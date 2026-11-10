import { getModifiedPoFiles } from "./drive.js";
import { SupabaseClient } from "./supabase.js";
import { ScriptConfig, SyncResult } from "./types.js";

/**
 * Loads configuration from Google Apps Script Script Properties.
 */
function getConfig(): ScriptConfig {
  const props = PropertiesService.getScriptProperties();

  const supabaseUrl = props.getProperty("SUPABASE_URL") || "";
  const supabaseAnonKey = props.getProperty("SUPABASE_ANON_KEY") || "";
  const targetDriveFolder = props.getProperty("DRIVE_FOLDER_PATH") || "Apps/Books/.Moon+/Cache";
  const lastSyncStr = props.getProperty("LAST_SYNC_TIMESTAMP");
  const lastSyncTimestamp = lastSyncStr ? parseInt(lastSyncStr, 10) : 0;

  if (!supabaseUrl || !supabaseAnonKey) {
    throw new Error(
      "[Configuration Error] SUPABASE_URL and SUPABASE_ANON_KEY must be set in Script Properties."
    );
  }

  return {
    supabaseUrl,
    supabaseAnonKey,
    targetDriveFolder,
    lastSyncTimestamp,
  };
}

/**
 * Main execution function triggered on a time-driven cron schedule (e.g. every 10-15 minutes).
 */
export function syncMoonReader(): SyncResult[] {
  console.log("=== Starting Moon+ Reader Sync Worker ===");
  const startTime = Date.now();
  const config = getConfig();

  const supabase = new SupabaseClient(config.supabaseUrl, config.supabaseAnonKey);
  const modifiedFiles = getModifiedPoFiles(config.targetDriveFolder, config.lastSyncTimestamp);

  const results: SyncResult[] = [];

  for (const file of modifiedFiles) {
    try {
      console.log(`[Sync] Processing file: ${file.fileName} (Parsed Chapter: ${file.chapterIndex})`);

      const matched = supabase.findSeriesOrEntry(file.cleanTitle);
      if (!matched) {
        console.warn(`[Sync] No database match found for clean_title '${file.cleanTitle}' (${file.rawTitle})`);
        results.push({
          file: file.fileName,
          status: "not_found",
          details: `clean_title '${file.cleanTitle}' does not match any series or alternate title.`,
        });
        continue;
      }

      // Check current progress
      const currentUnit = matched.entryId ? (matched.entryCurrentUnit || 0) : matched.currentCumulativeUnit;

      if (file.chapterIndex > currentUnit) {
        supabase.updateProgress(matched, file.chapterIndex);
        results.push({
          file: file.fileName,
          status: "updated",
          details: `Updated '${matched.seriesTitle}' from ch. ${currentUnit} -> ch. ${file.chapterIndex}`,
        });
      } else {
        console.log(`[Sync] Skipped '${matched.seriesTitle}' - file chapter ${file.chapterIndex} <= current unit ${currentUnit}`);
        results.push({
          file: file.fileName,
          status: "skipped_no_change",
          details: `Chapter ${file.chapterIndex} is not greater than existing progress ${currentUnit}.`,
        });
      }
    } catch (err: any) {
      console.error(`[Sync] Error processing ${file.fileName}:`, err);
      results.push({
        file: file.fileName,
        status: "error",
        details: err.message || String(err),
      });
    }
  }

  // Update last sync execution timestamp
  PropertiesService.getScriptProperties().setProperty("LAST_SYNC_TIMESTAMP", startTime.toString());
  console.log(`=== Sync Completed. Processed ${results.length} item(s). ===`);
  return results;
}

/**
 * Helper to programmatically install the 10-minute time-driven trigger.
 * Run this function once from the Google Apps Script editor.
 */
export function setupCronTrigger(): void {
  // Clear any existing triggers for syncMoonReader
  const triggers = ScriptApp.getProjectTriggers();
  for (const trigger of triggers) {
    if (trigger.getHandlerFunction() === "syncMoonReader") {
      ScriptApp.deleteTrigger(trigger);
    }
  }

  // Create new time-driven trigger every 10 minutes
  ScriptApp.newTrigger("syncMoonReader")
    .timeBased()
    .everyMinutes(10)
    .create();

  console.log("✅ Time-driven trigger successfully set to run syncMoonReader every 10 minutes.");
}
