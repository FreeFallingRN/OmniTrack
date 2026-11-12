DROP INDEX "idx_series_user_status";--> statement-breakpoint
ALTER TABLE "series" DROP COLUMN "publishing_status";--> statement-breakpoint
ALTER TABLE "series" DROP COLUMN "user_status";--> statement-breakpoint
ALTER TABLE "series" DROP COLUMN "current_cumulative_unit";--> statement-breakpoint
ALTER TABLE "series" DROP COLUMN "total_units";--> statement-breakpoint
ALTER TABLE "series" DROP COLUMN "unit_name";