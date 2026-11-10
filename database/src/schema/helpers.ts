import { uuid, numeric, timestamp } from "drizzle-orm/pg-core";

/**
 * Reusable column helpers to eliminate repetitive boilerplate
 */

/** Primary Key UUID with automatic gen_random_uuid() generation */
export const pkUuid = (name = "id") => uuid(name).primaryKey().defaultRandom();

/** Numeric(7, 2) tracking for chapters, volumes, or episodes (e.g. 45.50) */
export const unitNumber = (name: string) =>
  numeric(name, { precision: 7, scale: 2 }).notNull();

/** Nullable unit number (e.g. total_units when ongoing) */
export const optionalUnitNumber = (name: string) =>
  numeric(name, { precision: 7, scale: 2 });

/** Timestamp with timezone, defaulting to now() */
export const timestampNow = (name: string) =>
  timestamp(name, { withTimezone: true, mode: "string" }).defaultNow().notNull();

/** Standard audit timestamps (created_at & updated_at) */
export const timestamps = {
  createdAt: timestampNow("created_at"),
  updatedAt: timestampNow("updated_at"),
};
