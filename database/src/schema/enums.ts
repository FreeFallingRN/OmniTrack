import { pgEnum } from "drizzle-orm/pg-core";

/**
 * High-level media categories
 * - novel: Webnovels (continuous or multi-book), light novels, published fiction
 * - comic: Manga, manhwa, manhua, western comics, webtoons
 * - anime: Anime seasons, animated shows, live-action series
 * - movie: Standalone films or film franchises
 */
export const mediaTypeEnum = pgEnum("media_type", [
  "novel",
  "comic",
  "anime",
  "movie",
]);
/**
 * User personal tracking status
 */
export const userStatusEnum = pgEnum("user_status", [
  "planning",    // Planning to read / watch
  "in_progress", // Currently reading / watching
  "completed",   // Finished
  "paused",      // On hold / paused
  "dropped",     // Dropped by user
]);

/**
 * Industry publication / release status of the work
 */
export const publishingStatusEnum = pgEnum("publishing_status", [
  "announced",    // Announced / upcoming
  "releasing",    // Currently publishing / airing
  "hiatus",       // On hiatus
  "completed",    // Fully published / aired
  "discontinued", // Cancelled or dropped by publisher/author
]);
