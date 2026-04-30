'use strict';

const { searchResults } = require('./ashi');
const tmdb              = require('./tmdb');
const cacheDb           = require('./content-cache-db');
const licenseDb         = require('./license-db');

// ── Config ───────────────────────────────────────────────────────────────────

const TMDB_API_KEY = process.env.TMDB_API_KEY || "baa12aec93839f2342abae9e0a1d881e";
const TMDB_BASE    = "https://api.themoviedb.org/3";
const IMG_POSTER   = "https://image.tmdb.org/t/p/w500";
const IMG_BACKDROP = "https://image.tmdb.org/t/p/original";

// ── Verify scanner ───────────────────────────────────────────────────────────
// 15 s between scraper calls → ~240 titles/hour.
// Items already verified within REVERIFY_AGE_MS are refreshed with no
// scraper call and don't count toward this rate.
const VERIFY_DELAY_MS      = 15_000;
const REVERIFY_AGE_MS      = 4 * 60 * 60 * 1000;
const SCAN_BOOT_DELAY_MS   = 30_000;
// Cooldown between scan cycles in continuous mode. Kept short so the
// verifier behaves "always-on" — when one batch wraps, the next starts
// almost immediately. Small enough that we don't idle, large enough that
// we don't pummel TMDB if the cache is fully fresh and the loop runs in
// seconds per cycle.
const SCAN_LOOP_COOLDOWN_MS = 60_000;
// Hard age cap on `scraper_match_cache` entries. Past this, even a cached
// match forces a real scraper re-check on the next verify pass — protects
// against silently trusting a stale "this is streamable" mapping for a
// title the source has since dropped.
const MATCH_CACHE_TTL_MS    = 12 * 60 * 60 * 1000;

// ── Enrichment scanner ───────────────────────────────────────────────────────
// Fills age_rating + keywords for streamable titles that don't have them yet.
// Pure TMDB API calls — no scraper involvement — so can run fast.
// 2 s between calls → ~1 800/hour, well within TMDB's limits.
const ENRICH_DELAY_MS         = 2_000;
const ENRICH_BOOT_DELAY_MS    = 5 * 60 * 1000;
const ENRICH_LOOP_COOLDOWN_MS = 30 * 60 * 1000;   // 30 min between cycles

// ── TMDB browse categories ───────────────────────────────────────────────────
//
// `pages` is the number of TMDB pages to walk per category. 1 page = 20 results.
// With ~30% overlap between categories, a full scan now produces ~700-1000
// unique candidates per cycle, up from ~350.

const CATEGORIES = [
  { name: 'popular_movie',   path: '/movie/popular',       type: 'movie', pages: 10 },
  { name: 'popular_tv',      path: '/tv/popular',          type: 'tv',    pages: 10 },
  { name: 'trending_movie',  path: '/trending/movie/week', type: 'movie', pages: 5  },
  { name: 'trending_tv',     path: '/trending/tv/week',    type: 'tv',    pages: 5  },
  { name: 'new_movie',       path: '/movie/now_playing',   type: 'movie', pages: 5  },
  { name: 'new_tv',          path: '/tv/on_the_air',       type: 'tv',    pages: 5  },
  { name: 'top_rated_movie', path: '/movie/top_rated',     type: 'movie', pages: 5  },
  { name: 'top_rated_tv',    path: '/tv/top_rated',        type: 'tv',    pages: 5  },
];

// ── State ────────────────────────────────────────────────────────────────────

let scanInProgress   = false;
let enrichInProgress = false;
// Admin-controlled pause. When set, both scan loops idle (sleeping at
// their cooldown points) without picking up new work. Currently-running
// scans finish their current item then yield. Resuming kicks the loops
// straight back into action — no need to restart the server.
let scannerPaused    = false;

// ── Utilities ────────────────────────────────────────────────────────────────

const sleep = ms => new Promise(r => setTimeout(r, ms));

const licenseGet = (sql, params) =>
  new Promise((resolve, reject) =>
    licenseDb.get(sql, params, (err, row) => err ? reject(err) : resolve(row || null))
  );

const licenseRun = (sql, params) =>
  new Promise((resolve, reject) =>
    licenseDb.run(sql, params, function (err) { err ? reject(err) : resolve(this); })
  );

// ── TMDB helpers ─────────────────────────────────────────────────────────────

async function tmdbFetch(urlPath, params = {}) {
  const qs  = new URLSearchParams({ api_key: TMDB_API_KEY, language: 'en-US', ...params }).toString();
  const res = await fetch(`${TMDB_BASE}${urlPath}?${qs}`);
  if (!res.ok) throw new Error(`TMDB ${urlPath} → ${res.status}`);
  return res.json();
}

