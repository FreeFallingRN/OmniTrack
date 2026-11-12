CREATE TABLE "platforms" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"name" text NOT NULL,
	"website_url" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "media_entries" ADD COLUMN "platform_id" uuid;--> statement-breakpoint
CREATE UNIQUE INDEX "idx_platforms_name" ON "platforms" USING btree ("name");--> statement-breakpoint
INSERT INTO "platforms" ("name")
SELECT DISTINCT "platform" FROM "media_entries"
WHERE "platform" IS NOT NULL AND btrim("platform") <> ''
ON CONFLICT ("name") DO NOTHING;--> statement-breakpoint
UPDATE "media_entries" AS entries
SET "platform_id" = platforms."id"
FROM "platforms"
WHERE platforms."name" = entries."platform";--> statement-breakpoint
ALTER TABLE "media_entries" ADD CONSTRAINT "media_entries_platform_id_platforms_id_fk" FOREIGN KEY ("platform_id") REFERENCES "public"."platforms"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "series" DROP COLUMN "notes";--> statement-breakpoint
ALTER TABLE "media_entries" DROP COLUMN "platform";