// TMDB proxy + SQLite cache.
//
// All client traffic for "what titles exist, what their metadata is, and
// what episodes a given season has" goes through here so we (a) never leak
// the TMDB API key to the client and (b) avoid hammering TMDB by caching
// every distinct request.
//
// The scraper-side (ashi.js) is unaffected — it still resolves actual
// playable stream URLs. TMDB just drives discovery / details / episodes.
//
// Required environment variable:
//   TMDB_API_KEY   v3 API key from https://www.themoviedb.org/settings/api
//                  (the older "v3 auth" key, not the v4 read-access bearer)

const sqlite3 = require("sqlite3");
const path = require("path");

const TMDB_API_KEY = process.env.TMDB_API_KEY || "baa12aec93839f2342abae9e0a1d881e";
const TMDB_BASE = "https://api.themoviedb.org/3";

// Image base URLs. We pick reasonable widths for TV — full "/original"
// is wasteful when the client only renders a 200-300 dp poster.
const IMG_POSTER = "https://image.tmdb.org/t/p/w500";
const IMG_STILL  = "https://image.tmdb.org/t/p/w500";
const IMG_BACKDROP = "https://image.tmdb.org/t/p/original";

// ---------- Cache ----------

// Lives next to license.db. Single table keyed by a string `cache_key`.
const cacheDb = new sqlite3.Database(
  path.join(__dirname, "tmdb-cache.db")
);

cacheDb.serialize(() => {
  cacheDb.run(`
    CREATE TABLE IF NOT EXISTS tmdb_cache (
      cache_key  TEXT PRIMARY KEY,
      payload    TEXT NOT NULL,
      fetched_at INTEGER NOT NULL
    )
  `);
});

// Time-to-live in seconds.
//
// Search is short — popular shows update frequently and the scraper
// behind us also influences which titles surface.
// Title and Season are longer because the underlying TMDB record changes
// rarely once a series has aired.
const TTL_SEARCH = 6 * 60 * 60;        // 6h
const TTL_TITLE  = 24 * 60 * 60;       // 24h
const TTL_SEASON = 24 * 60 * 60;       // 24h

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

function cacheGet(key) {
  return new Promise((resolve, reject) => {
    cacheDb.get(
      "SELECT payload, fetched_at FROM tmdb_cache WHERE cache_key = ?",
      [key],
      (err, row) => err ? reject(err) : resolve(row || null)
    );
  });
}

function cachePut(key, payload) {
  return new Promise((resolve, reject) => {
    cacheDb.run(
      `INSERT OR REPLACE INTO tmdb_cache(cache_key, payload, fetched_at)
       VALUES (?, ?, ?)`,
      [key, JSON.stringify(payload), nowSeconds()],
      (err) => err ? reject(err) : resolve()
    );
  });
}

async function withCache(key, ttlSeconds, fetcher) {
  const row = await cacheGet(key);
  if (row && (nowSeconds() - row.fetched_at) < ttlSeconds) {
    try {
      return JSON.parse(row.payload);
    } catch (_) {
      // Bad cached JSON — fall through and refetch.
    }
  }
  const fresh = await fetcher();
  // Best-effort cache write — never fail the request because cache failed.
  try { await cachePut(key, fresh); } catch (e) {
    console.error("tmdb cache write failed:", key, e.message);
  }
  return fresh;
}

// ---------- TMDB fetch helper ----------

async function tmdbFetch(pathName, params = {}) {
  if (!TMDB_API_KEY) {
    throw new Error("TMDB_API_KEY env var not set");
  }
  const qs = new URLSearchParams({
    api_key: TMDB_API_KEY,
    language: "en-US",
    ...params,
  }).toString();
  const url = `${TMDB_BASE}${pathName}?${qs}`;
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`TMDB ${pathName} -> ${res.status}`);
  }
  return res.json();
}

// ---------- Shape helpers ----------

