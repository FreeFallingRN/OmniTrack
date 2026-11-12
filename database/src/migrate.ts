import { drizzle } from "drizzle-orm/postgres-js";
import { migrate } from "drizzle-orm/postgres-js/migrator";
import postgres from "postgres";
import * as dotenv from "dotenv";
import * as path from "path";

// Load root .env or local .env
dotenv.config({ path: path.resolve(__dirname, "../../.env") });
dotenv.config();

const connectionString = process.env.DATABASE_URL || process.env.SUPABASE_DB_URL;

if (!connectionString) {
  console.error("❌ [ERROR] DATABASE_URL or SUPABASE_DB_URL environment variable is required.");
  process.exit(1);
}

async function runMigrations() {
  console.log("🚀 Starting database migrations...");
  console.log(`Connecting to: ${connectionString.replace(/:[^:@]+@/, ":****@")}`);

  const isLocalHost = connectionString.includes("localhost") || connectionString.includes("127.0.0.1");

  // Explicit SSL configuration prevents SELF_SIGNED_CERT_IN_CHAIN on Supabase connection poolers
  const sql = postgres(connectionString, {
    max: 1,
    ssl: isLocalHost ? false : { rejectUnauthorized: false },
    onnotice: () => {}, // Suppress verbose Postgres notice messages in CI logs
  });

  const db = drizzle(sql);

  try {
    const migrationsFolder = path.resolve(__dirname, "../migrations");
    console.log(`📂 Applying schema migrations from: ${migrationsFolder}`);

    await migrate(db, { migrationsFolder });
    console.log("✅ Drizzle schema migrations applied successfully.");

    // Apply database triggers, RLS policies, and realtime publications
    console.log("⚙️  Configuring PostgreSQL functions, triggers, and RLS policies...");

    await sql.unsafe(`
      -- 1. Ensure updated_at auto-update trigger function
      CREATE OR REPLACE FUNCTION update_updated_at_column()
      RETURNS TRIGGER AS $$
      BEGIN
          NEW.updated_at = now();
          RETURN NEW;
      END;
      $$ LANGUAGE plpgsql;

      -- Apply updated_at triggers safely if tables exist
      DO $$
      DECLARE
          tbl text;
          tables text[] := ARRAY['series', 'media_entries', 'authors'];
      BEGIN
          FOREACH tbl IN ARRAY tables LOOP
              IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = tbl) THEN
                  EXECUTE format('DROP TRIGGER IF EXISTS %I ON %I;', 'set_' || tbl || '_updated_at', tbl);
                  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();', 'set_' || tbl || '_updated_at', tbl);
              END IF;
          END LOOP;
      END $$;

      CREATE OR REPLACE FUNCTION ensure_single_active_entry()
      RETURNS TRIGGER AS $$
      BEGIN
          IF NEW.is_active_target = TRUE THEN
              UPDATE media_entries
              SET is_active_target = FALSE
              WHERE id <> NEW.id AND is_active_target = TRUE;
          END IF;
          RETURN NEW;
      END;
      $$ LANGUAGE plpgsql;

      DO $$
      BEGIN
          IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'media_entries') THEN
              DROP TRIGGER IF EXISTS trigger_ensure_single_active_entry ON media_entries;
              CREATE TRIGGER trigger_ensure_single_active_entry
              BEFORE INSERT OR UPDATE OF is_active_target ON media_entries
              FOR EACH ROW
              WHEN (NEW.is_active_target = TRUE)
              EXECUTE FUNCTION ensure_single_active_entry();
          END IF;
      END $$;

      CREATE OR REPLACE FUNCTION preserve_progress_history_start()
      RETURNS TRIGGER AS $$
      BEGIN
          NEW.previous_unit = OLD.previous_unit;
          RETURN NEW;
      END;
      $$ LANGUAGE plpgsql;

      DROP TRIGGER IF EXISTS trigger_preserve_progress_history_start ON progress_history;
      CREATE TRIGGER trigger_preserve_progress_history_start
      BEFORE UPDATE ON progress_history
      FOR EACH ROW
      EXECUTE FUNCTION preserve_progress_history_start();

      -- 2. Row Level Security (RLS) for all tables
      DROP TRIGGER IF EXISTS trigger_ensure_single_active_series ON series;

      DO $$
      DECLARE
          tbl text;
          tables text[] := ARRAY[
              'series',
              'media_entries',
              'alternate_titles',
              'authors',
              'entry_authors',
              'progress_history',
              'external_links'
          ];
      BEGIN
          FOREACH tbl IN ARRAY tables LOOP
              IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = tbl) THEN
                  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY;', tbl);
                  EXECUTE format('DROP POLICY IF EXISTS "Allow anon and auth all on %s" ON %I;', tbl, tbl);
                  EXECUTE format('CREATE POLICY "Allow anon and auth all on %s" ON %I FOR ALL TO anon, authenticated USING (true) WITH CHECK (true);', tbl, tbl);
              END IF;
          END LOOP;
      END $$;

      -- 3. Supabase Realtime publication
      DO $$
      DECLARE
          tbl text;
          tables text[] := ARRAY['series', 'media_entries', 'alternate_titles', 'progress_history', 'external_links'];
      BEGIN
          IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
              FOREACH tbl IN ARRAY tables LOOP
                  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = tbl) THEN
                      IF NOT EXISTS (
                          SELECT 1 FROM pg_publication_tables 
                          WHERE pubname = 'supabase_realtime' AND tablename = tbl
                      ) THEN
                          EXECUTE format('ALTER PUBLICATION supabase_realtime ADD TABLE %I;', tbl);
                      END IF;
                  END IF;
              END LOOP;
          END IF;
      END $$;
    `);

    console.log("✅ Triggers, RLS, and Realtime publications verified.");
    console.log("🎉 All migrations completed successfully!");
  } catch (error) {
    console.error("❌ Migration failed:", error);
    process.exitCode = 1;
  } finally {
    await sql.end({ timeout: 5 });
  }
}

runMigrations();