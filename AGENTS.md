# AGENTS.md — AI Handoff & Developer Guide

## Overview
**Phone Hub** is a zero-dependency Node.js + SQLite web application for managing corporate mobile devices. Any AI agent or developer can modify this project directly without installing npm packages.

## File Map
- `server.js` — Node.js built-in `node:http` + `node:sqlite` backend, RBAC authentication (`owner`, `admin`, `viewer`), REST endpoints (`/api/login`, `GET/POST /api/devices`, `PUT /api/devices/:id`), and self-test suite (`node server.js --test`).
- `index.html` — Single-file responsive frontend with Industrial Ledger UI, TH/EN language switcher, instant SN/Phone/IMEI search, base64 image compression/upload at the bottom of the record, and `@media print` sheet formatting. Includes automatic `localStorage` fallback when opened statically.
- `presentation.html` — English executive slide deck with 4 UI screen mockups and a 4-step user guide.
- `PLAN.md` — System architecture summary.

## Commands
- Start server: `node server.js` (runs on `http://localhost:3000`)
- Run self-checks: `node server.js --test`