async function fetchGenreMaps() {
  const [movieList, tvList] = await Promise.all([
    tmdbFetch('/genre/movie/list'),
    tmdbFetch('/genre/tv/list'),
  ]);
  const toMap = arr => Object.fromEntries((arr || []).map(g => [g.id, g.name]));
  return { movie: toMap(movieList.genres || []), tv: toMap(tvList.genres || []) };
}

function normaliseItem(raw, type, genreMap) {
  const isTv      = type === 'tv';
  const title     = (isTv ? raw.name          : raw.title)          || '';
  const origTitle = (isTv ? raw.original_name  : raw.original_title) || title;
  const dateStr   = (isTv ? raw.first_air_date : raw.release_date)   || '';
  const year      = dateStr.slice(0, 4) || null;
  const genreIds  = raw.genre_ids || [];
  const genres    = genreIds.map(id => genreMap[type][id]).filter(Boolean);
  const origLang  = raw.original_language || 'en';
  // Detection MUST match the client bridge's `isAnimeTitle()` exactly,
  // otherwise the cache will route a title to one source for verification
  // and the bridge will route the same title to a different source at
  // playback — surfacing as "Failed to fetch streams" on titles that
  // *appeared* to be cached as streamable. Bridge uses Animation genre
  // (TMDB id 16) on its own; we do the same.
  const isAnime   = genreIds.includes(16);

  return {
    tmdbId: raw.id, tmdbType: type,
    title, originalTitle: origTitle, year,
    posterUrl:   raw.poster_path   ? `${IMG_POSTER}${raw.poster_path}`     : '',
    backdropUrl: raw.backdrop_path ? `${IMG_BACKDROP}${raw.backdrop_path}` : '',
    overview: raw.overview || '',
    genres, genreIds,
    voteAverage: raw.vote_average || 0,
    voteCount:   raw.vote_count   || 0,
    originalLanguage: origLang, isAnime,
    categories: [],
  };
}

// ── Build deduped queue ───────────────────────────────────────────────────────

async function buildQueue(genreMap) {
  const byKey = new Map();
  for (const cat of CATEGORIES) {
    let position = 0;
    for (let page = 1; page <= cat.pages; page++) {
      let data;
      try {
        data = await tmdbFetch(cat.path, { page });
      } catch (err) {
        console.error(`[ContentCache] TMDB fetch failed ${cat.path} p${page}:`, err.message);
        continue;
      }
      for (const raw of (data.results || [])) {
        if (!raw.poster_path) continue;
        const key = `${raw.id}:${cat.type}`;
        let item = byKey.get(key);
        if (!item) { item = normaliseItem(raw, cat.type, genreMap); byKey.set(key, item); }
        item.categories.push({ name: cat.name, position: ++position });
      }
    }
  }
  return [...byKey.values()];
}

// ── Scraper scoring (JS port of TmdbStreamBridge.kt) ─────────────────────────

function seasonAdjustment(candidateLower, requestedSeason) {
  if (new RegExp(`\\bseason\\s+${requestedSeason}\\b`).test(candidateLower)) return 250;
  if (new RegExp(`(?:^|[\\s:!\\-])${requestedSeason}\\b\\s*$`).test(candidateLower)) return 200;
  const sn = /\bseason\s+(\d+)\b/.exec(candidateLower);
  if (sn && parseInt(sn[1]) !== requestedSeason) return -200;
  const tn = /(?:^|[\s:!\-])(\d+)\b\s*$/.exec(candidateLower);
  if (tn) { const n = parseInt(tn[1]); if (n >= 2 && n <= 9 && n !== requestedSeason) return -150; }
  return requestedSeason === 1 ? 0 : -25;
}

function scoreCandidate(targetTitle, year, season, candidate) {
  const t    = targetTitle.trim().toLowerCase();
  const rawC = (candidate.title || '').trim().toLowerCase();
  if (!t || !rawC) return 0;
  const cStripped = rawC.replace(/\s*\([^)]*\)\s*/g, ' ').replace(/\s+/g, ' ').trim();

  let score;
  if (rawC === t || cStripped === t)                                          score = 1000;
  else if (cStripped.startsWith(`${t} `) || cStripped.startsWith(`${t}:`))   score = 800;
  else if (cStripped.startsWith(t))                                           score = 700;
  else {
    const tWords = t.split(/\s+/).filter(w => w.length >= 2);
    const cWords = cStripped.split(/[\s\-:!?]+/).filter(w => w.length >= 2);
    if (!tWords.length || !cWords.length) { score = 0; }
    else {
      const matches = tWords.filter(tw => cWords.some(cw => cw.startsWith(tw))).length;
      score = Math.floor((matches / tWords.length) * 500);
    }
  }
  if (year && rawC.includes(year)) score += 80;
  if (season !== null)             score += seasonAdjustment(rawC, season);
  return score;
}

