# OmniTrack Database Entity Diagram

```mermaid
erDiagram
    SERIES |o--o{ MEDIA_ENTRIES : "contains books or seasons (optional)"
    SERIES ||--o{ ALTERNATE_TITLES : "has alternate/translated titles"
    MEDIA_ENTRIES ||--o{ ALTERNATE_TITLES : "entry-specific titles"
    SERIES ||--o{ EXTERNAL_LINKS : "platform/store reading URLs"
    MEDIA_ENTRIES ||--o{ EXTERNAL_LINKS : "entry-specific store URLs"
    SERIES ||--o{ SERIES_AUTHORS : "credited to"
    AUTHORS ||--o{ SERIES_AUTHORS : "created"
    SERIES ||--o{ MEDIA_RELATIONS : "source / adaptation"
    SERIES ||--o{ PROGRESS_HISTORY : "series progress"
    MEDIA_ENTRIES ||--o{ PROGRESS_HISTORY : "entry progress"

    SERIES {
        uuid id PK "gen_random_uuid()"
        text title "Primary title"
        text clean_title "Generated lowercase alphanumeric"
        media_type type "novel | comic | anime | movie"[cite: 8]
        publishing_status publishing_status "announced | releasing | hiatus | completed | discontinued"[cite: 8]
        user_status user_status "planning | in_progress | completed | paused | dropped"[cite: 8]
        numeric current_cumulative_unit "Total cumulative units logged"
        numeric total_units "Overall series chapters/episodes"
        text unit_name "Chapter | Episode | Volume"
        smallint rating "1 - 10"
        text notes "User notes & commentary"
        boolean is_active_target "Pinned active series"
        timestamptz created_at "Audit timestamp"
        timestamptz updated_at "Audit timestamp (auto-trigger)"
    }

    MEDIA_ENTRIES {
        uuid id PK "gen_random_uuid()"
        uuid series_id FK "References series(id) ON DELETE CASCADE (Nullable for standalone works)"[cite: 10]
        text title "e.g. 'Book 1: Unsouled', 'Season 2 Part 2'"[cite: 10]
        media_type type "novel | comic | anime | movie (required for standalone entries)"
        numeric sequence_number "Order: 1.00, 2.00, 2.50..."[cite: 10]
        publishing_status publishing_status "announced | releasing | hiatus | completed | discontinued"[cite: 8, 10]
        user_status user_status "planning | in_progress | completed | paused | dropped"[cite: 8, 10]
        numeric current_unit "Chapter/Ep inside this book/season"[cite: 10]
        numeric total_units "Total units in this book/season"[cite: 10]
        numeric start_cumulative_unit "Offset for series cumulative total"[cite: 10]
        text unit_name "Chapter | Episode"[cite: 10]
        text source_url "Legacy/direct reading URL"[cite: 10]
        text platform "Moon+ Reader | Royal Road | Crunchyroll"[cite: 10]
        boolean is_active_target "Pinned active entry"[cite: 10]
        timestamptz created_at "Audit timestamp"[cite: 10]
        timestamptz updated_at "Audit timestamp (auto-trigger)"[cite: 10]
    }

    EXTERNAL_LINKS {
        bigint id PK "Auto-incrementing identity"
        uuid series_id FK "References series(id) ON DELETE CASCADE"
        uuid entry_id FK "Optional: references media_entries(id)"
        text platform "royal_road | webnovel | wattpad | amazon | kindle | mangafire | lua_comics | other"
        text display_name "Button label: 'Royal Road', 'Amazon Kindle', etc."
        text url "Direct reading or storefront URL"
        text icon_key "Icon identifier for logo rendering: 'royal_road', 'amazon', etc."
        boolean is_primary "Primary link for one-click navigation"
        timestamptz created_at "Audit timestamp"
    }

    ALTERNATE_TITLES {
        bigint id PK "Auto-incrementing identity"
        uuid series_id FK "References series(id) ON DELETE CASCADE"
        uuid entry_id FK "Optional: references media_entries(id)"
        text title "e.g. 'Sousou no Frieren', '葬送のフリーレン'"
        text clean_title "Generated lowercase alphanumeric"
        text language "en | ja | ko | romaji | native"
        boolean is_primary "Primary display title flag"
        timestamptz created_at "Audit timestamp"
    }

    AUTHORS {
        uuid id PK "gen_random_uuid()"
        text name "Display name (e.g. 'Chugong')"
        text native_name "Native name (e.g. '추공')"
        text image_url "Avatar or photo URL"
        text bio "Biography or notes"
        timestamptz created_at "Audit timestamp"
        timestamptz updated_at "Audit timestamp"
    }

    SERIES_AUTHORS {
        uuid series_id PK,FK "References series(id)"
        uuid author_id PK,FK "References authors(id)"
        text role "author | illustrator | studio | director"
    }

    MEDIA_RELATIONS {
        bigint id PK "Auto-incrementing identity"
        uuid source_series_id FK "References series(id)"
        uuid target_series_id FK "References series(id)"
        relation_type relation_type "adaptation | source_material | sequel | prequel | spin_off"[cite: 8]
        text notes "e.g. 'Anime covers Webnovel Books 1 through 3'"
        timestamptz created_at "Audit timestamp"
    }

    PROGRESS_HISTORY {
        bigint id PK "Auto-incrementing identity"
        uuid series_id FK "References series(id) ON DELETE CASCADE"
        uuid entry_id FK "Optional: references media_entries(id)"
        numeric previous_unit "Prior progress inside entry"
        numeric new_unit "New progress inside entry"
        numeric previous_cumulative_unit "Prior series-wide total"
        numeric new_cumulative_unit "New series-wide total"
        text source "android_notification | moon_reader_sync | extension"
        timestamptz logged_at "Event timestamp"
    }
```

### Table Definitions in Code:

- [`series`](file:///c:/Users/benjv/Dev/OmniTrack/database/src/schema/series.ts)
- [`media_entries`](file:///c:/Users/benjv/Dev/OmniTrack/database/src/schema/entries.ts)
- [`external_links`](file:///c:/Users/benjv/Dev/OmniTrack/database/src/schema/links.ts)
- [`alternate_titles`](file:///c:/Users/benjv/Dev/OmniTrack/database/src/schema/titles.ts)
- [`authors`](file:///c:/Users/benjv/Dev/OmniTrack/database/src/schema/authors.ts)
- [`media_relations`](file:///c:/Users/benjv/Dev/OmniTrack/database/src/schema/relations_model.ts)
- [`progress_history`](file:///c:/Users/benjv/Dev/OmniTrack/database/src/schema/history.ts)
- [`enums`](file:///c:/Users/benjv/Dev/OmniTrack/database/src/schema/enums.ts)
- [`helpers`](file:///c:/Users/benjv/Dev/OmniTrack/database/src/schema/helpers.ts)