function imageUrl(prefix, p) {
  return p ? `${prefix}${p}` : "";
}

function yearOf(date) {
  if (!date || typeof date !== "string") return null;
  const y = date.slice(0, 4);
  return /^\d{4}$/.test(y) ? y : null;
}

function searchHitToCard(hit) {
  // multi-search returns a heterogeneous array; we only handle tv & movie
  const isTv = hit.media_type === "tv";
  const title = isTv ? hit.name : hit.title;
  const date  = isTv ? hit.first_air_date : hit.release_date;
  return {
    tmdbId: hit.id,
    type: isTv ? "tv" : "movie",
    title: title || "",
    year: yearOf(date),
    posterUrl: imageUrl(IMG_POSTER, hit.poster_path),
    backdropUrl: imageUrl(IMG_BACKDROP, hit.backdrop_path),
    rating: typeof hit.vote_average === "number"
      ? Math.round(hit.vote_average * 10) / 10
      : null,
    ratingCount: hit.vote_count || 0,
    synopsis: hit.overview || "",
  };
}

function pickAgeRating(type, full) {
  if (type === "tv") {
    const list = full.content_ratings?.results || [];
    // Prefer US, then GB, then anything else.
    const us = list.find((x) => x.iso_3166_1 === "US");
    if (us?.rating) return us.rating;
    const gb = list.find((x) => x.iso_3166_1 === "GB");
    if (gb?.rating) return gb.rating;
    const anything = list.find((x) => x.rating);
    return anything?.rating || "";
  } else {
    const list = full.release_dates?.results || [];
    const us = list.find((x) => x.iso_3166_1 === "US");
    const cert = (us?.release_dates || []).find((d) => d.certification);
    if (cert?.certification) return cert.certification;
    // Try GB
    const gb = list.find((x) => x.iso_3166_1 === "GB");
    const certGB = (gb?.release_dates || []).find((d) => d.certification);
    if (certGB?.certification) return certGB.certification;
    return "";
  }
}

function titleToFullShape(type, full) {
  const isTv = type === "tv";
  const title = isTv ? full.name : full.title;
  const firstDate = isTv ? full.first_air_date : full.release_date;
  const lastDate  = isTv ? full.last_air_date  : null;

  const startYear = yearOf(firstDate);
  const endYear   = yearOf(lastDate);
  let yearRange = startYear || "";
  if (isTv && startYear) {
    if (endYear && endYear !== startYear && full.in_production === false) {
      yearRange = `${startYear}–${endYear}`;
    } else if (full.in_production === false && endYear) {
      yearRange = `${startYear}–${endYear}`;
    } else if (full.in_production) {
      yearRange = `${startYear}–`;
    }
  }

  const seasons = isTv
    ? (full.seasons || [])
        // Skip "Season 0" / specials by default — they confuse most viewers.
        .filter((s) => typeof s.season_number === "number" && s.season_number > 0)
        .map((s) => ({
          seasonNumber: s.season_number,
          name: s.name || `Season ${s.season_number}`,
          episodeCount: s.episode_count || 0,
          airDate: s.air_date || null,
          posterUrl: imageUrl(IMG_POSTER, s.poster_path),
        }))
    : [];

  // For movies: use runtime; for TV: episode_run_time is an array.
  let runtimeMinutes = null;
  if (isTv) {
    const arr = full.episode_run_time || [];
    if (arr.length > 0) runtimeMinutes = arr[0];
  } else if (typeof full.runtime === "number") {
    runtimeMinutes = full.runtime;
  }

  return {
    tmdbId: full.id,
    imdbId: full.external_ids?.imdb_id || full.imdb_id || null,
    type,
    title: title || "",
    originalTitle: isTv ? (full.original_name || "") : (full.original_title || ""),
    // Used by the client to route stream resolution to the correct
    // scraper source (e.g. Japanese animation → Animekai, everything
    // else → 1Movies). e.g. "en", "ja", "ko".
    originalLanguage: full.original_language || null,
    year: startYear,
    yearRange: yearRange || startYear || "",
    posterUrl: imageUrl(IMG_POSTER, full.poster_path),
    backdropUrl: imageUrl(IMG_BACKDROP, full.backdrop_path),
    rating: typeof full.vote_average === "number"
      ? Math.round(full.vote_average * 10) / 10
      : null,
    ratingCount: full.vote_count || 0,
    ageRating: pickAgeRating(type, full),
    synopsis: full.overview || "",
    genres: (full.genres || []).map((g) => g.name),
    runtimeMinutes,
    numberOfSeasons: isTv ? (full.number_of_seasons || seasons.length) : 0,
    numberOfEpisodes: isTv ? (full.number_of_episodes || 0) : 0,
    inProduction: !!full.in_production,
    seasons,
  };
}