function pickBestMatch(title, year, candidates) {
  if (!candidates.length) return null;
  let best = null, bestScore = -1;
  for (const c of candidates) {
    const s = scoreCandidate(title, year, null, c);
    if (s > bestScore) { bestScore = s; best = c; }
  }
  return bestScore >= 250 ? best : null;
}

// ── Scraper verification ──────────────────────────────────────────────────────

async function verifyOnScraper(item) {
  const queries = [item.title];
  if (item.originalTitle && item.originalTitle !== item.title) queries.push(item.originalTitle);

  for (const query of queries) {
    let results;
    try { results = JSON.parse(await searchResults(query)); } catch { continue; }
    const filtered = item.isAnime
      ? results.filter(r => r.href && r.href.startsWith('Animekai:'))
      : results.filter(r => r.href && !r.href.startsWith('Animekai:') && r.href !== '');
    const match = pickBestMatch(item.title, item.year, filtered);
    if (match) return match;
  }
  return null;
}

// ── DB helpers ────────────────────────────────────────────────────────────────

async function deleteContent(item) {
  await cacheDb.run(
    'DELETE FROM cached_content WHERE tmdb_id = ? AND tmdb_type = ?',
    [item.tmdbId, item.tmdbType]
  );
  await cacheDb.run(
    'DELETE FROM content_categories WHERE tmdb_id = ? AND tmdb_type = ?',
    [item.tmdbId, item.tmdbType]
  );
}

async function upsertContent(item, scraperHref, scraperImage) {
  const now = new Date().toISOString();
  await cacheDb.run(`
    INSERT INTO cached_content
      (tmdb_id, tmdb_type, title, original_title, overview, poster_url, backdrop_url,
       year, genres, vote_average, vote_count, is_anime,
       scraper_href, scraper_image, is_streamable, last_verified)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?)
    ON CONFLICT(tmdb_id, tmdb_type) DO UPDATE SET
      title          = excluded.title,
      original_title = excluded.original_title,
      overview       = excluded.overview,
      poster_url     = excluded.poster_url,
      backdrop_url   = excluded.backdrop_url,
      year           = excluded.year,
      genres         = excluded.genres,
      vote_average   = excluded.vote_average,
      vote_count     = excluded.vote_count,
      is_anime       = excluded.is_anime,
      scraper_href   = excluded.scraper_href,
      scraper_image  = excluded.scraper_image,
      is_streamable  = 1,
      last_verified  = excluded.last_verified
  `, [
    item.tmdbId, item.tmdbType, item.title, item.originalTitle,
    item.overview, item.posterUrl, item.backdropUrl,
    item.year, JSON.stringify(item.genres),
    item.voteAverage, item.voteCount, item.isAnime ? 1 : 0,
    scraperHref || null, scraperImage || null, now,
  ]);
}

async function upsertCategories(item) {
  const now = new Date().toISOString();
  for (const cat of item.categories) {
    await cacheDb.run(`
      INSERT INTO content_categories (category, tmdb_id, tmdb_type, position, updated_at)
      VALUES (?,?,?,?,?)
      ON CONFLICT(category, tmdb_id, tmdb_type) DO UPDATE SET
        position = excluded.position, updated_at = excluded.updated_at
    `, [cat.name, item.tmdbId, item.tmdbType, cat.position, now]);
  }
}

async function writeMatchCache(key, href, image) {
  const now = new Date().toISOString();
  await licenseRun(`
    INSERT INTO scraper_match_cache (cache_key, scraper_href, scraper_image, updated_at)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(cache_key) DO UPDATE SET
      scraper_href  = excluded.scraper_href,
      scraper_image = excluded.scraper_image,
      updated_at    = excluded.updated_at
  `, [key, href, image || '', now]);
}

// ── Verify scanner ────────────────────────────────────────────────────────────

