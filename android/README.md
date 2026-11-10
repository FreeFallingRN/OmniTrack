# OmniTrack Android Application

Native Android client built with Kotlin, Jetpack Compose, Room SQLite cache, and WorkManager background sync.

## Specifications
- **Architecture**: MVVM with Offline-First Repository Pattern
- **Instant Actions**: Low-latency persistent notification with `[+1]`, `[-1]`, and `[Switch]` quick actions.
- **Share Target**: Intercepts `ACTION_SEND` (webnovel links) with an OpenGraph metadata parser sheet.
- **Sync**: Supabase REST / WebSocket realtime integration with local Room caching.
