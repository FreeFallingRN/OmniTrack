CREATE TABLE "entry_authors" (
	"entry_id" uuid NOT NULL,
	"author_id" uuid NOT NULL,
	"role" text DEFAULT 'author' NOT NULL,
	CONSTRAINT "entry_authors_entry_id_author_id_role_pk" PRIMARY KEY("entry_id","author_id","role")
);
--> statement-breakpoint
INSERT INTO "entry_authors" ("entry_id", "author_id", "role")
SELECT DISTINCT me."id", sa."author_id", sa."role"
FROM "media_entries" me
JOIN "series_authors" sa ON sa."series_id" = me."series_id";
--> statement-breakpoint
ALTER TABLE "series_authors" DISABLE ROW LEVEL SECURITY;--> statement-breakpoint
DROP TABLE "series_authors" CASCADE;--> statement-breakpoint
DROP INDEX "idx_series_type";--> statement-breakpoint
DROP INDEX "idx_series_is_active";--> statement-breakpoint
ALTER TABLE "entry_authors" ADD CONSTRAINT "entry_authors_entry_id_media_entries_id_fk" FOREIGN KEY ("entry_id") REFERENCES "public"."media_entries"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "entry_authors" ADD CONSTRAINT "entry_authors_author_id_authors_id_fk" FOREIGN KEY ("author_id") REFERENCES "public"."authors"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "idx_entry_authors_author_id" ON "entry_authors" USING btree ("author_id");--> statement-breakpoint
DROP TRIGGER IF EXISTS "trigger_ensure_single_active_series" ON "series";--> statement-breakpoint
ALTER TABLE "series" DROP COLUMN "type";--> statement-breakpoint
ALTER TABLE "series" DROP COLUMN "is_active_target";