async function runScan() {
  if (scannerPaused) return;
  if (scanInProgress) { console.log('[ContentCache] Scan already in progress, skipping.'); return; }
  scanInProgress = true;
  const t0 = Date.now();
  console.log('[ContentCache] ── Scan started ──────────────────────────────────────');

  try {
    const genreMap = await fetchGenreMaps();
    const queue    = await buildQueue(genreMap);
    console.log(`[ContentCache] ${queue.length} unique titles queued for verification`);

    // Clear last cycle's category positions so each scan rebuilds the
    // curated lists from current TMDB rankings. Without this, a title
    // that was popular last week but isn't this week would still appear
    // in `getCategory('popular_movie')`. Titles that survive the new
    // scan get fresh positions inserted via upsertCategories below.
    for (const cat of CATEGORIES) {
      try {
        await cacheDb.run('DELETE FROM content_categories WHERE category = ?', [cat.name]);
      } catch (err) {
        console.error(`[ContentCache] Failed to clear ${cat.name}:`, err.message);
      }
    }

    let verified = 0, streamable = 0, skippedFresh = 0;

    for (let i = 0; i < queue.length; i++) {
      // Bail out cleanly if the admin paused mid-scan. Already-verified
      // items stay in the cache; the rest of the queue is dropped on the
      // floor — the next scan rebuilds from scratch anyway.
      if (scannerPaused) {
        console.log('[ContentCache] Scan paused mid-queue, exiting early.');
        break;
      }
      const item = queue[i];

      const existing = await cacheDb.get(
        'SELECT last_verified FROM cached_content WHERE tmdb_id = ? AND tmdb_type = ?',
        [item.tmdbId, item.tmdbType]
      );
      const ageMs = existing ? Date.now() - new Date(existing.last_verified).getTime() : Infinity;

      if (ageMs < REVERIFY_AGE_MS) {
        await upsertCategories(item);
        skippedFresh++;
        continue;
      }

      const pos = `(${i + 1}/${queue.length})`;
      console.log(`[ContentCache] Verifying ${pos}: "${item.title}"`);

      const matchKey    = item.tmdbType === 'movie' ? `movie:${item.tmdbId}` : `tv:${item.tmdbId}:s1`;
      const cachedMatch = await licenseGet(
        'SELECT scraper_href, scraper_image, updated_at FROM scraper_match_cache WHERE cache_key = ?',
        [matchKey]
      );

      // Trust the match cache only while it's still fresh. Past the TTL
      // we *force* a real scraper re-check — otherwise a title that was
      // dropped by the source still gets blessed as streamable forever.
      let trustCache = false;
      if (cachedMatch && cachedMatch.scraper_href) {
        const cacheAgeMs = cachedMatch.updated_at
          ? Date.now() - new Date(cachedMatch.updated_at).getTime()
          : Infinity;
        trustCache = Number.isFinite(cacheAgeMs) && cacheAgeMs < MATCH_CACHE_TTL_MS;
      }

      let scraperHref = null, scraperImage = null, isStreamable = false;

      if (trustCache) {
        scraperHref  = cachedMatch.scraper_href;
        scraperImage = cachedMatch.scraper_image;
        isStreamable = true;
      } else {
        const match = await verifyOnScraper(item);
        if (match) {
          scraperHref  = match.href;
          scraperImage = match.image;
          isStreamable = true;
          try { await writeMatchCache(matchKey, scraperHref, scraperImage); } catch {}
        }
        await sleep(VERIFY_DELAY_MS);
      }

      if (isStreamable) {
        console.log(`[ContentCache]   ✓ "${item.title}" — streamable, adding to database`);
        await upsertContent(item, scraperHref, scraperImage);
        await upsertCategories(item);
        streamable++;
      } else {
        console.log(`[ContentCache]   ✗ "${item.title}" — not streamable, removing record`);
        if (existing) await deleteContent(item);
      }

      verified++;

      if (verified % 10 === 0) {
        const remaining = queue.length - skippedFresh - verified;
        console.log(
          `[ContentCache] ── Progress: ${verified} verified, ${streamable} streamable` +
          ` — ${skippedFresh} fresh/skipped, ${remaining} remaining`
        );
      }
    }

    const elapsed = Math.round((Date.now() - t0) / 1000);
    console.log('[ContentCache] ── Scan complete ─────────────────────────────────────');
    console.log(
      `[ContentCache]    Verified: ${verified}  Streamable: ${streamable}` +
      `  Fresh/skipped: ${skippedFresh}  Elapsed: ${elapsed}s`
    );
  } catch (err) {
    console.error('[ContentCache] Scan error:', err.message);
  } finally {
    scanInProgress = false;
  }
}

// ── Enrichment scanner ────────────────────────────────────────────────────────
// Fetches age_rating and keywords for streamable titles that are missing them.
// Runs in parallel with the verify scanner — both can be active simultaneously.

function extractAgeRating(type, data) {
  if (type === 'movie') {
    const results = data.release_dates?.results || [];
    for (const region of ['US', 'GB']) {
      const entry = results.find(r => r.iso_3166_1 === region);
      const cert  = (entry?.release_dates || []).find(d => d.certification)?.certification;
      if (cert) return cert;
    }
  } else {
    const results = data.content_ratings?.results || [];
    for (const region of ['US', 'GB']) {
      const entry = results.find(r => r.iso_3166_1 === region);
      if (entry?.rating) return entry.rating;
    }
  }
  return '';   // empty string = enriched but no rating found; NULL = not yet enriched
}

function extractKeywords(type, data) {
  const kwArray = type === 'movie'
    ? (data.keywords?.keywords || [])
    : (data.keywords?.results  || []);
  return JSON.stringify(kwArray.map(k => k.name.toLowerCase()));
}

