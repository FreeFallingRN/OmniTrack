import { ParsedPoFile } from "./types.js";

/**
 * Normalizes title into an alphanumeric lowercase string matching PostgreSQL clean_title column.
 */
export function cleanTitle(title: string): string {
  return title.toLowerCase().replace(/[^a-z0-9]/g, "");
}

/**
 * Extracts raw title from Moon+ Reader position cache filename.
 * Example: "Shadow Slave.epub.po" -> "Shadow Slave"
 * Example: "Cradle 02 - Soulsmith.epub.po" -> "Cradle 02 - Soulsmith"
 */
export function extractTitleFromFilename(fileName: string): string {
  return fileName.replace(/\.epub\.po$/i, "").trim();
}

/**
 * Parses Moon+ Reader .po ASCII content.
 * Format: timestamp * chapter_index @ volume_index # character_offset : percentage%
 * Regex: /\*(\d+)@/ extracts the active chapter index.
 */
export function parsePoContent(content: string): { chapterIndex: number; volumeIndex?: number; offset?: number } | null {
  if (!content) return null;

  // Regex matching chapter index between '*' and '@'
  const match = content.match(/\*(\d+)@/);
  if (!match) return null;

  const chapterIndex = parseInt(match[1], 10);
  if (isNaN(chapterIndex)) return null;

  // Optional volume extraction
  const volMatch = content.match(/@(\d+)#/);
  const volumeIndex = volMatch ? parseInt(volMatch[1], 10) : undefined;

  // Optional character offset
  const offsetMatch = content.match(/#(\d+):/);
  const offset = offsetMatch ? parseInt(offsetMatch[1], 10) : undefined;

  return { chapterIndex, volumeIndex, offset };
}

/**
 * Traverses Google Drive folders recursively by path string (e.g. "Apps/Books/.Moon+/Cache").
 */
function getFolderByPath(path: string): GoogleAppsScript.Drive.Folder | null {
  const parts = path.split("/").filter((p) => p.trim().length > 0);
  let current: GoogleAppsScript.Drive.Folder = DriveApp.getRootFolder();

  for (const part of parts) {
    const folders = current.getFoldersByName(part);
    if (!folders.hasNext()) {
      return null;
    }
    current = folders.next();
  }
  return current;
}

/**
 * Scans Google Drive for Moon+ Reader .po files updated after sinceTimestampMs.
 */
export function getModifiedPoFiles(folderPath: string, sinceTimestampMs: number): ParsedPoFile[] {
  const results: ParsedPoFile[] = [];
  const targetFolder = getFolderByPath(folderPath);

  let fileIterator: GoogleAppsScript.Drive.FileIterator;

  if (targetFolder) {
    console.log(`[Drive] Scanning folder: ${folderPath}`);
    fileIterator = targetFolder.getFiles();
  } else {
    console.warn(`[Drive] Specific path '${folderPath}' not found, falling back to global .epub.po search...`);
    fileIterator = DriveApp.searchFiles("title contains '.epub.po' and trashed = false");
  }

  while (fileIterator.hasNext()) {
    const file = fileIterator.next();
    const fileName = file.getName();

    if (!fileName.toLowerCase().endsWith(".epub.po")) {
      continue;
    }

    const lastUpdated = file.getLastUpdated();
    const lastUpdatedMs = lastUpdated.getTime();

    // Only process files modified strictly after the previous sync timestamp
    if (lastUpdatedMs <= sinceTimestampMs) {
      continue;
    }

    try {
      const content = file.getBlob().getDataAsString();
      const parsed = parsePoContent(content);

      if (parsed) {
        const rawTitle = extractTitleFromFilename(fileName);
        results.push({
          fileName,
          rawTitle,
          cleanTitle: cleanTitle(rawTitle),
          chapterIndex: parsed.chapterIndex,
          volumeIndex: parsed.volumeIndex,
          characterOffset: parsed.offset,
          lastUpdated,
        });
      }
    } catch (err) {
      console.error(`[Drive] Failed reading file ${fileName}:`, err);
    }
  }

  console.log(`[Drive] Found ${results.length} modified .po file(s) since ${new Date(sinceTimestampMs).toISOString()}`);
  return results;
}

