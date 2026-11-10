import { pgTable, uuid, text, bigint, index } from "drizzle-orm/pg-core";
import { series } from "./series";
import { mediaEntries } from "./entries";
import { unitNumber, optionalUnitNumber, timestampNow } from "./helpers";

/**
 * Progress History Log Table
 * Records every increment/decrement with its origin source (notification, sync worker, extension).
 */
export const progressHistory = pgTable(
  "progress_history",
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

    // Progress within entry (e.g. Chapter 15 of Book 2)
    previousUnit: unitNumber("previous_unit"),
    newUnit: unitNumber("new_unit"),

    // Cumulative Series Progress (e.g. Chapter 39 of Series)
    previousCumulativeUnit: optionalUnitNumber("previous_cumulative_unit"),
    newCumulativeUnit: optionalUnitNumber("new_cumulative_unit"),

    source: text("source").notNull(), // 'android_notification', 'moon_reader_sync', 'extension'
    loggedAt: timestampNow("logged_at"),
  },
  (table) => [
    index("idx_progress_history_series_id").on(table.seriesId),
    index("idx_progress_history_entry_id").on(table.entryId),
    index("idx_progress_history_logged_at").on(table.loggedAt),
  ]
);

export type ProgressHistory = typeof progressHistory.$inferSelect;
export type NewProgressHistory = typeof progressHistory.$inferInsert;