async function runEnrichmentScan() {
  if (scannerPaused) return;
  if (enrichInProgress) { console.log('[Enrichment] Already in progress, skipping.'); return; }
  enrichInProgress = true;
  const t0 = Date.now();
  console.log('[Enrichment] ── Scan started ───────────────────────────────────────');

  try {
    // Only target streamable titles that haven't been enriched yet.
    // NULL = not processed; empty string '' = processed but nothing found.
    const titles = await cacheDb.all(`
      SELECT tmdb_id, tmdb_type, title FROM cached_content
      WHERE is_streamable = 1 AND (age_rating IS NULL OR keywords IS NULL)
      ORDER BY vote_average DESC
    `);

    console.log(`[Enrichment] ${titles.length} titles need enrichment`);
    let enriched = 0;

    for (let i = 0; i < titles.length; i++) {
      if (scannerPaused) {
        console.log('[Enrichment] Paused mid-queue, exiting early.');
        break;
      }
      const row = titles[i];
      const pos = `(${i + 1}/${titles.length})`;
      console.log(`[Enrichment] Enriching ${pos}: "${row.title}"`);

      const appendParam = row.tmdb_type === 'movie'
        ? 'release_dates,keywords'
        : 'content_ratings,keywords';

      let data;
      try {
        data = await tmdbFetch(`/${row.tmdb_type}/${row.tmdb_id}`, { append_to_response: appendParam });
      } catch (err) {
        console.error(`[Enrichment]   ✗ TMDB fetch failed for "${row.title}": ${err.message}`);
        await sleep(ENRICH_DELAY_MS);
        continue;
      }

      const ageRating = extractAgeRating(row.tmdb_type, data);
      const keywords  = extractKeywords(row.tmdb_type, data);
      const kwCount   = JSON.parse(keywords).length;

      console.log(`[Enrichment]   ✓ "${row.title}" — rating: ${ageRating || 'none'}, tags: ${kwCount}`);

      await cacheDb.run(
        'UPDATE cached_content SET age_rating = ?, keywords = ? WHERE tmdb_id = ? AND tmdb_type = ?',
        [ageRating, keywords, row.tmdb_id, row.tmdb_type]
      );

      enriched++;

      if (enriched % 20 === 0) {
        const remaining = titles.length - enriched;
        console.log(`[Enrichment] ── Progress: ${enriched} enriched, ${remaining} remaining`);
      }

      await sleep(ENRICH_DELAY_MS);
    }

    const elapsed = Math.round((Date.now() - t0) / 1000);
    console.log('[Enrichment] ── Scan complete ──────────────────────────────────────');
    console.log(`[Enrichment]    Enriched: ${enriched}  Elapsed: ${elapsed}s`);
  } catch (err) {
    console.error('[Enrichment] Scan error:', err.message);
  } finally {
    enrichInProgress = false;
  }
}

// ── Browse query functions ────────────────────────────────────────────────────

function formatRow(row) {
  return {
    tmdbId:      row.tmdb_id,
    tmdbType:    row.tmdb_type,
    title:       row.title,
    year:        row.year,
    posterUrl:   row.poster_url,
    backdropUrl: row.backdrop_url,
    overview:    row.overview,
    genres:      JSON.parse(row.genres   || '[]'),
    keywords:    JSON.parse(row.keywords || '[]'),
    voteAverage: row.vote_average,
    ageRating:   row.age_rating  || null,
    isAnime:     row.is_anime === 1,
  };
}

async function getCategory(category, limit = 20, offset = 0) {
  const rows = await cacheDb.all(`
    SELECT cc.tmdb_id, cc.tmdb_type, cc.title, cc.year, cc.poster_url,
           cc.backdrop_url, cc.overview, cc.genres, cc.keywords,
           cc.vote_average, cc.age_rating, cc.is_anime
    FROM cached_content cc
    JOIN content_categories cat
      ON cc.tmdb_id = cat.tmdb_id AND cc.tmdb_type = cat.tmdb_type
    WHERE cat.category = ? AND cc.is_streamable = 1
    ORDER BY cat.position
    LIMIT ? OFFSET ?
  `, [category, limit, offset]);
  return rows.map(formatRow);
}

async function getByGenre(genre, type, limit = 20, offset = 0) {
  const params = [`%"${genre}"%`];
  const typeClause = type ? 'AND tmdb_type = ?' : '';
  if (type) params.push(type);
  params.push(limit, offset);
  const rows = await cacheDb.all(`
    SELECT tmdb_id, tmdb_type, title, year, poster_url, backdrop_url,
           overview, genres, keywords, vote_average, age_rating, is_anime
    FROM cached_content
    WHERE is_streamable = 1 AND genres LIKE ? ${typeClause}
    ORDER BY vote_average DESC
    LIMIT ? OFFSET ?
  `, params);
  return rows.map(formatRow);
}

