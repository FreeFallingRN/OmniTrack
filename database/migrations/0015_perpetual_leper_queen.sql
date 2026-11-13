ALTER TABLE "alternate_titles" ALTER COLUMN "series_id" DROP NOT NULL;--> statement-breakpoint
CREATE INDEX "idx_alternate_titles_entry_id" ON "alternate_titles" USING btree ("entry_id");