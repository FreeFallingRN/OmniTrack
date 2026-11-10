-- OmniTrack Migration 001: Initial Schema
-- Description: Core tables for media tracking and granular history logging

-- Enable UUID extension (gen_random_uuid is native in PG 13+, uuid-ossp for backwards compatibility)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Core media storage table
CREATE TABLE IF NOT EXISTS media_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT NOT NULL,
    clean_title TEXT GENERATED ALWAYS AS (lower(regexp_replace(title, '[^a-zA-Z0-9]', '', 'g'))) STORED,
    type TEXT NOT NULL CHECK (type IN ('novel', 'manga', 'anime', 'movie', 'tv')),
    status TEXT NOT NULL DEFAULT 'in_progress' 
        CHECK (status IN ('plan_to_read', 'in_progress', 'completed', 'on_hold', 'dropped')),
    
    -- Numeric tracking accommodates decimal chapters (e.g. 45.5)
    current_unit NUMERIC(7, 2) NOT NULL DEFAULT 0.00,
    total_units NUMERIC(7, 2) DEFAULT NULL,
    unit_name TEXT NOT NULL DEFAULT 'Chapter', -- 'Chapter', 'Episode', 'Volume'
    
    -- Metadata
    source_url TEXT,
    platform TEXT,                             -- 'Royal Road', 'Moon+ Reader', 'Crunchyroll', etc.
    rating SMALLINT CHECK (rating BETWEEN 1 AND 10),
    notes TEXT,
    is_active_target BOOLEAN DEFAULT FALSE,    -- Pinned item for rapid Android notification tracking
    
    updated_at TIMESTAMPTZ DEFAULT now() NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now() NOT NULL
);

-- Granular history logging table
CREATE TABLE IF NOT EXISTS progress_history (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    media_id UUID REFERENCES media_items(id) ON DELETE CASCADE NOT NULL,
    previous_unit NUMERIC(7, 2) NOT NULL,
    new_unit NUMERIC(7, 2) NOT NULL,
    source TEXT NOT NULL,                      -- 'android_notification', 'moon_reader_sync', 'extension'
    logged_at TIMESTAMPTZ DEFAULT now() NOT NULL
);

-- Auto-update updated_at timestamp function
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to update updated_at automatically on media_items modification
DROP TRIGGER IF EXISTS set_media_items_updated_at ON media_items;
CREATE TRIGGER set_media_items_updated_at
BEFORE UPDATE ON media_items
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_media_items_status ON media_items(status);
CREATE INDEX IF NOT EXISTS idx_media_items_clean_title ON media_items(clean_title);
CREATE INDEX IF NOT EXISTS idx_media_items_is_active ON media_items(is_active_target) WHERE is_active_target = TRUE;
CREATE INDEX IF NOT EXISTS idx_progress_history_media_id ON progress_history(media_id);
CREATE INDEX IF NOT EXISTS idx_progress_history_logged_at ON progress_history(logged_at DESC);
