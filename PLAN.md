# Phone Hub — Enterprise Mobile IT Asset Management Plan

## 1. Executive Summary
A lightweight, mobile-first web platform to track corporate smartphones by **Serial Number (SN)**, **IMEI**, and **SIM Phone Number**, with **Role-Based Access Control (Owner / Admin / Viewer)** and **One-Click Asset Sheet Printing**.

*Security Boundary:* Strictly scoped to hardware inventory, custodian assignment, and device specifications. Financial credentials, banking data, and PINs are blocked at the validation layer.

---

## 2. Minimal Folder Structure
Zero-dependency Node.js standard library (`node:http` + `node:sqlite`) + single-file responsive HTML/CSS.

```text
phone-hub/
├── server.js            # HTTP API + SQLite database + RBAC + input guardrails
├── index.html           # Mobile-first UI + @media print stylesheet
├── presentation.html    # English executive overview & visual feature diagram
├── assets.db            # Auto-generated SQLite single-file database
└── .claude/
    └── launch.json      # Dev server configuration
```

---

## 3. Core Architecture & Features
1. **Instant Multi-Field Search:** Filter by `SN`, `Phone Number`, `IMEI`, `Model`, or `Custodian` in real time.
2. **Role-Based Access Control (RBAC):**
   - `Owner / Admin`: Full create & edit permissions on device records and custom hardware specs.
   - `Viewer (Standard User)`: Read-only search, view, and print access.
3. **Structured Spec Sections:**
   - **Section 1 — Device Core:** Serial Number (SN), IMEI, SIM Number, Model, Status (`Active`, `Spare`, `Repair`).
   - **Section 2 — Custodian:** Employee Name, Department / Branch.
   - **Section 3 — Additional Specs & Notes:** Color, Storage Capacity, Issued Accessories, General IT Notes.
4. **Mobile-Everywhere & Print-Ready:**
   - Responsive CSS Grid works on any smartphone browser without app store installation.
   - Native `@media print` stylesheet formats any device record into a clean A4/Letter handover sheet.

---

## 4. Implementation Phases
- **Phase 1 (Ready):** Core SQLite schema, RBAC auth (`scrypt`), sensitive-data input filter, responsive search & edit UI, print layout (`server.js`, `index.html`).
- **Phase 2 (Deployment):** Host on internal VPS / Cloud Run / Render with HTTPS for smartphone access from anywhere.