async function getGenres(type) {
  const typeClause = type ? 'AND tmdb_type = ?' : '';
  const params     = type ? [type] : [];
  const rows = await cacheDb.all(
    `SELECT genres FROM cached_content WHERE is_streamable = 1 ${typeClause}`, params
  );
  const seen = new Set();
  for (const row of rows) {
    try { for (const g of JSON.parse(row.genres || '[]')) seen.add(g); } catch {}
  }
  return [...seen].sort();
}

// Multi-filter browse — all params are optional and AND-combined.
// type: 'movie' | 'tv' | 'anime'
// sort: 'rating_desc' | 'rating_asc' | 'year_desc' | 'year_asc'
async function getFiltered({ genre, type, yearFrom, yearTo, ageRating, minRating, tag, sort, limit = 20, offset = 0 }) {
  const conditions = ['is_streamable = 1'];
  const params     = [];

  if (genre) {
    conditions.push('genres LIKE ?');
    params.push(`%"${genre}"%`);
  }

  if (type === 'anime') {
    conditions.push('is_anime = 1');
  } else if (type === 'movie') {
    conditions.push("tmdb_type = 'movie' AND is_anime = 0");
  } else if (type === 'tv') {
    conditions.push("tmdb_type = 'tv' AND is_anime = 0");
  }

  if (yearFrom) {
    conditions.push('CAST(year AS INTEGER) >= ?');
    params.push(parseInt(yearFrom, 10));
  }
  if (yearTo) {
    conditions.push('CAST(year AS INTEGER) <= ?');
    params.push(parseInt(yearTo, 10));
  }

  if (ageRating) {
    conditions.push('age_rating = ?');
    params.push(ageRating);
  }

  if (minRating) {
    conditions.push('vote_average >= ?');
    params.push(parseFloat(minRating));
  }

  // tag matches any keyword whose name contains the search string.
  if (tag) {
    conditions.push('keywords LIKE ?');
    params.push(`%${tag.toLowerCase()}%`);
  }

  const ORDER_MAP = {
    rating_desc: 'vote_average DESC, vote_count DESC',
    rating_asc:  'vote_average ASC',
    year_desc:   'year DESC',
    year_asc:    'year ASC',
  };
  const orderBy = ORDER_MAP[sort] || 'vote_average DESC, vote_count DESC';

  params.push(limit, offset);

  const rows = await cacheDb.all(`
    SELECT tmdb_id, tmdb_type, title, year, poster_url, backdrop_url,
           overview, genres, keywords, vote_average, age_rating, is_anime
    FROM cached_content
    WHERE ${conditions.join(' AND ')}
    ORDER BY ${orderBy}
    LIMIT ? OFFSET ?
  `, params);

  return rows.map(formatRow);
}

/**
 * Verified-streamable search.
 *
 * Pipeline:
 *   1. TMDB multi-search for fuzzy ranking (much better than SQLite LIKE).
 *   2. Intersect the results with cached_content WHERE is_streamable = 1.
 *   3. Return only the survivors, in TMDB's original ranking order.
 *
 * Net effect: anything the user types into Browse only ever shows
 * titles the verify scanner has confirmed are actually playable on the
 * scraper sources — no more dead titles surfacing in search.
 *
 * Returns an array of TmdbCard-shaped objects (same shape as
 * /tmdb/search) so the client can drop this in without parser changes.
 */
async function searchStreamable(query, limit = 20) {
  const q = (query || '').trim();
  if (!q) return [];

  let tmdbResults;
  try {
    tmdbResults = await tmdb.searchTitles(q);
  } catch (err) {
    console.error('[searchStreamable] TMDB search failed:', err.message);
    return [];
  }
  const cards = tmdbResults.results || [];
  if (!cards.length) return [];

  // Single bulk lookup keyed by tmdb_id (PRIMARY KEY) — much faster than
  // one round-trip per card. We post-filter on tmdb_type because the same
  // numeric id can appear under both "movie" and "tv" namespaces.
  const ids = cards.map(c => c.tmdbId);
  const placeholders = ids.map(() => '?').join(',');
  let rows = [];
  try {
    rows = await cacheDb.all(
      `SELECT tmdb_id, tmdb_type FROM cached_content
       WHERE is_streamable = 1 AND tmdb_id IN (${placeholders})`,
      ids
    );
  } catch (err) {
    console.error('[searchStreamable] cache query failed:', err.message);
    return [];
  }

  const verifiedKeys = new Set(rows.map(r => `${r.tmdb_id}:${r.tmdb_type}`));
  return cards
    .filter(c => verifiedKeys.has(`${c.tmdbId}:${c.type}`))
    .slice(0, limit);
}

/**
 * Self-warming cache entry — invoked by the client whenever the scraper
 * bridge has successfully resolved a stream for a title.
 *
 * Idempotent: if the title is already in `cached_content` we no-op
 * cheaply (no TMDB call, no scraper call, ~1ms). Otherwise we fetch
 * TMDB metadata + age-rating + keywords in one go, write a row with
 * `is_streamable = 1`, and seed the scraper-match cache so the verify
 * scanner skips this title on the next pass.
 *
 * Net effect: a title the user found via the TMDB-fallback search and
 * actually managed to play, surfaces in *cache-backed* search next
 * time — no more falling through to the fallback.
 *
 * Fire-and-forget on the client; this returns nothing.
 */
