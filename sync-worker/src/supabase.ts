import { MatchedSeries } from "./types.js";

/**
 * PostgREST API client designed for Google Apps Script using UrlFetchApp.
 */
export class SupabaseClient {
  private baseUrl: string;
  private headers: { [key: string]: string };

  constructor(supabaseUrl: string, anonKey: string) {
    this.baseUrl = supabaseUrl.replace(/\/+$/, "");
    this.headers = {
      apikey: anonKey,
      Authorization: `Bearer ${anonKey}`,
      "Content-Type": "application/json",
      Prefer: "return=representation",
    };
  }

  private request(
    path: string,
    method: "get" | "post" | "patch" = "get",
    payload?: any,
    prefer: string = this.headers.Prefer
  ): any {
    const url = `${this.baseUrl}${path}`;
    const options: GoogleAppsScript.URL_Fetch.URLFetchRequestOptions = {
      method,
      headers: { ...this.headers, Prefer: prefer },
      muteHttpExceptions: true,
    };

    if (payload && (method === "post" || method === "patch")) {
      options.payload = JSON.stringify(payload);
    }

    const response = UrlFetchApp.fetch(url, options);
    const code = response.getResponseCode();
    const content = response.getContentText();

    if (code >= 200 && code < 300) {
      return content ? JSON.parse(content) : null;
    }

    throw new Error(`[Supabase API Error ${code}] ${content}`);
  }

  /**
   * Looks up a series and entry by clean_title against alternate_titles or series table.
   */
  public findSeriesOrEntry(cleanTitle: string): MatchedSeries | null {
    // 1. Search alternate_titles first (handles translations, romanizations, and specific books)
    try {
      const altUrl = `/rest/v1/alternate_titles?clean_title=eq.${encodeURIComponent(cleanTitle)}&select=series_id,entry_id,series:series_id(id,title,current_cumulative_unit)&limit=1`;
      const altResults = this.request(altUrl, "get");

      if (altResults && altResults.length > 0) {
        const item = altResults[0];
        const s = item.series;
        return this.hydrateMatchedSeries(s.id, s.title, parseFloat(s.current_cumulative_unit || "0"), item.entry_id);
      }
    } catch (err) {
      console.warn(`[Supabase] Search alternate_titles failed for '${cleanTitle}':`, err);
    }

    // 2. Search series table directly
    try {
      const seriesUrl = `/rest/v1/series?clean_title=eq.${encodeURIComponent(cleanTitle)}&select=id,title,current_cumulative_unit&limit=1`;
      const seriesResults = this.request(seriesUrl, "get");

      if (seriesResults && seriesResults.length > 0) {
        const s = seriesResults[0];
        return this.hydrateMatchedSeries(s.id, s.title, parseFloat(s.current_cumulative_unit || "0"));
      }
    } catch (err) {
      console.warn(`[Supabase] Search series failed for '${cleanTitle}':`, err);
    }

    return null;
  }

  /**
   * Hydrates media entry details (book / volume) for the matched series.
   */
  private hydrateMatchedSeries(
    seriesId: string,
    seriesTitle: string,
    currentCumulativeUnit: number,
    targetEntryId?: string
  ): MatchedSeries {
    try {
      let entryUrl = `/rest/v1/media_entries?series_id=eq.${seriesId}&order=sequence_number.asc`;
      if (targetEntryId) {
        entryUrl = `/rest/v1/media_entries?id=eq.${targetEntryId}&limit=1`;
      }

      const entries = this.request(entryUrl, "get");
      if (entries && entries.length > 0) {
        const entry = entries[0];
        return {
          seriesId,
          seriesTitle,
          currentCumulativeUnit,
          entryId: entry.id,
          entryTitle: entry.title,
          entryCurrentUnit: parseFloat(entry.current_unit || "0"),
        };
      }
    } catch (err) {
      console.warn(`[Supabase] Fetch entries for series ${seriesId} failed:`, err);
    }

    return {
      seriesId,
      seriesTitle,
      currentCumulativeUnit,
    };
  }

  /**
   * Updates current_unit on media_entries and cumulative progress on series, logging to progress_history.
   */
  public updateProgress(match: MatchedSeries, newChapter: number): void {
    const previousCumulative = match.currentCumulativeUnit;
    let newCumulative = newChapter;

    if (match.entryId) {
      // Update specific book/entry
      this.request(`/rest/v1/media_entries?id=eq.${match.entryId}`, "patch", {
        current_unit: newChapter,
      });

      // Update series cumulative unit
      this.request(`/rest/v1/series?id=eq.${match.seriesId}`, "patch", {
        current_cumulative_unit: newCumulative,
      });

      // Log progress history
      this.request(`/rest/v1/progress_history?on_conflict=entry_id,activity_date`, "post", {
        entry_id: match.entryId,
        activity_date: Utilities.formatDate(new Date(), Session.getScriptTimeZone(), "yyyy-MM-dd"),
        previous_unit: match.entryCurrentUnit || 0,
        new_unit: newChapter,
        source: "moon_reader_sync",
      }, "resolution=merge-duplicates,return=minimal");
    } else {
      // Continuous series update
      this.request(`/rest/v1/series?id=eq.${match.seriesId}`, "patch", {
        current_cumulative_unit: newChapter,
      });

    }

    console.log(
      `[Supabase] Successfully updated '${match.seriesTitle}' -> Chapter ${newChapter} (Cumulative: ${newCumulative})`
    );
  }
}

