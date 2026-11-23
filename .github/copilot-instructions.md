# Copilot instructions

These notes describe verified Android app code and repository conventions for OmniTrack. Keep changes aligned with the contracts below and verify symbols in source before editing.

## Project overview and stack

- Android app module: `android/app`, package/namespace `com.omnitrack.app`; Kotlin with JVM 17.
- UI: Jetpack Compose with Material 3 (`OmniTrackTheme`, dark color scheme).
- Persistence: Room SQLite cache (`AppDatabase`, `MediaEntryDao`, `ProgressHistoryDao`); remote sync uses Supabase REST through Ktor and `kotlinx.serialization` (not WebSocket realtime).
- Architecture: MVVM with an offline-first repository (`SeriesRepository` / `SeriesRepositoryImpl`). `MainActivity` manually wires the Room database, repository, and `SeriesViewModel` via a `ViewModelProvider.Factory`; no DI framework is used.
- Key source directories:
  - `android/app/src/main/java/com/omnitrack/app/domain/model/` — domain models and enums.
  - `.../domain/repository/` — repository interface.
  - `.../data/local/` — Room database, entities, and DAOs.
  - `.../data/repository/` — repository implementation and entity mappers.
  - `.../data/` — Supabase service; `.../data/model/` — network DTOs.
  - `.../ui/` — `MainActivity`, `viewmodel/` (`SeriesViewModel`), `components/`, `screens/` (including split screen sections/calculations/editors), and `theme/`.
  - `.../utils/`, `.../worker/` — utilities and worker.

## Domain and data contracts

- `MediaEntry` fields: `id: String`; `seriesId`, `seriesTitle: String?`; `title: String`; `type: MediaType`; `sequenceNumber: Double?`; `userStatus: UserStatus`; `publishingStatus: String`; `isCanon: Boolean`; `authors: List<String>`; `creators: List<EntryCreator>`; `alternativeTitles: List<String>`; `coverUrl`, `sourceUrl`, `platformId`, `platform: String?`; `currentUnit: Double`; `unitName: String`; `createdAt`, `updatedAt: String`.
- `EntryCreator`: `id: String?`, `name: String`, `role: String` (default `"author"`).
- `ProgressHistoryItem`: `entryId`, `activityDate`, `source`, `entryTitle`, `unitName: String`; `previousUnit`, `newUnit: Double`; `mediaType: MediaType`; `coverUrl: String?`. Helpers: `toEntryLocalDate(): LocalDate?`, `delta: Double`.
- `HistoryTimeframe` values/labels: `DAYS_7/"Week"`, `DAYS_30/"Month"`, `YEAR/"Year"`, `ALL_TIME/"All Time"`. Use `HistoryTimeframe` consistently rather than mixing raw string keys and enum labels.
- `ProgressSource` values: `DETAIL_SHEET/"detail_sheet"`, `INCREMENT/"increment"`, `EDITOR/"editor"`, `IMPORT/"import"`. Exposes `value`, `isActiveConsumption`, and `fromValue(value: String): ProgressSource`.
- `MediaType` values: `NOVEL`, `COMIC`, `ANIME`, `MOVIE`; `UserStatus`: `PLANNING`, `IN_PROGRESS`, `COMPLETED`, `PAUSED`, `DROPPED`. Both expose `value`, `label`, and `fromValue(value: String)`.
- Other progress models: `DailyProgressStats(date: String, totalUnitsDelta: Double, entriesCount: Int)`, `MediaTypeStats(type: MediaType, totalUnits: Double, entriesCount: Int)`, `OverallProgressStats(totalUnitsTracked: Double, activeDaysCount: Int, currentStreakDays: Int, mediaTypeBreakdown: List<MediaTypeStats>)`.
- `SeriesRepository` contracts:
  - `getMediaEntriesStream(): Flow<List<MediaEntry>>`; `fetchFromSupabase(): suspend Unit`.
  - `updateProgress(id: String, newProgress: Double, source: ProgressSource = DETAIL_SHEET): suspend Unit`.
  - `incrementProgress(id: String, delta: Double, source: ProgressSource = INCREMENT): suspend Unit`; `deleteMediaEntry(id: String): suspend Unit`.
  - `getRecentActivityFeed(): Flow<List<ProgressHistoryItem>>`; `getProgressStats(): Flow<OverallProgressStats>`.
- `MediaEntryDao`: `getAllEntries(): Flow<List<MediaEntryEntity>>`; `getEntriesForSeries(seriesId: String): suspend List<MediaEntryEntity>`; `getEntryById(id: String): suspend MediaEntryEntity?`; `deleteAll(): suspend Unit`; `updateProgress(id: String, newProgress: Double): suspend Unit`; `updateProgressAndStatus(id: String, newProgress: Double, newStatus: String): suspend Unit`; `insertAll(entries: List<MediaEntryEntity>): suspend Unit`; `replaceAll(entries: List<MediaEntryEntity>): suspend Unit`; `deleteEntryById(id: String): suspend Unit`.
- `ProgressHistoryDao`: `getEntryHistoryForDate(entryId: String, activityDate: String): suspend ProgressHistoryEntity?`; `upsert(history: ProgressHistoryEntity): suspend Unit`; `insertAll(histories: List<ProgressHistoryEntity>): suspend Unit`; `deleteAll(): suspend Unit`; `replaceAll(histories: List<ProgressHistoryEntity>): suspend Unit`; `getRecentActivityFeed(): Flow<List<ProgressHistoryWithMedia>>`; `getActivityFeedSince(startDate: String): suspend List<ProgressHistoryWithMedia>`; `getAllHistory(): Flow<List<ProgressHistoryEntity>>`; `deleteHistoryForEntry(entryId: String): suspend Unit`.

## State and Compose conventions

- `SeriesViewModel` (in `ui.viewmodel`) exposes `mediaEntries`, `recentActivity`, `progressStats`, `isRefreshing`, and combined `state` as `StateFlow`s. Repository flows use `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialValue)`.
- `isRefreshing` tracks active sync work (`fetchFromSupabase()`) and resets in `finally` while preserving `CancellationException`; UI refresh indicators such as `PullToRefreshBox` should follow `isRefreshing` rather than resetting immediately on launch.
- `SeriesViewModelState` contains `mediaEntries`, `recentActivity`, `progressStats`, `selectedPeriodTab`, `periodOffset`, `canGoPrevious`, and `canGoNext`. Public actions include `selectPeriodTab(...)`, `stepPeriod(delta: Int)`, `updateProgress(...)`, `incrementUnitCount(...)`, `deleteMediaEntry(id: String)`, and `refreshData()`.
- Preserve `refreshData()`: `MainActivity`, `OmniTrackHomeScreen`, and `LibraryManagementScreen` call it to trigger repository sync.
- Screens receive `SeriesViewModel` as a parameter and collect flows with `collectAsState()`. Large screens may split distinct UI sections, dialogs/editors, or pure calculation helpers into focused companion files in the same package/feature area.

## Repo preferences & guardrails

- **Keep the repo simple:** Prefer direct code, small focused files, and deleting dead code over introducing extra abstraction layers, wrappers, use-case classes, or new dependencies.
- **Strict scope:** Do not invent model properties, methods, DAO operations, repository methods, or signatures; check declarations before use. Keep edits scoped to requested files and update directly related docs when needed.
- **Builds & tests are manual:** Do not run Gradle builds, emulators, or tests unless explicitly instructed; the developer runs and tests builds directly in Android Studio.
- **Concise output:** Keep responses brief and scoped to the requested task.