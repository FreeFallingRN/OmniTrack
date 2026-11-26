# OmniTrack

OmniTrack is a personal library for keeping track of what you read and watch. Save comics, manga and manhwa, novels and webnovels, anime, and movies in one place, and pick up where you left off across multiple entries in the same series.

## What you can do

- **Home and Library:** Browse and filter your collection, update chapter, episode, or volume progress, and open an entry for its details. Keep parallel tracks in a series, such as a novel and its comic adaptation, without combining their progress. Rescrape an entry to review refreshed details before applying them.
- **Add media:** Search MangaBaka or AniList, import details from a web link, or create an entry yourself.
- **Manage your library:** Organize series, authors and other creators, and platforms or sources.
- **Stats:** Review reading and watching activity, streaks, and breakdowns of your library.
- **Settings and integrations:** Connect a MangaBaka account to import, match, and sync eligible comic progress; link Moon+ Reader `.po` files for automatic novel progress updates; configure Supabase cloud sync; check for app updates; and report scraper issues.

## Getting started

OmniTrack's main app is for Android. To build and run it:

1. Install Android Studio with JDK 17 and the Android SDK required by the project.
2. Configure the app's Supabase project URL and anon key in `android/local.properties`:

   ```properties
   SUPABASE_URL=https://your-project.supabase.co
   SUPABASE_ANON_KEY=your-supabase-anon-key
   ```

   Alternatively, set those two values as environment variables before building. Do not commit credentials. The Supabase project must have the database schema and migrations from `database/` applied.
3. Open the `android/` folder in Android Studio, sync Gradle, and run the `app` configuration. Or, from PowerShell:

   ```powershell
   cd android
   .\gradlew.bat :app:installDebug
   ```

Connect MangaBaka in **Settings > MangaBaka** using its sign-in flow or a personal access token. In **Settings > Moon+ Reader**, choose the `.po` progress folder and link books to entries. Android may delay background sync according to its battery and network policies.

## Project layout

- `android/` - Android application (Kotlin and Jetpack Compose).
- `database/` - Supabase database schema and migrations.
- `sync-worker/` - Google Apps Script worker for Moon+ Reader files in Google Drive.
- `extension/` - Planned browser extension; it is not implemented.
