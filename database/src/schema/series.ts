import { pgTable, text, smallint, index } from "drizzle-orm/pg-core";
import { sql } from "drizzle-orm";
import { pkUuid, timestamps } from "./helpers";

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

    // Metadata
    rating: smallint("rating"),
    notes: text("notes"),
    ...timestamps,
  },
  (table) => [index("idx_series_clean_title").on(table.cleanTitle)]
);

export type Series = typeof series.$inferSelect;
export type NewSeries = typeof series.$inferInsert;
