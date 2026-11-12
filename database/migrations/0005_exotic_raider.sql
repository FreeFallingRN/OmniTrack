ALTER TABLE "media_relations" DISABLE ROW LEVEL SECURITY;--> statement-breakpoint
DROP TABLE "media_relations" CASCADE;--> statement-breakpoint
CREATE TEMP TABLE "standalone_entry_series" ON COMMIT DROP AS
SELECT "id" AS "entry_id", gen_random_uuid() AS "series_id"
FROM "media_entries"
WHERE "series_id" IS NULL;
--> statement-breakpoint
INSERT INTO "series" (
	"id",
	"title",
	"publishing_status",
	"user_status",
	"current_cumulative_unit",
	"total_units",
	"unit_name"
)
SELECT
	"mapping"."series_id",
	"entry"."title",
	"entry"."publishing_status",
	"entry"."user_status",
	"entry"."current_unit",
	"entry"."total_units",
	"entry"."unit_name"
FROM "standalone_entry_series" AS "mapping"
JOIN "media_entries" AS "entry" ON "entry"."id" = "mapping"."entry_id";
--> statement-breakpoint
UPDATE "media_entries" AS "entry"
SET "series_id" = "mapping"."series_id"
FROM "standalone_entry_series" AS "mapping"
WHERE "entry"."id" = "mapping"."entry_id";
--> statement-breakpoint
DROP INDEX "idx_media_entries_standalone";--> statement-breakpoint
ALTER TABLE "media_entries" ALTER COLUMN "series_id" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "media_entries" ADD COLUMN "is_canon" boolean DEFAULT true NOT NULL;--> statement-breakpoint
DROP TYPE "public"."relation_type";