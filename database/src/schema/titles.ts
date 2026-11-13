import { pgTable, uuid, text, boolean, bigint, index } from "drizzle-orm/pg-core";
import { sql } from "drizzle-orm";
import { series } from "./series";
import { mediaEntries } from "./entries";
import { timestampNow } from "./helpers";

/**
 * Alternate Titles Table
 * Stores alternate, romanized, and translated titles for series and entries
 * (e.g. "Sousou no Frieren", "Frieren: Beyond Journey's End", "葬送のフリーレン").
 */
export const alternateTitles = pgTable(
  "alternate_titles",
  {
    id: bigint("id", { mode: "number" })
      .primaryKey()
      .generatedAlwaysAsIdentity(),

    seriesId: uuid("series_id")
      .references(() => series.id, { onDelete: "cascade" }),

    entryId: uuid("entry_id").references(() => mediaEntries.id, {
      onDelete: "cascade",
    }),

    title: text("title").notNull(),

    // Clean alphanumeric title for Moon+ Reader & extension scraper lookups
    cleanTitle: text("clean_title").generatedAlwaysAs(
      sql`lower(regexp_replace(title, '[^a-zA-Z0-9]', '', 'g'))`
    ),

    language: text("language"), // 'en', 'ja', 'ko', 'romaji', 'native', etc.
    isPrimary: boolean("is_primary").default(false),

    createdAt: timestampNow("created_at"),
  },
  (table) => [
    index("idx_alternate_titles_series_id").on(table.seriesId),
    index("idx_alternate_titles_entry_id").on(table.entryId),
    index("idx_alternate_titles_clean_title").on(table.cleanTitle),
  ]
);

export type AlternateTitle = typeof alternateTitles.$inferSelect;
export type NewAlternateTitle = typeof alternateTitles.$inferInsert;
