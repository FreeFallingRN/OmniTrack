ALTER TABLE "media_entries" ALTER COLUMN "series_id" DROP NOT NULL;--> statement-breakpoint
CREATE INDEX "idx_media_entries_standalone" ON "media_entries" USING btree ("id") WHERE series_id IS NULL;