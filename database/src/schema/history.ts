import { pgTable, uuid, text, bigint, date, index, uniqueIndex } from "drizzle-orm/pg-core";
import { mediaEntries } from "./entries";
import { unitNumber, timestampNow } from "./helpers";

/**
 * Progress History Log Table
 * Consolidates progress changes for each media entry and local calendar day.
 */
export const progressHistory = pgTable(
  "progress_history",
  {
    id: bigint("id", { mode: "number" })
      .primaryKey()
      .generatedAlwaysAsIdentity(),

    entryId: uuid("entry_id")
      .notNull()
      .references(() => mediaEntries.id, {
      onDelete: "cascade",
    }),

    activityDate: date("activity_date", { mode: "string" }).notNull(),
    previousUnit: unitNumber("previous_unit"),
    newUnit: unitNumber("new_unit"),

    source: text("source").notNull(), // 'android_notification', 'moon_reader_sync', 'extension'
    loggedAt: timestampNow("logged_at"),
  },
  (table) => [
    index("idx_progress_history_entry_id").on(table.entryId),
    uniqueIndex("uq_progress_history_entry_day").on(table.entryId, table.activityDate),
    index("idx_progress_history_logged_at").on(table.loggedAt),
  ]
);

export type ProgressHistory = typeof progressHistory.$inferSelect;
export type NewProgressHistory = typeof progressHistory.$inferInsert;
