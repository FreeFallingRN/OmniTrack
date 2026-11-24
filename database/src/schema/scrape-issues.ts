import { boolean, jsonb, pgTable, text, uuid } from "drizzle-orm/pg-core";
import { timestampNow } from "./helpers";

export const scrapeIssueReports = pgTable("scrape_issue_reports", {
  id: uuid("id").primaryKey(),
  sourceUrl: text("source_url").notNull(),
  normalizedUrl: text("normalized_url").notNull(),
  adapterName: text("adapter_name").notNull(),
  scrapedSnapshot: jsonb("scraped_snapshot").notNull(),
  issueTags: jsonb("issue_tags").notNull(),
  note: text("note").notNull(),
  createdAt: timestampNow("created_at"),
  synced: boolean("synced").notNull().default(true),
});
