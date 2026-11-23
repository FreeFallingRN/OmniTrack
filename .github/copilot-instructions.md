# Copilot instructions

These notes describe verified Android app code. Keep Android changes consistent with the contracts below; verify APIs in source before using them.

## Project overview and stack

- Android app module: `android/app`, package/namespace `com.omnitrack.app`; Kotlin with JVM 17.
- UI: Jetpack Compose with Material 3.
- Persistence: Room SQLite cache (`AppDatabase`, `MediaEntryDao`, `ProgressHistoryDao`); remote data uses Supabase REST through Ktor and kotlinx.serialization.
- Architecture: MVVM with an offline-first repository (`SeriesRepository` / `SeriesRepositoryImpl`). `MainActivity` manually creates the Room database, repository, and `SeriesViewModel` through a `ViewModelProvider.Factory`; no DI framework is used here.
- Key source directories:
  - `android/app/src/main/java/com/omnitrack/app/domain/model/` — domain models and enums.
  - `.../domain/repository/` — repository interface.
  - `.../data/local/` — Room database, entities, and DAOs.
  - `.../data/repository/` — repository implementation and entity mappers.
  - `.../data/` — Supabase service; `.../data/model/` — network DTOs.
  - `.../ui/` — `MainActivity`, `SeriesViewModel`, components, screens, and theme.
  - `.../utils/`, `.../worker/` — utilities and worker.

## Domain and data contracts

- `MediaEntry` fields: `id: String`; `seriesId`, `seriesTitle: String?`; `title: String`; `type: MediaType`; `sequenceNumber: Double?`; `userStatus: UserStatus`; `publishingStatus: String`; `isCanon: Boolean`; `authors: List<String>`; `creators: List<EntryCreator>`; `alternativeTitles: List<String>`; `coverUrl`, `sourceUrl`, `platformId`, `platform: String?`; `currentUnit: Double`; `unitName: String`; `createdAt`, `updatedAt: String`.
- `EntryCreator`: `id: String?`, `name: String`, `role: String` (default `"author"`).
- `ProgressHistoryItem`: `entryId`, `activityDate`, `source`, `entryTitle`, `unitName: String`; `previousUnit`, `newUnit: Double`; `mediaType: MediaType`; `coverUrl: String?`. Helpers: `toEntryLocalDate(): LocalDate?`, `delta: Double`.
- `HistoryTimeframe` values/labels: `DAYS_7/"Week"`, `DAYS_30/"Month"`, `YEAR/"Year"`, `ALL_TIME/"All Time"`.
- `ProgressSource` values: `DETAIL_SHEET/"detail_sheet"`, `INCREMENT/"increment"`, `EDITOR/"editor"`, `IMPORT/"import"`. It exposes `value`, `isActiveConsumption`, and `fromValue(value: String): ProgressSource`.
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

- `SeriesViewModel` exposes `mediaEntries`, `recentActivity`, `progressStats`, `isRefreshing`, and combined `state` as `StateFlow`s. Repository flows are converted with `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialValue)`.
- `SeriesViewModelState` contains `mediaEntries`, `recentActivity`, `progressStats`, `selectedPeriodTab`, `periodOffset`, `canGoPrevious`, and `canGoNext`. Public actions include `selectPeriodTab(tab: String)`, `stepPeriod(delta: Int)`, `updateProgress(...)`, `incrementUnitCount(...)`, `deleteMediaEntry(id: String)`, and `refreshData()`.
- Preserve `refreshData()`: `MainActivity`, `OmniTrackHomeScreen`, and `LibraryManagementScreen` call it to launch a repository refresh.
- Screens receive `SeriesViewModel` as a parameter and collect its flows with `collectAsState()` (not `collectAsStateWithLifecycle()` in the inspected screens). `HistoryStatsScreen` collects combined `state`; home and library management collect `mediaEntries`.
- Screens use `@Composable` functions, `MaterialTheme.colorScheme` / `.typography`, Material 3 components (`Scaffold`, `TabRow`, `Card`, `ModalBottomSheet`, dialogs), Compose `remember` state, and effects such as `LaunchedEffect`. Theme entry point is `OmniTrackTheme`, with a dark color scheme.

## Guardrails

- Do not invent model properties, methods, DAO operations, repository methods, or signatures; check their declarations before use.
- Keep existing public APIs used by screens, especially `SeriesViewModel.refreshData()`.
- Do not run Gradle builds, emulators, or tests unless explicitly instructed.
- Keep changes scoped to the requested Android behavior and update directly related documentation when needed.