function seasonToShape(season) {
  return {
    seasonNumber: season.season_number,
    name: season.name || `Season ${season.season_number}`,
    synopsis: season.overview || "",
    episodes: (season.episodes || []).map((e) => ({
      episodeNumber: e.episode_number,
      name: e.name || `Episode ${e.episode_number}`,
      synopsis: e.overview || "",
      stillUrl: imageUrl(IMG_STILL, e.still_path),
      airDate: e.air_date || null,
      runtimeMinutes: typeof e.runtime === "number" ? e.runtime : null,
      rating: typeof e.vote_average === "number"
        ? Math.round(e.vote_average * 10) / 10
        : null,
    })),
  };
}

// ---------- Public functions ----------

/**
 * Multi-search across movies & TV.
 * Returns {results: Card[]}
 */
async function searchTitles(query) {
  const q = (query || "").trim();
  if (!q) return { results: [] };
  const key = `search:${q.toLowerCase()}`;
  return withCache(key, TTL_SEARCH, async () => {
    const data = await tmdbFetch("/search/multi", {
      query: q,
      include_adult: "false",
    });
    const results = (data.results || [])
      .filter((x) => x.media_type === "tv" || x.media_type === "movie")
      .filter((x) => x.poster_path) // hide entries with no poster — they look broken
      .map(searchHitToCard);
    return { results };
  });
}

/**
 * Full title details. type: "tv" | "movie", id: TMDB numeric ID.
 */
async function getTitle(type, id) {
  if (type !== "tv" && type !== "movie") {
    throw new Error(`Unsupported type: ${type}`);
  }
  const numericId = parseInt(id, 10);
  if (!Number.isFinite(numericId) || numericId <= 0) {
    throw new Error(`Invalid TMDB id: ${id}`);
  }
  const append = type === "tv"
    ? "content_ratings,external_ids"
    : "release_dates,external_ids";
  const key = `title:${type}:${numericId}`;
  return withCache(key, TTL_TITLE, async () => {
    const full = await tmdbFetch(`/${type}/${numericId}`, {
      append_to_response: append,
    });
    return titleToFullShape(type, full);
  });
}

/**
 * Episode list for a given TV season, including episode stills (thumbnails),
 * synopsis, runtime, etc.
 */
async function getSeason(id, seasonNumber) {
  const numericId = parseInt(id, 10);
  const numericSeason = parseInt(seasonNumber, 10);
  if (!Number.isFinite(numericId) || numericId <= 0) {
    throw new Error(`Invalid TMDB id: ${id}`);
  }
  if (!Number.isFinite(numericSeason) || numericSeason < 0) {
    throw new Error(`Invalid season: ${seasonNumber}`);
  }
  const key = `season:${numericId}:${numericSeason}`;
  return withCache(key, TTL_SEASON, async () => {
    const full = await tmdbFetch(`/tv/${numericId}/season/${numericSeason}`);
    return seasonToShape(full);
  });
}

module.exports = {
  searchTitles,
  getTitle,
  getSeason,
};
