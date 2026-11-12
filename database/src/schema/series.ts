import { pgTable, text, smallint, index } from "drizzle-orm/pg-core";
import { sql } from "drizzle-orm";
import { userStatusEnum, publishingStatusEnum } from "./enums";
import { pkUuid, unitNumber, optionalUnitNumber, timestamps } from "./helpers";

/**
 * Series / Overarching Work Table
 * Represents the top-level franchise or work (e.g. "Shadow Slave", "Mushoku Tensei", "Attack on Titan").
 */
export const series = pgTable(
  "series",
  {
    id: pkUuid(),
    title: text("title").notNull(),

    // Alphanumeric lowercase clean title for fuzzy search & scraper matching
    cleanTitle: text("clean_title").generatedAlwaysAs(
      sql`lower(regexp_replace(title, '[^a-zA-Z0-9]', '', 'g'))`
    ),

    publishingStatus: publishingStatusEnum("publishing_status")
      .notNull()
      .default("releasing"),
    userStatus: userStatusEnum("user_status").notNull().default("planning"),

    // Cumulative Series Progress (across all books/seasons)
    currentCumulativeUnit: unitNumber("current_cumulative_unit").default("0.00"),
    totalUnits: optionalUnitNumber("total_units"),
    unitName: text("unit_name").notNull().default("Chapter"),

    // Metadata
    rating: smallint("rating"),
    notes: text("notes"),
    ...timestamps,
  },
  (table) => [
    index("idx_series_user_status").on(table.userStatus),
    index("idx_series_clean_title").on(table.cleanTitle),
  ]
);

export type Series = typeof series.$inferSelect;
export type NewSeries = typeof series.$inferInsert;
