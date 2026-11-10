-- OmniTrack Migration 002: RLS Policies, Active Target Constraint, and Realtime Configuration
-- Description: Configures Row Level Security, enforces single active target logic, and configures realtime sync.

-- 1. Enable Row Level Security (RLS)
ALTER TABLE media_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE progress_history ENABLE ROW LEVEL SECURITY;

-- 2. Define RLS Policies
-- OmniTrack is designed as a personal tracking backend. Clients authenticate using the Supabase Anon Key
-- or authenticated JWT. These policies permit full access to anon and authenticated roles.

-- media_items policies
DROP POLICY IF EXISTS "Allow anon and auth all on media_items" ON media_items;
CREATE POLICY "Allow anon and auth all on media_items"
    ON media_items
    FOR ALL
    TO anon, authenticated
    USING (true)
    WITH CHECK (true);

-- progress_history policies
DROP POLICY IF EXISTS "Allow anon and auth all on progress_history" ON progress_history;
CREATE POLICY "Allow anon and auth all on progress_history"
    ON progress_history
    FOR ALL
    TO anon, authenticated
    USING (true)
    WITH CHECK (true);

-- 3. Single Active Target Logic
-- Ensure at most one media item is pinned as active_target at any time.
-- When a row is set to is_active_target = true, automatically unset any other active items.

CREATE OR REPLACE FUNCTION ensure_single_active_target()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.is_active_target = TRUE THEN
        UPDATE media_items
        SET is_active_target = FALSE
        WHERE id <> NEW.id AND is_active_target = TRUE;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_ensure_single_active_target ON media_items;
CREATE TRIGGER trigger_ensure_single_active_target
BEFORE INSERT OR UPDATE OF is_active_target ON media_items
FOR EACH ROW
WHEN (NEW.is_active_target = TRUE)
EXECUTE FUNCTION ensure_single_active_target();

-- 4. Enable Supabase Realtime Replication
-- Allows Android app and Browser Extension to receive instant updates over WebSockets
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE media_items;
        ALTER PUBLICATION supabase_realtime ADD TABLE progress_history;
    END IF;
END $$;
