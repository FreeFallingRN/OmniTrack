DROP TRIGGER IF EXISTS trigger_ensure_single_active_entry ON "media_entries";--> statement-breakpoint
DROP INDEX "idx_media_entries_is_active";--> statement-breakpoint
ALTER TABLE "media_entries" DROP COLUMN "is_active_target";