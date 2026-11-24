CREATE TABLE "scrape_issue_reports" (
	"id" uuid PRIMARY KEY NOT NULL,
	"source_url" text NOT NULL,
	"normalized_url" text NOT NULL,
	"adapter_name" text NOT NULL,
	"scraped_snapshot" jsonb NOT NULL,
	"issue_tags" jsonb NOT NULL,
	"note" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"synced" boolean DEFAULT true NOT NULL
);
--> statement-breakpoint
ALTER TABLE "scrape_issue_reports" ENABLE ROW LEVEL SECURITY;
--> statement-breakpoint
CREATE POLICY "Allow anon and auth all on scrape_issue_reports" ON "scrape_issue_reports"
FOR ALL TO anon, authenticated USING (true) WITH CHECK (true);