async function registerVerified(tmdbId, tmdbType, scraperHref, scraperImage) {
  const numericId = parseInt(tmdbId, 10);
  if (!Number.isFinite(numericId) || numericId <= 0) return;
  if (tmdbType !== 'tv' && tmdbType !== 'movie')      return;

  const existing = await cacheDb.get(
    'SELECT 1 FROM cached_content WHERE tmdb_id = ? AND tmdb_type = ?',
    [numericId, tmdbType]
  );
  if (existing) return;

  // Need a fresh TMDB pull because we don't have the full metadata
  // (overview, genres, original_language, …) at the call site. The
  // append_to_response saves a round-trip on enrichment.
  const append = tmdbType === 'tv'
    ? 'content_ratings,keywords'
    : 'release_dates,keywords';
  let data;
  try {
    data = await tmdbFetch(`/${tmdbType}/${numericId}`, { append_to_response: append });
  } catch (err) {
    console.error('[registerVerified] TMDB fetch failed:', err.message);
    return;
  }

  const isTv      = tmdbType === 'tv';
  const title     = (isTv ? data.name          : data.title)          || '';
  const origTitle = (isTv ? data.original_name  : data.original_title) || title;
  const dateStr   = (isTv ? data.first_air_date : data.release_date)   || '';
  const year      = dateStr.slice(0, 4) || null;
  const genreList = data.genres || [];
  const genres    = genreList.map(g => g.name);
  const genreIds  = genreList.map(g => g.id);
  const origLang  = data.original_language || 'en';
  // Match `normaliseItem`'s rule (and the client bridge's `isAnimeTitle`):
  // the Animation genre alone routes to Animekai. Keeps verify, self-warm
  // and bridge in lockstep.
  const isAnime   = genreIds.includes(16);

  const item = {
    tmdbId: numericId, tmdbType,
    title, originalTitle: origTitle, year,
    posterUrl:   data.poster_path   ? `${IMG_POSTER}${data.poster_path}`     : '',
    backdropUrl: data.backdrop_path ? `${IMG_BACKDROP}${data.backdrop_path}` : '',
    overview: data.overview || '',
    genres,
    voteAverage: data.vote_average || 0,
    voteCount:   data.vote_count   || 0,
    isAnime,
  };

  try {
    await upsertContent(item, scraperHref || null, scraperImage || null);

    // Inline enrichment — we already paid for the TMDB call, no reason
    // to leave this title pending for the enrichment scanner.
    const ageRating = extractAgeRating(tmdbType, data);
    const keywords  = extractKeywords(tmdbType, data);
    await cacheDb.run(
      'UPDATE cached_content SET age_rating = ?, keywords = ? WHERE tmdb_id = ? AND tmdb_type = ?',
      [ageRating, keywords, numericId, tmdbType]
    );

    // Seed the scraper-match cache so the next verify scan skips this
    // title (we already know it streams).
    if (scraperHref) {
      const matchKey = isTv ? `tv:${numericId}:s1` : `movie:${numericId}`;
      try {
        await writeMatchCache(matchKey, scraperHref, scraperImage || '');
      } catch (e) { /* non-fatal */ }
    }

    console.log(`[ContentCache] User-verified: "${title}" added to cache.`);
  } catch (err) {
    console.error('[registerVerified] DB write failed:', err.message);
  }
}

async function getScanStatus() {
  const total      = await cacheDb.get('SELECT COUNT(*) AS c FROM cached_content');
  const streamable = await cacheDb.get('SELECT COUNT(*) AS c FROM cached_content WHERE is_streamable = 1');
  const enriched   = await cacheDb.get(`
    SELECT COUNT(*) AS c FROM cached_content
    WHERE is_streamable = 1 AND age_rating IS NOT NULL AND keywords IS NOT NULL
  `);
  const cats = await cacheDb.get('SELECT COUNT(*) AS c FROM content_categories');
  return {
    paused:           scannerPaused,
    scanInProgress,
    enrichInProgress,
    totalCached:      total?.c      || 0,
    totalStreamable:  streamable?.c || 0,
    totalEnriched:    enriched?.c   || 0,
    categoryEntries:  cats?.c       || 0,
  };
}

/**
 * Admin-controlled pause toggle. Setting `true` makes both scan loops
 * idle through their cooldown without picking up new work; in-flight
 * scans bail out of their per-item loop early. Setting back to `false`
 * resumes immediately on the next loop tick (≤ SCAN_LOOP_COOLDOWN_MS).
 */
