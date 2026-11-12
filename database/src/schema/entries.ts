import { pgTable, uuid, text, boolean, numeric, index } from "drizzle-orm/pg-core";
import { sql } from "drizzle-orm";
import { series } from "./series";
import { platforms } from "./platforms";
import { mediaTypeEnum, userStatusEnum, publishingStatusEnum } from "./enums";
import { pkUuid, unitNumber, optionalUnitNumber, timestamps } from "./helpers";

/**
 * Media Entries Table (Books, Seasons, Volumes, and Parts)
 * Represents individual trackable releases under a Series.
 * - Continuous webnovel: Single entry ("Main Story")
 * - Multi-book novel: Multiple entries ("Book 1", "Book 2"), with individual chapter counters
 * - Anime: "Season 1", "Season 2 Part 2", "Final Season"
 */
export const mediaEntries = pgTable(
  "media_entries",
  {
    id: pkUuid(),
    seriesId: uuid("series_id")
      .notNull()
      .references(() => series.id, { onDelete: "cascade" }),

    title: text("title").notNull(), // e.g. "Book 1: Unsouled", "Season 2 Part 2"
    type: mediaTypeEnum("type").notNull().default("novel"),
    sequenceNumber: numeric("sequence_number", { precision: 5, scale: 2 })
      .notNull()
      .default("1.00"),

    publishingStatus: publishingStatusEnum("publishing_status")
      .notNull()
      .default("releasing"),
    userStatus: userStatusEnum("user_status").notNull().default("planning"),
    isCanon: boolean("is_canon").notNull().default(true),

    // Progress within THIS specific book/season (resets per book)
    currentUnit: unitNumber("current_unit").default("0.00"),

    unitName: text("unit_name").notNull().default("Chapter"),

    // Source Metadata
    sourceUrl: text("source_url"),
    platformId: uuid("platform_id").references(() => platforms.id, { onDelete: "set null" }),
    coverUrl: text("cover_url"),

    ...timestamps,
  },
  (table) => [
    index("idx_media_entries_series_id").on(table.seriesId),
    index("idx_media_entries_user_status").on(table.userStatus),
  ]
);

export type MediaEntry = typeof mediaEntries.$inferSelect;
export type NewMediaEntry = typeof mediaEntries.$inferInsert;