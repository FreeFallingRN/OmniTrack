# Copilot instructions

## Codebase map

The Android app is `android/app` (package `com.omnitrack.app`, Kotlin/JVM 17, Jetpack Compose, Room, WorkManager). Its source is under `android/app/src/main/java/com/omnitrack/app/`:

- `MainActivity.kt`, `OmniTrackApp.kt` - app startup and manual service/ViewModel wiring.
- `ui/` - home, library and entry detail/editor, MangaBaka, Moon+ Reader, scraper reports, search/scraping, settings, sharing, stats, components, theme, and `viewmodel/`.
- `domain/model/`, `domain/repository/` - app-facing models and repository contracts.
- `data/` - Supabase and MangaBaka services, preferences, search providers, DTOs in `data/model/`, Room in `data/local/`, and implementations/mappers in `data/repository/`.
- `feature/` - media lookup and `mangabaka/matching/`.
- `utils/` - URL import, Moon+ Reader import/sync, update checks, and scraper adapters.
- `worker/` - periodic/background sync workers.

Other top-level areas: `database/` holds the TypeScript/Drizzle schema and SQL migrations; `sync-worker/` is the Google Apps Script worker; `extension/` is planned, not implemented. Android build configuration is in `android/build.gradle.kts`, `android/app/build.gradle.kts`, and `android/gradle/`.

## Architecture and invariants

- Keep dependencies moving inward: UI/feature presentation -> ViewModels or state holders -> domain models/repository interfaces -> data implementations, network services, and Room. Composables must not call DAOs, preferences, or HTTP directly. Keep network DTOs out of `domain/`.
- Inspect declarations and call sites before editing; never guess class, method, or field names.
- A series position belongs to `(seriesId, mediaType, sequenceNumber)`. Sequence numbers are nullable and user-supplied; never infer or auto-renumber existing entries when an out-of-order entry is added.
- Prefer English metadata. Import only English alternative titles from upstream sources; do not add untranslated upstream titles as alternatives.
- MangaBaka progress sync is for comics, not novels. A MangaBaka link must not lock novel progress: Moon+ Reader remains authoritative for linked novels.
- Ongoing tracking-service syncs record active consumption history; initial library and file-link imports are non-active.
- Do not silently drop local mutations when a remote write fails or the device is offline: preserve them locally and retain/retry pending sync work. Moon+ Reader provider timeouts must be surfaced or retried without abandoning pending local changes or stopping periodic sync.
- Preserve `CancellationException`; report other failures explicitly. Avoid success-shaped fallbacks and broad silent catches.

## Working rules

- Prefer direct code, small focused files, and deleting dead code over introducing extra abstraction layers, wrappers, use-case classes, or new dependencies.
- Do not invent model properties, methods, DAO operations, repository methods, or signatures; check declarations before use. Keep edits scoped to requested files and update directly related docs when needed.
- Do not run Gradle builds, emulators, or tests unless explicitly instructed; the developer runs and tests builds directly in Android Studio.
- Keep responses brief and scoped to the requested task.
- Do not use Python in this workspace. Do not retry a failed or blocked fetch or repeatedly request permission for the same URL/domain in one turn; use already-retrieved or local evidence instead.
- Record verified scraper fetch and parsing observations here when discovered; site behavior can change.