function setScannerPaused(value) {
  scannerPaused = !!value;
  console.log(`[ContentCache] Scanner ${scannerPaused ? 'PAUSED' : 'RESUMED'} by admin.`);
  return scannerPaused;
}

function isScannerPaused() {
  return scannerPaused;
}

/**
 * Paginated browse over the cached_content table. Used by the admin
 * page's cache browser. `query` is an optional case-insensitive title
 * substring; `streamableOnly` defaults to true (matches what the app
 * actually surfaces). Sort defaults to most-recently-verified first.
 */
async function listCached({ query, streamableOnly = true, type, page = 1, limit = 50 } = {}) {
  const conditions = [];
  const params     = [];
  if (streamableOnly) conditions.push('is_streamable = 1');
  if (query) {
    conditions.push('LOWER(title) LIKE ?');
    params.push(`%${String(query).toLowerCase()}%`);
  }
  if (type === 'movie' || type === 'tv') {
    conditions.push('tmdb_type = ?');
    params.push(type);
  }

  const where = conditions.length ? `WHERE ${conditions.join(' AND ')}` : '';
  const offset = Math.max(0, (page - 1) * limit);

  const totalRow = await cacheDb.get(
    `SELECT COUNT(*) AS c FROM cached_content ${where}`, params
  );
  params.push(limit, offset);
  const rows = await cacheDb.all(`
    SELECT tmdb_id, tmdb_type, title, year, poster_url, vote_average,
           is_anime, is_streamable, last_verified, age_rating
    FROM cached_content ${where}
    ORDER BY last_verified DESC
    LIMIT ? OFFSET ?
  `, params);

  return {
    total: totalRow?.c || 0,
    page,
    limit,
    items: rows.map(r => ({
      tmdbId:       r.tmdb_id,
      tmdbType:     r.tmdb_type,
      title:        r.title,
      year:         r.year,
      posterUrl:    r.poster_url,
      voteAverage:  r.vote_average,
      isAnime:      r.is_anime === 1,
      isStreamable: r.is_streamable === 1,
      ageRating:    r.age_rating || null,
      lastVerified: r.last_verified,
    })),
  };
}

// ── Entry point ───────────────────────────────────────────────────────────────

/**
 * Continuous-mode loop: chains runScan() calls back-to-back so the
 * verifier is effectively "always on". When a scan finishes (whether it
 * actually verified anything or just rebuilt the queue and skipped fresh
 * items), we wait SCAN_LOOP_COOLDOWN_MS and immediately start the next
 * one. The whole `scanInProgress` guard inside runScan still protects
 * against accidental reentry.
 */
async function scanLoop() {
  // Brief boot delay so the rest of the server has settled before we
  // start hammering TMDB / the scraper.
  await sleep(SCAN_BOOT_DELAY_MS);
  while (true) {
    try {
      await runScan();
    } catch (err) {
      console.error('[ContentCache] Scan loop error:', err.message);
    }
    await sleep(SCAN_LOOP_COOLDOWN_MS);
  }
}

/**
 * Same shape for enrichment — auto-quiesces (the SQL query returns 0
 * rows) when there's nothing left to enrich, so the loop is cheap when
 * idle and immediately picks up new candidates produced by the verify
 * scanner the moment they arrive.
 */
async function enrichLoop() {
  await sleep(ENRICH_BOOT_DELAY_MS);
  while (true) {
    try {
      await runEnrichmentScan();
    } catch (err) {
      console.error('[Enrichment] Loop error:', err.message);
    }
    await sleep(ENRICH_LOOP_COOLDOWN_MS);
  }
}

function start() {
  const scanBootSec   = SCAN_BOOT_DELAY_MS    / 1000;
  const enrichBootSec = ENRICH_BOOT_DELAY_MS  / 1000;
  const scanCdSec     = SCAN_LOOP_COOLDOWN_MS / 1000;
  const enrichCdMin   = ENRICH_LOOP_COOLDOWN_MS / 60_000;

  console.log(
    `[ContentCache] Continuous mode — first scan in ${scanBootSec}s, ` +
    `then chained with ${scanCdSec}s cooldown between cycles.`
  );
  console.log(
    `[Enrichment]   Continuous mode — first run in ${enrichBootSec}s, ` +
    `then chained with ${enrichCdMin}min cooldown.`
  );

  // Fire-and-forget — both loops run forever. Caught errors inside each
  // loop's try/catch keep the loop alive across transient failures.
  scanLoop().catch(err => console.error('[ContentCache] scanLoop crashed:', err));
  enrichLoop().catch(err => console.error('[Enrichment] enrichLoop crashed:', err));
}

module.exports = {
  start,
  getCategory,
  getByGenre,
  getGenres,
  getFiltered,
  getScanStatus,
  searchStreamable,
  registerVerified,
  setScannerPaused,
  isScannerPaused,
  listCached,
};
