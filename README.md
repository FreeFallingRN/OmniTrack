# OmniTrack

> A personal, cross-platform media and webnovel tracking engine designed to replace platforms like MyAnimeList. OmniTrack provides custom catalog support for non-serialized webnovels, zero-latency progress logging, cross-device synchronization, and automated ingestion from Moon+ Reader.

---

## Architecture Overview

```
                              ┌─────────────────────────────────────────┐
                              │            Supabase Cloud               │
                              │  - PostgreSQL 15+ (Relational Store)    │
                              │  - PostgREST (Auto-generated REST API)  │
                              │  - Realtime Engine (WebSockets)         │
                              └────────────────────┬────────────────────┘
                                                   │
                ┌──────────────────────────────────┼──────────────────────────────────┐
                │ HTTPS (PostgREST API)            │ HTTPS (PostgREST API)            │ HTTPS (PostgREST API)
                ▼                                  ▼                                  ▼
   ┌─────────────────────────┐        ┌─────────────────────────┐        ┌─────────────────────────┐
   │       Android App       │        │  Desktop Browser Ext.   │        │ Google Apps Script Sync │
   │  - Kotlin / Compose     │        │  - Chrome / Manifest V3 │        │  - Headless Cron Worker │
   │  - Room Local Cache     │        │  - DOM/URL Parser       │        │  - Clasp CI/CD via GHA  │
   │  - Action Notification  │        │  - Global Shortcuts     │        └────────────▲────────────┘
   │  - Android Share Target │        └─────────────────────────┘                     │
   └────────────▲────────────┘                                                        │ Google Drive API
                │                                                                     │
   ┌────────────┴────────────┐                                           ┌────────────┴────────────┐
   │ Android System Services │                                           │   Moon+ Reader Pro      │
   │  - BroadcastReceiver    │                                           │  - Syncs .po position   │
   │  - WorkManager Sync     │                                           │    files to Drive Cache │
   └─────────────────────────┘                                           └─────────────────────────┘
```

---

## Monorepo Layout

```
omnitrack/
├── .github/
│   └── workflows/
│       ├── deploy-database-migrations.yml # Auto-migrates Supabase DB on push to main
│       ├── deploy-sync-worker.yml         # GAS Clasp deployment via GitHub Actions
│       └── build-android.yml              # Android linting, tests, and APK build
├── database/
│   ├── src/
│   │   ├── schema.ts            # Declarative TypeScript Entity Model (Drizzle ORM)
│   │   ├── migrate.ts           # Migration runner + trigger/RLS installer
│   │   └── index.ts             # Type exports for consumers
│   ├── migrations/              # Auto-generated SQL migration files & journal
│   │   ├── 0000_initial_schema.sql
│   │   └── meta/_journal.json
│   ├── manual/                  # Standalone raw SQL scripts (reference)
│   ├── seed.sql                 # Sample development data
│   ├── drizzle.config.ts        # Drizzle Kit configuration
│   ├── package.json             # Database migration tooling dependencies
│   └── verify_connection.ps1    # PostgREST verification test script
├── sync-worker/                 # Google Apps Script syncing Moon+ Reader from Drive
├── extension/                   # Chrome Manifest V3 Browser Extension (Vite + TS)
└── android/                     # Native Android Application (Kotlin + Jetpack Compose)
```

---

## Database Management & Entity Modeling

OmniTrack uses **Drizzle ORM** for declarative TypeScript entity modeling and migration generation. You define and modify models in TypeScript—Drizzle automatically generates versioned, production-safe SQL migrations.

### How It Works:
1. **Define / Update Models**: Edit [`database/src/schema.ts`](file:///c:/Users/benjv/Dev/OmniTrack/database/src/schema.ts) in TypeScript.
2. **Generate Migration**: Run `npm run db:generate` in the `database/` folder. Drizzle computes the diff against your previous migrations and writes a new versioned SQL migration into `database/migrations/`.
3. **Commit & Push to `main`**: GitHub Actions (`deploy-database-migrations.yml`) detects changes in `database/` and automatically applies the migration to your Supabase Cloud production database.
4. **Local Migration (Optional)**: Run `npm run db:migrate` with `DATABASE_URL` configured in `.env`.

---

## Getting Started: Supabase & CI/CD Setup

### 1. Create Supabase Cloud Project
- Create a project on [supabase.com](https://supabase.com).
- Under **Project Settings -> Database -> Connection string**, copy the URI (e.g. `postgresql://postgres:[password]@db.[project-ref].supabase.co:5432/postgres` or Connection Pooler on port 6543).

### 2. Configure GitHub Secret for CI/CD Migrations
- In your GitHub Repository: Go to **Settings -> Secrets and variables -> Actions**.
- Add a new repository secret:
  - **Name**: `SUPABASE_DB_URL`
  - **Value**: Your Supabase PostgreSQL connection string.
- Any push to `main` touching `database/**` will now automatically run the migrations and configure RLS, triggers, and Realtime!

### 3. Local Verification
- Copy `.env.example` to `.env` and fill in `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `DATABASE_URL`.
- Run the PostgREST verification test:
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File database/verify_connection.ps1
  ```
