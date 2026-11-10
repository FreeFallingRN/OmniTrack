CREATE TYPE "public"."media_type" AS ENUM('novel', 'comic', 'anime', 'movie');--> statement-breakpoint
CREATE TYPE "public"."publishing_status" AS ENUM('announced', 'releasing', 'hiatus', 'completed', 'discontinued');--> statement-breakpoint
CREATE TYPE "public"."relation_type" AS ENUM('adaptation', 'source_material', 'sequel', 'prequel', 'spin_off', 'side_story', 'alternate_version');--> statement-breakpoint
CREATE TYPE "public"."user_status" AS ENUM('planning', 'in_progress', 'completed', 'paused', 'dropped');--> statement-breakpoint
CREATE TABLE "series" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"title" text NOT NULL,
	"clean_title" text GENERATED ALWAYS AS (lower(regexp_replace(title, '[^a-zA-Z0-9]', '', 'g'))) STORED,
	"type" "media_type" NOT NULL,
	"publishing_status" "publishing_status" DEFAULT 'releasing' NOT NULL,
	"user_status" "user_status" DEFAULT 'planning' NOT NULL,
	"current_cumulative_unit" numeric(7, 2) DEFAULT '0.00' NOT NULL,
	"total_units" numeric(7, 2),
	"unit_name" text DEFAULT 'Chapter' NOT NULL,
	"rating" smallint,
	"notes" text,
	"is_active_target" boolean DEFAULT false,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "media_entries" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"series_id" uuid NOT NULL,
	"title" text NOT NULL,
	"sequence_number" numeric(5, 2) DEFAULT '1.00' NOT NULL,
	"publishing_status" "publishing_status" DEFAULT 'releasing' NOT NULL,
	"user_status" "user_status" DEFAULT 'planning' NOT NULL,
	"current_unit" numeric(7, 2) DEFAULT '0.00' NOT NULL,
	"total_units" numeric(7, 2),
	"start_cumulative_unit" numeric(7, 2) DEFAULT '0.00' NOT NULL,
	"unit_name" text DEFAULT 'Chapter' NOT NULL,
	"source_url" text,
	"platform" text,
	"is_active_target" boolean DEFAULT false,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "alternate_titles" (
	"id" bigint PRIMARY KEY GENERATED ALWAYS AS IDENTITY (sequence name "alternate_titles_id_seq" INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 CACHE 1),
	"series_id" uuid NOT NULL,
	"entry_id" uuid,
	"title" text NOT NULL,
	"clean_title" text GENERATED ALWAYS AS (lower(regexp_replace(title, '[^a-zA-Z0-9]', '', 'g'))) STORED,
	"language" text,
	"is_primary" boolean DEFAULT false,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "authors" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"name" text NOT NULL,
	"native_name" text,
	"image_url" text,
	"bio" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "series_authors" (
	"series_id" uuid NOT NULL,
	"author_id" uuid NOT NULL,
	"role" text DEFAULT 'author' NOT NULL,
	CONSTRAINT "series_authors_series_id_author_id_role_pk" PRIMARY KEY("series_id","author_id","role")
);
--> statement-breakpoint
CREATE TABLE "media_relations" (
	"id" bigint PRIMARY KEY GENERATED ALWAYS AS IDENTITY (sequence name "media_relations_id_seq" INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 CACHE 1),
	"source_series_id" uuid NOT NULL,
	"target_series_id" uuid NOT NULL,
	"relation_type" "relation_type" NOT NULL,
	"notes" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "progress_history" (
	"id" bigint PRIMARY KEY GENERATED ALWAYS AS IDENTITY (sequence name "progress_history_id_seq" INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 CACHE 1),
	"series_id" uuid NOT NULL,
	"entry_id" uuid,
	"previous_unit" numeric(7, 2) NOT NULL,
	"new_unit" numeric(7, 2) NOT NULL,
	"previous_cumulative_unit" numeric(7, 2),
	"new_cumulative_unit" numeric(7, 2),
	"source" text NOT NULL,
	"logged_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "external_links" (
	"id" bigint PRIMARY KEY GENERATED ALWAYS AS IDENTITY (sequence name "external_links_id_seq" INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 CACHE 1),
	"series_id" uuid NOT NULL,
	"entry_id" uuid,
	"platform" text NOT NULL,
	"display_name" text NOT NULL,
	"url" text NOT NULL,
	"icon_key" text DEFAULT 'generic' NOT NULL,
	"is_primary" boolean DEFAULT false,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "media_entries" ADD CONSTRAINT "media_entries_series_id_series_id_fk" FOREIGN KEY ("series_id") REFERENCES "public"."series"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "alternate_titles" ADD CONSTRAINT "alternate_titles_series_id_series_id_fk" FOREIGN KEY ("series_id") REFERENCES "public"."series"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "alternate_titles" ADD CONSTRAINT "alternate_titles_entry_id_media_entries_id_fk" FOREIGN KEY ("entry_id") REFERENCES "public"."media_entries"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "series_authors" ADD CONSTRAINT "series_authors_series_id_series_id_fk" FOREIGN KEY ("series_id") REFERENCES "public"."series"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "series_authors" ADD CONSTRAINT "series_authors_author_id_authors_id_fk" FOREIGN KEY ("author_id") REFERENCES "public"."authors"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "media_relations" ADD CONSTRAINT "media_relations_source_series_id_series_id_fk" FOREIGN KEY ("source_series_id") REFERENCES "public"."series"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "media_relations" ADD CONSTRAINT "media_relations_target_series_id_series_id_fk" FOREIGN KEY ("target_series_id") REFERENCES "public"."series"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "progress_history" ADD CONSTRAINT "progress_history_series_id_series_id_fk" FOREIGN KEY ("series_id") REFERENCES "public"."series"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "progress_history" ADD CONSTRAINT "progress_history_entry_id_media_entries_id_fk" FOREIGN KEY ("entry_id") REFERENCES "public"."media_entries"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "external_links" ADD CONSTRAINT "external_links_series_id_series_id_fk" FOREIGN KEY ("series_id") REFERENCES "public"."series"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "external_links" ADD CONSTRAINT "external_links_entry_id_media_entries_id_fk" FOREIGN KEY ("entry_id") REFERENCES "public"."media_entries"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "idx_series_type" ON "series" USING btree ("type");--> statement-breakpoint
CREATE INDEX "idx_series_user_status" ON "series" USING btree ("user_status");--> statement-breakpoint
CREATE INDEX "idx_series_clean_title" ON "series" USING btree ("clean_title");--> statement-breakpoint
CREATE INDEX "idx_series_is_active" ON "series" USING btree ("is_active_target") WHERE is_active_target = TRUE;--> statement-breakpoint
CREATE INDEX "idx_media_entries_series_id" ON "media_entries" USING btree ("series_id");--> statement-breakpoint
CREATE INDEX "idx_media_entries_user_status" ON "media_entries" USING btree ("user_status");--> statement-breakpoint
CREATE INDEX "idx_media_entries_is_active" ON "media_entries" USING btree ("is_active_target") WHERE is_active_target = TRUE;--> statement-breakpoint
CREATE INDEX "idx_alternate_titles_series_id" ON "alternate_titles" USING btree ("series_id");--> statement-breakpoint
CREATE INDEX "idx_alternate_titles_clean_title" ON "alternate_titles" USING btree ("clean_title");--> statement-breakpoint
CREATE INDEX "idx_series_authors_author_id" ON "series_authors" USING btree ("author_id");--> statement-breakpoint
CREATE INDEX "idx_media_relations_source" ON "media_relations" USING btree ("source_series_id");--> statement-breakpoint
CREATE INDEX "idx_media_relations_target" ON "media_relations" USING btree ("target_series_id");--> statement-breakpoint
CREATE INDEX "idx_progress_history_series_id" ON "progress_history" USING btree ("series_id");--> statement-breakpoint
CREATE INDEX "idx_progress_history_entry_id" ON "progress_history" USING btree ("entry_id");--> statement-breakpoint
CREATE INDEX "idx_progress_history_logged_at" ON "progress_history" USING btree ("logged_at");--> statement-breakpoint
CREATE INDEX "idx_external_links_series_id" ON "external_links" USING btree ("series_id");--> statement-breakpoint
CREATE INDEX "idx_external_links_entry_id" ON "external_links" USING btree ("entry_id");--> statement-breakpoint
CREATE INDEX "idx_external_links_platform" ON "external_links" USING btree ("platform");