# OmniTrack Android Application

Native Android client built with Kotlin, Jetpack Compose, Room SQLite cache, and WorkManager background sync.

## Specifications

- **Architecture**: MVVM with Offline-First Repository Pattern
- **Instant Actions**: Low-latency persistent notification with `[+1]`, `[-1]`, and `[Switch]` quick actions.
- **Share Target**: Intercepts `ACTION_SEND` (webnovel links) with an OpenGraph metadata parser sheet.
- **Discord Sharing**: Customize fields and preview Markdown from an entry's share button. The share style is selected in Settings; covers are attached when available, with text-only sharing if loading fails.
- **Sync**: Supabase REST integration with local Room caching and WorkManager background sync.

`MainActivity` wires the library ViewModel, separate MangaBaka and Moon+ Reader sync owners, settings state holder, scrape-report repository, and media lookup services. Feature owners schedule their existing background work; the library ViewModel owns library and progress state.

Entry aliases are edited separately from shared series aliases. After upgrading, refresh the library and reopen a series entry before editing if its cached aliases have not yet been separated. If creating an entry only partially succeeds, check the refreshed library and complete its details before retrying; refresh manually if reconciliation fails.

## Moon+ Reader progress

In Settings → Moon+ Reader, select a progress backup/sync folder for `.po` files and use **Link books** to link matching chapter entries. The separate EPUB storage folder is used to find local `.epub` files. Adjust the chapter before applying to calibrate future checks; linked books can be unlinked on the same screen. Unlinking clears their saved link and calibration without changing reading progress or history. Checks run when the app resumes (at most once per minute), or use **Sync now**. Linked books are also checked periodically in the background, no more often than every 15 minutes and when network connectivity is available; Android controls the actual timing. The saved folders must retain read permission. Subfolders, including hidden ones, are scanned. Automatic checks read only linked `.po` files and skip files with an unchanged available modification time. Links, positions and calibrations are stored locally on the device. Matches use the book filename and entry titles; ambiguous titles are skipped. The link dialog adds one to Moon+'s zero-based spine chapter index and applies a per-entry offset, initially calibrated to current progress, including zero. Adjust the chapter before applying if needed. Each spine item, including interludes and unnumbered chapters, counts as a sequential step. Automatic sync only advances progress for a newer position and preserves entry status. A comic linked to MangaBaka is controlled by MangaBaka rather than Moon+ Reader; novels can use Moon+ Reader even when linked to MangaBaka. Unlink the active progress source before manually decreasing progress. Sync reports skipped links and read errors; background runs report partial outcomes instead of success. Synced changes use the import history source and pending Supabase writes are queued for retry.

## MangaBaka library

Use Settings → MangaBaka to connect with OAuth or a personal access token and fetch your library. Importing a MangaBaka series URL as a new chapter-based novel or comic links it to your library, adding it to MangaBaka if needed. If linking fails, the OmniTrack entry is still saved and a message is shown. Search and filter titles, add one as a new entry, or link it to a matching chapter-based entry with an optional offset. Links and offsets are stored on this device; linking itself does not change progress. MangaBaka syncs progress for comics only; novel links do not lock or update progress. Comic sync adds the offset to MangaBaka's chapter and only advances progress, using the import history source. A known total marks an entry completed when reached for a completed or cancelled series. Manual progress in the detail sheet is locked for linked comics; unlink before manually decreasing progress. When relinking after a reset, choose an offset that reflects the desired progress. Sync reports skipped links and errors, and background partial runs do not report success. Use **Sync now** or resume the app; automatic checks are debounced to once per minute. Connected accounts with linked entries are also checked in the background at least 15 minutes apart when network connectivity is available; Android controls the timing. Comic progress is saved locally after Supabase accepts the update. New entries are saved to Supabase. Sign in again when authorization expires. Data and covers are provided by [MangaBaka](https://mangabaka.org/) and its [source providers](https://mangabaka.org/developers/concepts/data-and-sources).

MangaBaka series URLs are imported through its public API. Existing library status and comic progress prefill the editor; new MangaBaka library entries are added when the OmniTrack entry is saved. The chapter total is displayed in the editor; it and the description are not currently saved on media entries.

## Scrape issue reports

Use the flag in a URL-imported draft to save a report, then open Settings → Scrape reports to inspect, copy, or delete it. Reports are stored locally first and uploaded when a network is available; deletions also sync. Run the database migrations before using sync. Reports include the original and normalized URLs, scraped draft, tags, and note: avoid including sensitive information. Like the existing personal-backend tables, reports can be read and modified by anyone with the Supabase anon key.
