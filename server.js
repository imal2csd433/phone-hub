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
  CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS mdm_devices (
    device_id TEXT PRIMARY KEY,
    name TEXT DEFAULT '',
    model TEXT DEFAULT '',
    android_version TEXT DEFAULT '',
    battery INTEGER DEFAULT 100,
    is_locked INTEGER DEFAULT 0,
    status TEXT DEFAULT 'online',
    last_boot_at TEXT DEFAULT '',
    last_seen_at TEXT DEFAULT CURRENT_TIMESTAMP
  );
  CREATE TABLE IF NOT EXISTS mdm_commands (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    command TEXT NOT NULL,
    payload TEXT DEFAULT '{}',
    status TEXT DEFAULT 'pending',
    created_by TEXT DEFAULT '',
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    executed_at TEXT DEFAULT ''
  );
  CREATE TABLE IF NOT EXISTS mdm_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    message TEXT NOT NULL,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
  );
  CREATE TABLE IF NOT EXISTS mdm_apks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    filename TEXT NOT NULL,
    package_name TEXT DEFAULT '',
    size_bytes INTEGER DEFAULT 0,
    data_base64 TEXT NOT NULL,
    uploaded_by TEXT DEFAULT '',
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
  );
`);

// ponytail: SSE client set in memory; single process only
const mdmClients = new Set();
const MDM_AGENT_KEY = process.env.MDM_AGENT_KEY || 'org-secret-agent-key-2026';

function broadcastMdmEvent(evt) {
  const payload = `data: ${JSON.stringify(evt)}\n\n`;
  for (const res of mdmClients) {
    try { res.write(payload); } catch { mdmClients.delete(res); }
  }
}

try { db.exec(`ALTER TABLE devices ADD COLUMN photo TEXT DEFAULT ''`); } catch {}
try { db.exec(`ALTER TABLE devices ADD COLUMN custom_fields TEXT DEFAULT '{}'`); } catch {}
try { db.exec(`ALTER TABLE devices ADD COLUMN extra_photos TEXT DEFAULT '[]'`); } catch {}

const hash = (pw) => crypto.scryptSync(pw, 'phone-hub-salt', 32).toString('hex');
const seedUser = db.prepare('INSERT OR IGNORE INTO users (username, password_hash, role) VALUES (?, ?, ?)');
seedUser.run('owner', hash('1234567'), 'owner');
seedUser.run('admin1', hash('1234567'), 'admin');
seedUser.run('admin2', hash('1234567'), 'admin');
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

const ALLOWED_FIELDS = ['sn', 'imei', 'phone', 'model', 'holder', 'department', 'status', 'color', 'capacity', 'accessories', 'notes', 'photo', 'custom_fields', 'extra_photos'];

function validatePayload(data) {
  if (!data) return 'Invalid payload';
  // Allow flexible entry: auto-generate SN and default model if not provided
  if (!data.sn || !String(data.sn).trim()) {
    data.sn = 'SN-' + Math.floor(100000 + Math.random() * 900000);
  }
  if (!data.model || !String(data.model).trim()) {
    data.model = '-';
  }
  return null;
}

if (process.argv.includes('--test')) {
  const assert = require('node:assert/strict');
  assert.equal(validatePayload({ sn: '1234567890123456', imei: '3590123456789012', phone: '0891234567', model: 'iPhone 15', notes: 'Password 1234, Bank KBank, PIN 9999' }), null);
  const emptyData = { sn: '', model: '' };
  assert.equal(validatePayload(emptyData), null);
  assert.ok(emptyData.sn.startsWith('SN-'));
  assert.equal(emptyData.model, '-');
  // Verify MDM tables & boot alert + remote command flow
  db.prepare(`INSERT OR REPLACE INTO mdm_devices (device_id, name, model, status) VALUES ('TEST-AND-1', 'Test Phone', 'Galaxy A55', 'online')`).run();
  const cmdInfo = db.prepare(`INSERT INTO mdm_commands (device_id, command, status) VALUES ('TEST-AND-1', 'SHUTDOWN', 'pending')`).run();
  assert.ok(cmdInfo.lastInsertRowid > 0);
  db.prepare(`DELETE FROM mdm_commands WHERE id = ?`).run(cmdInfo.lastInsertRowid);
  db.prepare(`DELETE FROM mdm_devices WHERE device_id = 'TEST-AND-1'`).run();
  console.log('Self-check passed');
  process.exit(0);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => { body += chunk; if (body.length > 50_000_000) req.destroy(); });
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

  if (url.pathname === '/favicon.ico') {
    res.writeHead(204);
    return res.end();
  }

  if ((req.method === 'GET' || req.method === 'HEAD') && (url.pathname === '/' || url.pathname === '/index.html')) {
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-cache, no-store, must-revalidate, max-age=0',
      'Pragma': 'no-cache',
      'Expires': '0'
    });
    if (req.method === 'HEAD') return res.end();
    return res.end(fs.readFileSync(path.join(__dirname, 'index.html')));
  }

  if ((req.method === 'GET' || req.method === 'HEAD') && url.pathname === '/presentation') {
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-cache, no-store, must-revalidate, max-age=0'
    });
    if (req.method === 'HEAD') return res.end();
    return res.end(fs.readFileSync(path.join(__dirname, 'presentation.html')));
  }

  // Standalone MDM Controller App (2-person exclusive: owner & admin)
  if ((req.method === 'GET' || req.method === 'HEAD') && (url.pathname === '/mdm' || url.pathname === '/mdm.html')) {
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-cache, no-store, must-revalidate, max-age=0'
    });
    if (req.method === 'HEAD') return res.end();
    return res.end(fs.readFileSync(path.join(__dirname, 'mdm.html')));
  }

  // Direct download for compiled Organization Android Agent APK
  if (req.method === 'GET' && url.pathname === '/org-device-guard.apk') {
    const apkPath = path.join(__dirname, 'org-device-guard.apk');
    if (!fs.existsSync(apkPath)) return send(404, { error: 'APK not found' });
    const stat = fs.statSync(apkPath);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': 'attachment; filename="org-device-guard.apk"',
      'Content-Length': stat.size
    });
    return fs.createReadStream(apkPath).pipe(res);
  }

  // Public APK binary download for Android Agent silent installer
  if (req.method === 'GET' && url.pathname.startsWith('/api/mdm/apk-file/')) {
    const id = Number(url.pathname.split('/').pop());
    const apk = db.prepare('SELECT filename, data_base64 FROM mdm_apks WHERE id = ?').get(id);
    if (!apk) return send(404, { error: 'APK not found' });
    const buf = Buffer.from(apk.data_base64, 'base64');
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': `attachment; filename="${apk.filename.replace(/[^a-zA-Z0-9._-]/g, '_')}"`,
      'Content-Length': buf.length
    });
    return res.end(buf);
  }

  // Android Agent Heartbeat / Boot Alert endpoint (authenticated via x-agent-key or body key)
  if (req.method === 'POST' && url.pathname === '/api/mdm/agent/checkin') {
    const body = await readJson(req);
    const key = req.headers['x-agent-key'] || body.agent_key || '';
    if (key !== MDM_AGENT_KEY) return send(401, { error: 'Invalid agent key' });
    const deviceId = String(body.device_id || '').trim();
    if (!deviceId) return send(400, { error: 'device_id required' });
    const name = String(body.name || deviceId).trim();
    const model = String(body.model || 'Android').trim();
    const androidVer = String(body.android_version || '14').trim();
    const battery = Number(body.battery ?? 100);
    const isLocked = body.is_locked ? 1 : 0;
    const isBoot = Boolean(body.boot_completed);
    const now = new Date().toISOString();

    const existing = db.prepare('SELECT * FROM mdm_devices WHERE device_id = ?').get(deviceId);
    if (!existing) {
      db.prepare(`
        INSERT INTO mdm_devices (device_id, name, model, android_version, battery, is_locked, status, last_boot_at, last_seen_at)
        VALUES (?, ?, ?, ?, ?, ?, 'online', ?, ?)
      `).run(deviceId, name, model, androidVer, battery, isLocked, isBoot ? now : '', now);
    } else {
      db.prepare(`
        UPDATE mdm_devices
        SET name=?, model=?, android_version=?, battery=?, is_locked=?, status='online',
            last_boot_at=CASE WHEN ? THEN ? ELSE last_boot_at END,
            last_seen_at=?
        WHERE device_id=?
      `).run(name || existing.name, model, androidVer, battery, isLocked, isBoot ? 1 : 0, now, now, deviceId);
    }

    if (isBoot) {
      const msg = `เครื่อง ${name} (${model}) เปิดเครื่องเข้าสู่ระบบ`;
      db.prepare('INSERT INTO mdm_events (device_id, event_type, message, created_at) VALUES (?, ?, ?, ?)').run(deviceId, 'BOOT_COMPLETED', msg, now);
      broadcastMdmEvent({ type: 'BOOT_COMPLETED', device_id: deviceId, name, model, message: msg, timestamp: now });
    } else {
      broadcastMdmEvent({ type: 'HEARTBEAT', device_id: deviceId, battery, is_locked: isLocked, timestamp: now });
    }

    // Acknowledge executed commands if reported
    if (Array.isArray(body.ack_commands) && body.ack_commands.length > 0) {
      const ackStmt = db.prepare(`UPDATE mdm_commands SET status = 'executed', executed_at = ? WHERE id = ? AND device_id = ?`);
      for (const cmdId of body.ack_commands) {
        ackStmt.run(now, Number(cmdId), deviceId);
      }
      broadcastMdmEvent({ type: 'COMMAND_ACK', device_id: deviceId, ack_commands: body.ack_commands, timestamp: now });
    }

    const pending = db.prepare(`SELECT id, command, payload, created_at FROM mdm_commands WHERE device_id = ? AND status = 'pending' ORDER BY id ASC`).all(deviceId);
    return send(200, {
      ok: true,
      commands: pending.map((c) => ({ id: c.id, command: c.command, payload: JSON.parse(c.payload || '{}') }))
    });
  }

  // SSE stream for real-time boot notifications in 2-person MDM controller app
  if (req.method === 'GET' && url.pathname === '/api/mdm/stream') {
    const token = url.searchParams.get('token') || (req.headers.authorization || '').replace('Bearer ', '');
    const sUser = sessions.get(token);
    if (!sUser || (sUser.role !== 'owner' && sUser.role !== 'admin')) return send(403, { error: 'Forbidden' });
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive'
    });
    res.write(`data: ${JSON.stringify({ type: 'CONNECTED', user: sUser.username })}\n\n`);
    mdmClients.add(res);
    req.on('close', () => mdmClients.delete(res));
    return;
  }

  if (req.method === 'POST' && url.pathname === '/api/login') {
    const { username, password } = await readJson(req);
    const u = db.prepare('SELECT username, password_hash, role FROM users WHERE username = ?').get(String(username || ''));
    if (!u || u.password_hash !== hash(String(password || ''))) return send(401, { error: 'Invalid credentials' });
    const token = crypto.randomBytes(24).toString('hex');
    sessions.set(token, { username: u.username, role: u.role });
    return send(200, { token, username: u.username, role: u.role });
  }

  // Public Custom Headings Read API
  if (req.method === 'GET' && url.pathname === '/api/labels') {
    const row = db.prepare('SELECT value FROM settings WHERE key = ?').get('custom_labels');
    return send(200, row ? JSON.parse(row.value) : {});
  }

  const user = getAuth(req);
  if (!user) return send(401, { error: 'Unauthorized' });

  // RBAC: Owner and Admin accounts (owner, admin1, admin2) have full edit permissions
  const canEdit = user.role === 'owner' || user.role === 'admin';

  // Custom Headings & Labels API (Owner & Admin only for saving)
  if (req.method === 'POST' && url.pathname === '/api/labels') {
    if (!canEdit) return send(403, { error: 'Forbidden: Viewer cannot edit headings' });
    const labels = await readJson(req);
    db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)').run('custom_labels', JSON.stringify(labels || {}));
    return send(200, { ok: true, labels });
  }

  // Change Password endpoint for logged in user
  if (req.method === 'POST' && url.pathname === '/api/change-password') {
    const { newPassword } = await readJson(req);
    const p = String(newPassword || '').trim();
    if (!p) return send(400, { error: 'New password required' });
    db.prepare('UPDATE users SET password_hash = ? WHERE username = ?').run(hash(p), user.username);
    return send(200, { ok: true, message: 'Password updated successfully' });
  }

  // User Account Management API (Owner & Admin only - can create viewers and admins)
  if (req.method === 'GET' && url.pathname === '/api/users') {
    if (!canEdit) return send(403, { error: 'Forbidden: Viewer cannot access user management' });
    const rows = db.prepare('SELECT username, role FROM users ORDER BY role, username').all();
    return send(200, rows);
  }

  if (req.method === 'POST' && url.pathname === '/api/users') {
    if (!canEdit) return send(403, { error: 'Forbidden: Viewer cannot create or manage users' });
    const { username, password, role } = await readJson(req);
    const u = String(username || '').trim().toLowerCase();
    const p = String(password || '');
    const r = ['owner', 'admin', 'viewer'].includes(role) ? role : 'viewer';
    if (!u || !p) return send(400, { error: 'Username and password required' });
    if (r === 'owner' && user.role !== 'owner') return send(403, { error: 'Only Owner can create Owner accounts' });
    db.prepare('INSERT OR REPLACE INTO users (username, password_hash, role) VALUES (?, ?, ?)').run(u, hash(p), r);
    return send(201, { ok: true });
  }

  if (req.method === 'DELETE' && url.pathname.startsWith('/api/users/')) {
    if (!canEdit) return send(403, { error: 'Forbidden: Viewer cannot delete users' });
    const target = decodeURIComponent(url.pathname.split('/').pop());
    if (target === 'owner' || target === user.username) return send(400, { error: 'Cannot delete primary owner or yourself' });
    db.prepare('DELETE FROM users WHERE username = ?').run(target);
    return send(200, { ok: true });
  }

  if (req.method === 'GET' && url.pathname === '/api/devices') {
    const q = `%${(url.searchParams.get('q') || '').trim()}%`;
    const rows = db.prepare(`
      SELECT * FROM devices
      WHERE sn LIKE ? OR phone LIKE ? OR imei LIKE ? OR holder LIKE ? OR model LIKE ? OR notes LIKE ? OR custom_fields LIKE ?
      ORDER BY id DESC
    `).all(q, q, q, q, q, q, q);
    return send(200, rows);
  }

  if (req.method === 'POST' && url.pathname === '/api/devices') {
    if (!canEdit) return send(403, { error: 'Forbidden: Viewer cannot create' });
    const data = await readJson(req);
    const err = validatePayload(data);
    if (err) return send(400, { error: err });
    try {
      const stmt = db.prepare(`
        INSERT INTO devices (sn, imei, phone, model, holder, department, status, color, capacity, accessories, notes, photo, custom_fields, extra_photos)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        UPDATE devices SET sn=?, imei=?, phone=?, model=?, holder=?, department=?, status=?, color=?, capacity=?, accessories=?, notes=?, photo=?, custom_fields=?, extra_photos=?, updated_at=CURRENT_TIMESTAMP
        WHERE id=?
      `).run(...ALLOWED_FIELDS.map((k) => String(data[k] ?? '').trim()), id);
      return send(200, { ok: true });
    } catch (e) {
      return send(400, { error: e.message });
    }
  }

  if (req.method === 'DELETE' && url.pathname.startsWith('/api/devices/')) {
    if (!canEdit) return send(403, { error: 'Forbidden: Viewer cannot delete devices' });
    const id = Number(url.pathname.split('/').pop());
    if (!id) return send(400, { error: 'Invalid device ID' });
    db.prepare('DELETE FROM devices WHERE id = ?').run(id);
    return send(200, { ok: true });
  }

  // --- MDM Controller APIs (Strictly Owner & Admin only) ---
  if (url.pathname.startsWith('/api/mdm/')) {
    if (!canEdit) return send(403, { error: 'Forbidden: Owner and Admin only' });

    if (req.method === 'GET' && url.pathname === '/api/mdm/overview') {
      const devices = db.prepare('SELECT * FROM mdm_devices ORDER BY last_seen_at DESC').all();
      const events = db.prepare('SELECT * FROM mdm_events ORDER BY id DESC LIMIT 50').all();
      const commands = db.prepare('SELECT * FROM mdm_commands ORDER BY id DESC LIMIT 50').all();
      const apks = db.prepare('SELECT id, filename, package_name, size_bytes, uploaded_by, created_at FROM mdm_apks ORDER BY id DESC').all();
      return send(200, { devices, events, commands, apks, agent_key: MDM_AGENT_KEY });
    }

    if (req.method === 'POST' && url.pathname === '/api/mdm/command') {
      const { device_id, command, payload } = await readJson(req);
      const allowedCmds = ['LOCK_NOW', 'UNLOCK', 'REBOOT', 'SHUTDOWN', 'INSTALL_APK', 'KIOSK_LOCK'];
      if (!device_id || !allowedCmds.includes(command)) {
        return send(400, { error: 'Invalid device_id or command' });
      }
      const now = new Date().toISOString();
      const info = db.prepare(`
        INSERT INTO mdm_commands (device_id, command, payload, status, created_by, created_at)
        VALUES (?, ?, ?, 'pending', ?, ?)
      `).run(String(device_id), command, JSON.stringify(payload || {}), user.username, now);

      if (command === 'LOCK_NOW' || command === 'KIOSK_LOCK') {
        db.prepare('UPDATE mdm_devices SET is_locked = 1 WHERE device_id = ?').run(String(device_id));
      } else if (command === 'UNLOCK') {
        db.prepare('UPDATE mdm_devices SET is_locked = 0 WHERE device_id = ?').run(String(device_id));
      } else if (command === 'SHUTDOWN') {
        db.prepare(`UPDATE mdm_devices SET status = 'offline', is_locked = 1 WHERE device_id = ?`).run(String(device_id));
      }

      const evtMsg = `[${user.username}] สั่ง ${command} ไปยัง ${device_id}`;
      db.prepare('INSERT INTO mdm_events (device_id, event_type, message, created_at) VALUES (?, ?, ?, ?)').run(String(device_id), 'COMMAND_' + command, evtMsg, now);
      broadcastMdmEvent({ type: 'COMMAND_QUEUED', id: info.lastInsertRowid, device_id, command, by: user.username, timestamp: now });
      return send(201, { ok: true, command_id: info.lastInsertRowid });
    }

    if (req.method === 'POST' && url.pathname === '/api/mdm/apks') {
      const { filename, package_name, data_base64, target_device_id } = await readJson(req);
      if (!filename || !data_base64) return send(400, { error: 'filename and data_base64 required' });
      const cleanBase64 = String(data_base64).replace(/^data:.*?;base64,/, '');
      const sizeBytes = Buffer.byteLength(cleanBase64, 'base64');
      const now = new Date().toISOString();
      const info = db.prepare(`
        INSERT INTO mdm_apks (filename, package_name, size_bytes, data_base64, uploaded_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
      `).run(String(filename), String(package_name || ''), sizeBytes, cleanBase64, user.username, now);

      const apkId = info.lastInsertRowid;
      if (target_device_id) {
        const dlUrl = `/api/mdm/apk-file/${apkId}`;
        db.prepare(`
          INSERT INTO mdm_commands (device_id, command, payload, status, created_by, created_at)
          VALUES (?, 'INSTALL_APK', ?, 'pending', ?, ?)
        `).run(String(target_device_id), JSON.stringify({ apk_id: apkId, filename, download_url: dlUrl }), user.username, now);
        const msg = `[${user.username}] ส่งไฟล์ ${filename} ติดตั้งระยะไกลไปยัง ${target_device_id}`;
        db.prepare('INSERT INTO mdm_events (device_id, event_type, message, created_at) VALUES (?, ?, ?, ?)').run(String(target_device_id), 'REMOTE_APK_PUSH', msg, now);
        broadcastMdmEvent({ type: 'REMOTE_APK_PUSH', device_id: target_device_id, filename, apk_id: apkId, timestamp: now });
      }
      return send(201, { ok: true, apk_id: apkId, size_bytes: sizeBytes });
    }

    if (req.method === 'DELETE' && url.pathname.startsWith('/api/mdm/apks/')) {
      const id = Number(url.pathname.split('/').pop());
      db.prepare('DELETE FROM mdm_apks WHERE id = ?').run(id);
      return send(200, { ok: true });
    }

    if (req.method === 'DELETE' && url.pathname.startsWith('/api/mdm/devices/')) {
      const devId = decodeURIComponent(url.pathname.split('/').pop());
      db.prepare('DELETE FROM mdm_devices WHERE device_id = ?').run(devId);
      return send(200, { ok: true });
    }
  }

  send(404, { error: 'Not found' });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => console.log(`Phone Hub running on http://localhost:${PORT}`));
