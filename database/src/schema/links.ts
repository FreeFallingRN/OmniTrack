import { pgTable, uuid, text, boolean, bigint, index } from "drizzle-orm/pg-core";
import { series } from "./series";
import { mediaEntries } from "./entries";
import { timestampNow } from "./helpers";

/**
 * External Links & Platform Store URLs
 * Stores reading locations, storefronts, and source URLs with icon keys for logo rendering.
 * Supported presets: Royal Road, Webnovel, Wattpad, Amazon, Kindle, Mangafire, Lua Comics, etc.
 */
export const externalLinks = pgTable(
  "external_links",
  {
    id: bigint("id", { mode: "number" })
      .primaryKey()
      .generatedAlwaysAsIdentity(),

    seriesId: uuid("series_id")
      .notNull()
      .references(() => series.id, { onDelete: "cascade" }),

    entryId: uuid("entry_id").references(() => mediaEntries.id, {
      onDelete: "cascade",
    }),

    // Platform key (e.g. 'royal_road', 'webnovel', 'wattpad', 'amazon', 'kindle', 'mangafire', 'lua_comics', 'other')
    platform: text("platform").notNull(),

    // Display label shown on UI button (e.g. "Royal Road", "Amazon Kindle")
    displayName: text("display_name").notNull(),

    // Target URL
    url: text("url").notNull(),

    // Icon identifier to render branded platform logos in Android & Web UI
    iconKey: text("icon_key").notNull().default("generic"),

    // Primary flag for one-click quick jump / resume
    isPrimary: boolean("is_primary").default(false),

    createdAt: timestampNow("created_at"),
  },
  (table) => [
    index("idx_external_links_series_id").on(table.seriesId),
    index("idx_external_links_entry_id").on(table.entryId),
    index("idx_external_links_platform").on(table.platform),
  ]
);

export type ExternalLink = typeof externalLinks.$inferSelect;
export type NewExternalLink = typeof externalLinks.$inferInsert;
