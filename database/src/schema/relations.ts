import { relations } from "drizzle-orm";
import { series } from "./series";
import { mediaEntries } from "./entries";
import { alternateTitles } from "./titles";
import { authors, entryAuthors } from "./authors";
import { mediaRelations } from "./relations_model";
import { progressHistory } from "./history";
import { externalLinks } from "./links";

export const seriesRelations = relations(series, ({ many }) => ({
  entries: many(mediaEntries),
  alternateTitles: many(alternateTitles),
  history: many(progressHistory),
  links: many(externalLinks),
  adaptations: many(mediaRelations, { relationName: "sourceSeries" }),
  adaptedFrom: many(mediaRelations, { relationName: "targetSeries" }),
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

export const mediaRelationsRelations = relations(mediaRelations, ({ one }) => ({
  sourceSeries: one(series, {
    fields: [mediaRelations.sourceSeriesId],
    references: [series.id],
    relationName: "sourceSeries",
  }),
  targetSeries: one(series, {
    fields: [mediaRelations.targetSeriesId],
    references: [series.id],
    relationName: "targetSeries",
  }),
}));

export const progressHistoryRelations = relations(progressHistory, ({ one }) => ({
  series: one(series, {
    fields: [progressHistory.seriesId],
    references: [series.id],
  }),
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
