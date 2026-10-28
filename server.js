const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');

// ponytail: SQLite single-file DB; swap to Postgres when multi-node needed
const db = new DatabaseSync(path.join(__dirname, 'assets.db'));
db.exec(`
  CREATE TABLE IF NOT EXISTS devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sn TEXT UNIQUE NOT NULL,
    imei TEXT DEFAULT '',
    phone TEXT DEFAULT '',
    model TEXT NOT NULL,
    holder TEXT DEFAULT '',
    department TEXT DEFAULT '',
    status TEXT DEFAULT 'Active',
    color TEXT DEFAULT '',
    capacity TEXT DEFAULT '',
    accessories TEXT DEFAULT '',
    notes TEXT DEFAULT '',
    photo TEXT DEFAULT '',
    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
  );
  CREATE TABLE IF NOT EXISTS users (
    username TEXT PRIMARY KEY,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL
  );
`);

// Add photo column if upgrading existing table
try { db.exec(`ALTER TABLE devices ADD COLUMN photo TEXT DEFAULT ''`); } catch {}

const hash = (pw) => crypto.scryptSync(pw, 'phone-hub-salt', 32).toString('hex');
const seedUser = db.prepare('INSERT OR IGNORE INTO users (username, password_hash, role) VALUES (?, ?, ?)');
seedUser.run('owner', hash('owner123'), 'owner');
seedUser.run('admin', hash('admin123'), 'admin');
seedUser.run('viewer', hash('viewer123'), 'viewer');

const count = db.prepare('SELECT COUNT(*) as c FROM devices').get().c;
if (count === 0) {
  db.prepare(`INSERT INTO devices (sn, imei, phone, model, holder, department, status, color, capacity, accessories, notes, photo)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(
    'SN-8849201', '359012345678901', '081-234-5678', 'iPhone 15 Pro',
    'Somchai B.', 'Sales (Bang Na)', 'Active', 'Black', '256GB', 'USB-C Adapter, Case', 'Issued Jan 2026', ''
  );
}

// ponytail: in-memory token map; move to SQLite table when persistent sessions needed
const sessions = new Map();

const ALLOWED_FIELDS = ['sn', 'imei', 'phone', 'model', 'holder', 'department', 'status', 'color', 'capacity', 'accessories', 'notes', 'photo'];
const TEXT_CHECK_FIELDS = ['model', 'holder', 'department', 'color', 'capacity', 'accessories', 'notes'];
// Block only explicit banking/credential keywords in text fields; numeric fields (SN/IMEI/Phone) are unrestricted
const BLOCKED_KEYWORDS = /\b(password|passcode|pin|cvv|otp|tac|bank|atm|debit|credit\s*card|saving|account\s*number|ktb|kbank|scb|bbl|security\s*question)\b|(รหัสผ่าน|รหัสกดเงิน|คำถามความปลอดภัย|เลขบัญชี|บัตรเอทีเอ็ม|ธนาคาร)/i;

function validatePayload(data) {
  if (!data || typeof data.sn !== 'string' || !data.sn.trim() || typeof data.model !== 'string' || !data.model.trim()) {
    return 'SN and Model are required';
  }
  for (const k of TEXT_CHECK_FIELDS) {
    const val = String(data[k] ?? '');
    if (val.length > 1000) return `${k} exceeds max length`;
    if (BLOCKED_KEYWORDS.test(val)) return `Restricted keyword detected in ${k}`;
  }
  const photo = String(data.photo ?? '');
  if (photo && (!photo.startsWith('data:image/') || photo.length > 2_500_000)) {
    return 'Invalid or oversized image (max ~1.8MB)';
  }
  return null;
}

if (process.argv.includes('--test')) {
  const assert = require('node:assert/strict');
  // 16-digit numbers in SN/IMEI/Phone must pass without false-positive
  assert.equal(validatePayload({ sn: '1234567890123456', imei: '3590123456789012', phone: '0891234567', model: 'iPhone 15', notes: 'Normal note 1234567890123456' }), null);
  assert.notEqual(validatePayload({ sn: '', model: 'Pixel 9' }), null);
  assert.notEqual(validatePayload({ sn: 'SN2', model: 'Pixel 9', notes: 'my bank account is here' }), null);
  console.log('Self-check passed');
  process.exit(0);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => { body += chunk; if (body.length > 3_000_000) req.destroy(); });
    req.on('end', () => { try { resolve(JSON.parse(body || '{}')); } catch (e) { reject(e); } });
  });
}

function getAuth(req) {
  const token = (req.headers.authorization || '').replace('Bearer ', '');
  return sessions.get(token) || null;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const send = (code, obj) => {
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(obj));
  };

  if (req.method === 'GET' && url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(fs.readFileSync(path.join(__dirname, 'index.html')));
  }

  if (req.method === 'GET' && url.pathname === '/presentation') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(fs.readFileSync(path.join(__dirname, 'presentation.html')));
  }

  if (req.method === 'POST' && url.pathname === '/api/login') {
    const { username, password } = await readJson(req);
    const u = db.prepare('SELECT username, password_hash, role FROM users WHERE username = ?').get(String(username || ''));
    if (!u || u.password_hash !== hash(String(password || ''))) return send(401, { error: 'Invalid credentials' });
    const token = crypto.randomBytes(24).toString('hex');
    sessions.set(token, { username: u.username, role: u.role });
    return send(200, { token, username: u.username, role: u.role });
  }

  const user = getAuth(req);
  if (!user) return send(401, { error: 'Unauthorized' });

  if (req.method === 'GET' && url.pathname === '/api/devices') {
    const q = `%${(url.searchParams.get('q') || '').trim()}%`;
    const rows = db.prepare(`
      SELECT * FROM devices
      WHERE sn LIKE ? OR phone LIKE ? OR imei LIKE ? OR holder LIKE ? OR model LIKE ?
      ORDER BY id DESC
    `).all(q, q, q, q, q);
    return send(200, rows);
  }

  const canEdit = user.role === 'owner' || user.role === 'admin';

  if (req.method === 'POST' && url.pathname === '/api/devices') {
    if (!canEdit) return send(403, { error: 'Forbidden: Viewer cannot create' });
    const data = await readJson(req);
    const err = validatePayload(data);
    if (err) return send(400, { error: err });
    try {
      const stmt = db.prepare(`
        INSERT INTO devices (sn, imei, phone, model, holder, department, status, color, capacity, accessories, notes, photo)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `);
      const info = stmt.run(...ALLOWED_FIELDS.map((k) => String(data[k] ?? '').trim()));
      return send(201, { id: info.lastInsertRowid });
    } catch (e) {
      return send(400, { error: e.message });
    }
  }

  if (req.method === 'PUT' && url.pathname.startsWith('/api/devices/')) {
    if (!canEdit) return send(403, { error: 'Forbidden: Viewer cannot edit' });
    const id = Number(url.pathname.split('/').pop());
    const data = await readJson(req);
    const err = validatePayload(data);
    if (err) return send(400, { error: err });
    try {
      db.prepare(`
        UPDATE devices SET sn=?, imei=?, phone=?, model=?, holder=?, department=?, status=?, color=?, capacity=?, accessories=?, notes=?, photo=?, updated_at=CURRENT_TIMESTAMP
        WHERE id=?
      `).run(...ALLOWED_FIELDS.map((k) => String(data[k] ?? '').trim()), id);
      return send(200, { ok: true });
    } catch (e) {
      return send(400, { error: e.message });
    }
  }

  send(404, { error: 'Not found' });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => console.log(`Phone Hub running on http://localhost:${PORT}`));
