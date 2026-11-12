import { relations } from "drizzle-orm";
import { series } from "./series";
import { mediaEntries } from "./entries";
import { alternateTitles } from "./titles";
import { authors, entryAuthors } from "./authors";
import { progressHistory } from "./history";
import { externalLinks } from "./links";

export const seriesRelations = relations(series, ({ many }) => ({
  entries: many(mediaEntries),
  alternateTitles: many(alternateTitles),
  history: many(progressHistory),
  links: many(externalLinks),
}));

export const mediaEntriesRelations = relations(mediaEntries, ({ one, many }) => ({
  series: one(series, {
    fields: [mediaEntries.seriesId],
    references: [series.id],
  }),
  alternateTitles: many(alternateTitles),
  history: many(progressHistory),
  links: many(externalLinks),
  authors: many(entryAuthors),
}));

export const alternateTitlesRelations = relations(alternateTitles, ({ one }) => ({
  series: one(series, {
    fields: [alternateTitles.seriesId],
    references: [series.id],
  }),
  entry: one(mediaEntries, {
    fields: [alternateTitles.entryId],
    references: [mediaEntries.id],
  }),
}));

export const authorsRelations = relations(authors, ({ many }) => ({
  entries: many(entryAuthors),
}));

export const entryAuthorsRelations = relations(entryAuthors, ({ one }) => ({
  entry: one(mediaEntries, {
    fields: [entryAuthors.entryId],
    references: [mediaEntries.id],
  }),
  author: one(authors, {
    fields: [entryAuthors.authorId],
    references: [authors.id],
  }),
}));

export const progressHistoryRelations = relations(progressHistory, ({ one }) => ({
  entry: one(mediaEntries, {
    fields: [progressHistory.entryId],
    references: [mediaEntries.id],
  }),
}));

export const externalLinksRelations = relations(externalLinks, ({ one }) => ({
  series: one(series, {
    fields: [externalLinks.seriesId],
    references: [series.id],
  }),
  entry: one(mediaEntries, {
    fields: [externalLinks.entryId],
    references: [mediaEntries.id],
  }),
}));
