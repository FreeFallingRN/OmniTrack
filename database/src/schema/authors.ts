import { pgTable, uuid, text, primaryKey, index } from "drizzle-orm/pg-core";
import { mediaEntries } from "./entries";
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
 * Entry Authors Junction Table
 * Associates creators with a specific book, season, adaptation, or standalone work.
 */
export const entryAuthors = pgTable(
  "entry_authors",
  {
    entryId: uuid("entry_id")
      .notNull()
      .references(() => mediaEntries.id, { onDelete: "cascade" }),

    authorId: uuid("author_id")
      .notNull()
      .references(() => authors.id, { onDelete: "cascade" }),

    role: text("role").notNull().default("author"), // 'author', 'illustrator', 'studio', 'director'
  },
  (table) => [
    primaryKey({ columns: [table.entryId, table.authorId, table.role] }),
    index("idx_entry_authors_author_id").on(table.authorId),
  ]
);

export type Author = typeof authors.$inferSelect;
export type NewAuthor = typeof authors.$inferInsert;

export type EntryAuthor = typeof entryAuthors.$inferSelect;
export type NewEntryAuthor = typeof entryAuthors.$inferInsert;
