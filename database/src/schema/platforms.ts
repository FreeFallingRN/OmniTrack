import { pgTable, text, uniqueIndex } from "drizzle-orm/pg-core";
import { pkUuid, timestamps } from "./helpers";

export const platforms = pgTable(
  "platforms",
  {
    id: pkUuid(),
    name: text("name").notNull(),
    websiteUrl: text("website_url"),
    ...timestamps,
  },
  (table) => [uniqueIndex("idx_platforms_name").on(table.name)]
);

export type Platform = typeof platforms.$inferSelect;
export type NewPlatform = typeof platforms.$inferInsert;
