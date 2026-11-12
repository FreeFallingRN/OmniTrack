ALTER TABLE "progress_history" DROP CONSTRAINT "progress_history_series_id_series_id_fk";
--> statement-breakpoint
DROP INDEX "idx_progress_history_series_id";
--> statement-breakpoint
ALTER TABLE "progress_history" ADD COLUMN "activity_date" date;
--> statement-breakpoint
UPDATE "progress_history" AS "history"
SET "entry_id" = (
	SELECT "entry"."id"
	FROM "media_entries" AS "entry"
	WHERE "entry"."series_id" = "history"."series_id"
	ORDER BY "entry"."sequence_number", "entry"."id"
	LIMIT 1
)
WHERE "history"."entry_id" IS NULL;
--> statement-breakpoint
DELETE FROM "progress_history" WHERE "entry_id" IS NULL;
--> statement-breakpoint
UPDATE "progress_history"
SET "activity_date" = ("logged_at" AT TIME ZONE 'UTC')::date;
--> statement-breakpoint
CREATE TEMP TABLE "daily_progress_history" ON COMMIT DROP AS
SELECT
	"entry_id",
	"activity_date",
	(array_agg("previous_unit" ORDER BY "logged_at", "id"))[1] AS "previous_unit",
	(array_agg("new_unit" ORDER BY "logged_at" DESC, "id" DESC))[1] AS "new_unit",
	(array_agg("source" ORDER BY "logged_at" DESC, "id" DESC))[1] AS "source",
	MAX("logged_at") AS "logged_at"
FROM "progress_history"
GROUP BY "entry_id", "activity_date";
--> statement-breakpoint
DELETE FROM "progress_history";
--> statement-breakpoint
INSERT INTO "progress_history" (
	"entry_id",
	"activity_date",
	"previous_unit",
	"new_unit",
	"source",
	"logged_at"
)
SELECT
	"entry_id",
	"activity_date",
	"previous_unit",
	"new_unit",
	"source",
	"logged_at"
FROM "daily_progress_history";
--> statement-breakpoint
ALTER TABLE "progress_history" ALTER COLUMN "entry_id" SET NOT NULL;
--> statement-breakpoint
ALTER TABLE "progress_history" ALTER COLUMN "activity_date" SET NOT NULL;
--> statement-breakpoint
ALTER TABLE "progress_history" DROP COLUMN "series_id";
--> statement-breakpoint
ALTER TABLE "progress_history" DROP COLUMN "previous_cumulative_unit";
--> statement-breakpoint
ALTER TABLE "progress_history" DROP COLUMN "new_cumulative_unit";
--> statement-breakpoint
CREATE UNIQUE INDEX "uq_progress_history_entry_day"
ON "progress_history" USING btree ("entry_id", "activity_date");
