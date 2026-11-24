# OmniTrack Android Application

Native Android client built with Kotlin, Jetpack Compose, Room SQLite cache, and WorkManager background sync.

## Specifications
- **Architecture**: MVVM with Offline-First Repository Pattern
- **Instant Actions**: Low-latency persistent notification with `[+1]`, `[-1]`, and `[Switch]` quick actions.
- **Share Target**: Intercepts `ACTION_SEND` (webnovel links) with an OpenGraph metadata parser sheet.
- **Discord Sharing**: Customize fields and preview Markdown from an entry's share button. The share style is selected in Settings; covers are attached when available, with text-only sharing if loading fails.
- **Sync**: Supabase REST / WebSocket realtime integration with local Room caching.

## Moon+ Reader progress
In Settings, choose a Moon+ Reader sync folder or a `.po` file. Apply a chapter match from a `.po` file in the folder to link it for automatic checks when the app resumes (at most once per minute), or use **Sync now**. The saved folder must retain read permission; use **Change folder** to re-grant access. Subfolders, including hidden ones, are scanned. Links, positions and calibrations are stored locally on the device. ZIP-based `.mrpro` and `.mrstd` backups and individually selected files remain manual imports; backups without positions cannot provide progress. Matches use the book filename (or backup path) and entry titles. Ambiguous titles are skipped. For chapter entries, the preview adds one to Moon+'s zero-based spine chapter index and applies a per-entry offset. The initial offset is inferred from current progress; adjusting the chapter and applying saves the calibration locally for later scans, including appended EPUB chapters. Each spine item, including interludes and unnumbered chapters, counts as a sequential step. Automatic sync only advances progress for a newer position; percentage entries still use Moon+'s percentage in the manual preview, and other units require manual input. Imports preserve entry status and use the existing Room/Supabase progress update path. As with other progress updates, a failed Supabase update is logged but not queued for retry; the local and remote values can diverge.

## Scrape issue reports
Use the flag in a URL-imported draft to save a report, then open Settings → Scrape reports to inspect, copy, or delete it. Reports are stored locally first and uploaded when a network is available; deletions also sync. Run the database migrations before using sync. Reports include the original and normalized URLs, scraped draft, tags, and note: avoid including sensitive information. Like the existing personal-backend tables, reports can be read and modified by anyone with the Supabase anon key.
