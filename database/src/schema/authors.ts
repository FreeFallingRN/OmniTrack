import { pgTable, uuid, text, primaryKey, index } from "drizzle-orm/pg-core";
import { series } from "./series";
import { pkUuid, timestamps } from "./helpers";

/**
 * Authors / Creators Table
 * Authors, illustrators, mangaka, animation studios, or directors.
 */
export const authors = pgTable("authors", {
  id: pkUuid(),
  name: text("name").notNull(),
  nativeName: text("native_name"), // e.g. "추공", "長月達平"
  imageUrl: text("image_url"),
  bio: text("bio"),

  ...timestamps,
});

/**
 * Series Authors Junction Table
 * Associates creators with their works and specific roles.
 */
export const seriesAuthors = pgTable(
  "series_authors",
  {
    seriesId: uuid("series_id")
      .notNull()
      .references(() => series.id, { onDelete: "cascade" }),

    authorId: uuid("author_id")
      .notNull()
      .references(() => authors.id, { onDelete: "cascade" }),

    role: text("role").notNull().default("author"), // 'author', 'illustrator', 'studio', 'director'
  },
  (table) => [
    primaryKey({ columns: [table.seriesId, table.authorId, table.role] }),
    index("idx_series_authors_author_id").on(table.authorId),
  ]
);

export type Author = typeof authors.$inferSelect;
export type NewAuthor = typeof authors.$inferInsert;

export type SeriesAuthor = typeof seriesAuthors.$inferSelect;
export type NewSeriesAuthor = typeof seriesAuthors.$inferInsert;
