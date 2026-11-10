/**
 * Types and interfaces for the Moon+ Reader Google Apps Script Sync Worker
 */

export interface ParsedPoFile {
  fileName: string;
  rawTitle: string;
  cleanTitle: string;
  chapterIndex: number;
  lastUpdated: Date;
  volumeIndex?: number;
  characterOffset?: number;
  percentage?: number;
}

export interface MatchedSeries {
  seriesId: string;
  seriesTitle: string;
  currentCumulativeUnit: number;
  entryId?: string;
  entryTitle?: string;
  entryCurrentUnit?: number;
  startCumulativeUnit?: number;
}

export interface SyncResult {
  file: string;
  status: "updated" | "skipped_no_change" | "not_found" | "error";
  details: string;
}

export interface ScriptConfig {
  supabaseUrl: string;
  supabaseAnonKey: string;
  targetDriveFolder: string;
  lastSyncTimestamp: number;
}
