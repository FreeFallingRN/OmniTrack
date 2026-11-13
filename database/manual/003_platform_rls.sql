-- platforms policies
DROP POLICY IF EXISTS "Allow anon and auth all on media_items" ON platforms;
CREATE POLICY "Allow anon and auth all on platforms"
    ON platforms
    FOR ALL
    TO anon, authenticated
    USING (true)
    WITH CHECK (true);