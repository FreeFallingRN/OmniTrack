import { pgTable, uuid, text, bigint, index } from "drizzle-orm/pg-core";
import { series } from "./series";
import { relationTypeEnum } from "./enums";
import { timestampNow } from "./helpers";

/**
 * Media Relations Table (Adaptations & Cross-Media Connections)
 * Maps connections between series (e.g. Manga adapted from Webnovel, Anime adapted from Manga).
 */
export const mediaRelations = pgTable(
  "media_relations",
  {
    id: bigint("id", { mode: "number" })
      .primaryKey()
      .generatedAlwaysAsIdentity(),

    sourceSeriesId: uuid("source_series_id")
      .notNull()
      .references(() => series.id, { onDelete: "cascade" }),

    targetSeriesId: uuid("target_series_id")
      .notNull()
      .references(() => series.id, { onDelete: "cascade" }),

    relationType: relationTypeEnum("relation_type").notNull(),
    notes: text("notes"), // e.g. "Anime covers Webnovel Books 1 through 3"

    createdAt: timestampNow("created_at"),
  },
  (table) => [
    index("idx_media_relations_source").on(table.sourceSeriesId),
    index("idx_media_relations_target").on(table.targetSeriesId),
  ]
);

export type MediaRelation = typeof mediaRelations.$inferSelect;
export type NewMediaRelation = typeof mediaRelations.$inferInsert;
