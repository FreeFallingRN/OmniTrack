# OmniTrack Sync Worker

Google Apps Script (GAS) worker that parses Moon+ Reader `.po` position cache files from Google Drive and updates your Supabase catalog.

## Specifications
- **Target Drive Path**: `Apps/Books/.Moon+/Cache/`
- **File Matching**: `*.epub.po`
- **Format**: `\text{timestamp} * \text{chapter_index} @ \text{volume_index} # \text{character_offset} : \text{percentage}%`
- **Regex**: `/\*(\d+)@/` extracts current chapter.
- **Trigger**: Time-driven execution every 5–15 minutes.
