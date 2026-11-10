-- OmniTrack Seed Data
-- Description: Sample records to verify PostgREST queries, active target notifications, and clean_title matching

INSERT INTO media_items (title, type, status, current_unit, total_units, unit_name, platform, source_url, is_active_target, rating, notes)
VALUES
    (
        'Shadow Slave',
        'novel',
        'in_progress',
        1750.00,
        NULL,
        'Chapter',
        'Webnovel',
        'https://www.webnovel.com/book/shadow-slave_22196546205090805',
        TRUE,
        9,
        'Active reading target for Android notification testing'
    ),
    (
        'The Primal Hunter',
        'novel',
        'in_progress',
        850.00,
        NULL,
        'Chapter',
        'Royal Road',
        'https://www.royalroad.com/fiction/36049/the-primal-hunter',
        FALSE,
        8,
        'Testing Royal Road scraper compatibility'
    ),
    (
        'Sousou no Frieren',
        'anime',
        'completed',
        28.00,
        28.00,
        'Episode',
        'Crunchyroll',
        'https://www.crunchyroll.com/series/GG5H5XQX4/frieren-beyond-journeys-end',
        FALSE,
        10,
        'Completed sample entry'
    )
ON CONFLICT DO NOTHING;

-- Insert corresponding progress history records for the active novel
INSERT INTO progress_history (media_id, previous_unit, new_unit, source)
SELECT id, 1749.00, 1750.00, 'extension'
FROM media_items
WHERE title = 'Shadow Slave'
LIMIT 1;
