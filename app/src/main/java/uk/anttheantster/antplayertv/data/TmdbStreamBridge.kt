package uk.anttheantster.antplayertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.anttheantster.antplayertv.model.MediaItem

/**
 * Bridges TMDB metadata (a title + season + episode) to the scraper
 * back-end (Animekai / 1movies / etc) so the user can actually play
 * something.
 *
 * Successful TMDB→scraper matches are stored server-side so every user
 * benefits from every other user's resolved match. Cache keys follow:
 *   "movie:<tmdbId>"         – movies
 *   "tv:<tmdbId>:s<season>"  – TV (per-season, since anime sequels are
 *                              separate pages on Animekai)
 *
 * Pickbest is a scored matcher: it prefers exact title equality,
 * boosts entries whose title contains a season-N marker, disambiguates
 * by year for remake collisions, and refuses to return a candidate
 * whose score is below an empirical "this is a real match" threshold.
 */
class TmdbStreamBridge(
    private val scraperApi: RemoteSearchApi,
) {

    sealed class Options {
        data class Resolved(
            val match: AshiSearchResult,
            val episodeNumber: Int,
            val totalEpisodes: Int,
            val streams: List<StreamOption>,
            val isMovie: Boolean,
            val title: String,
            val year: String?,
            val tmdbId: Int?,
            /** TMDB season; null for movies. */
            val tmdbSeason: Int? = null,
        ) : Options()
        data class Failure(val reason: String) : Options()
    }

    /** Resolve a movie. */
    suspend fun resolveMovieOptions(
        title: String,
        year: String?,
        tmdbId: Int? = null,
        isAnime: Boolean = false,
        originalTitle: String? = null,
    ): Options = withContext(Dispatchers.IO) {
        val cacheKey = tmdbId?.let { "movie:$it" }
        val seriesMatch = resolveSeriesHref(
            cacheKey = cacheKey,
            isAnime = isAnime,
            buildQueries = {
                buildList {
                    add(title)
                    if (!originalTitle.isNullOrBlank() && originalTitle != title) add(originalTitle)
                    if (year != null) add("$title $year")
                }
            },
            pickMatch = { candidates ->
                pickBest(title, year, season = null, candidates = candidates)
            },
        ) ?: return@withContext Options.Failure(
            "Couldn't find \"$title\" on the matching source."
        )

        // The seriesMatch.href is a human-facing /movie/… page URL.
        // The scraper's /stream extractor needs the
        // /ajax/links/list?eid=… form, which we get by going through
        // /episodes first. For a single-entry movie page, /episodes
        // returns one "episode" — that IS the movie. Without this hop
        // /stream sees a movie page, response.json() chokes on the HTML,
        // and we'd see the "1Movies fetch error: SyntaxError: Unexpected
        // token <" sentinel come back instead of a stream URL.
        val episodes = scraperApi.getEpisodes(seriesMatch.href)
        val streamableHref = episodes.firstOrNull { it.href.isNotBlank() }?.href
        if (streamableHref.isNullOrBlank()) {
            // Stale cache entry — evict so the next attempt does a fresh search.
            if (cacheKey != null) scraperApi.evictMatchCache(cacheKey)
            return@withContext Options.Failure(
                "Found \"${seriesMatch.title}\" but couldn't resolve a stream URL." +
                    if (cacheKey != null) " (Cache cleared — please try again.)" else ""
            )
        }

        val streams = scraperApi.getStreamOptions(streamableHref)
            .filter { it.url.startsWith("http") }
        if (streams.isEmpty()) {
            return@withContext Options.Failure(
                "Found \"${seriesMatch.title}\" but no playable stream."
            )
        }

        Options.Resolved(
            match = seriesMatch.copy(href = streamableHref),
            episodeNumber = 1,
            totalEpisodes = 1,
            streams = streams,
            isMovie = true,
            title = title,
            year = year,
            tmdbId = tmdbId,
        )
    }

    /** Resolve a TV episode. */
    suspend fun resolveEpisodeOptions(
        title: String,
        seasonNumber: Int,
        episodeNumber: Int,
        tmdbId: Int? = null,
        isAnime: Boolean = false,
        year: String? = null,
        originalTitle: String? = null,
    ): Options = withContext(Dispatchers.IO) {
        val cacheKey = tmdbId?.let { "tv:$it:s$seasonNumber" }
        val seriesMatch = resolveSeriesHref(
            cacheKey = cacheKey,
            isAnime = isAnime,
            buildQueries = {
                buildList {
                    add(title)
                    if (!originalTitle.isNullOrBlank() && originalTitle != title) add(originalTitle)
                    if (seasonNumber > 1) {
                        add("$title $seasonNumber")
                        add("$title Season $seasonNumber")
                        if (!originalTitle.isNullOrBlank() && originalTitle != title) {
                            add("$originalTitle $seasonNumber")
                        }
                    }
                }
            },
            pickMatch = { candidates ->
                pickBest(title, year, season = seasonNumber, candidates = candidates)
            },
        ) ?: return@withContext Options.Failure(
            "Couldn't find \"$title\" on the matching source."
        )

        val episodes = scraperApi.getEpisodes(seriesMatch.href)
        if (episodes.isEmpty()) {
            // Stale cache entry — evict it so the next attempt does a fresh search.
            if (cacheKey != null) scraperApi.evictMatchCache(cacheKey)
            return@withContext Options.Failure(
                "Found \"${seriesMatch.title}\" but its episode list is empty." +
                    if (cacheKey != null) " (Cache cleared — please try again.)" else ""
            )
        }

        // For Animekai each season is its own page so episodes are always
        // numbered from 1 — match by number only.
        // For 1Movies a single page covers all seasons so we match by
        // (season, number). Fall back to number-only if the HTML had no
        // season markers (all episodes would carry season=1).
        val isAnimekai = seriesMatch.href.startsWith("Animekai:", ignoreCase = true)
        val ep = if (isAnimekai || episodes.none { it.season != null }) {
            episodes.firstOrNull { it.number == episodeNumber }
        } else {
            episodes.firstOrNull { it.season == seasonNumber && it.number == episodeNumber }
        } ?: return@withContext Options.Failure(
                "Found \"${seriesMatch.title}\" but it has no episode $episodeNumber" +
                    " in season $seasonNumber."
            )

        val streams = scraperApi.getStreamOptions(ep.href)
            .filter { it.url.startsWith("http") }
        if (streams.isEmpty()) {
            return@withContext Options.Failure(
                "Episode $episodeNumber found but no playable stream."
            )
        }

        // IMPORTANT: keep seriesMatch.href intact (the series page URL).
        // The player's auto-play system reads MediaItem.id to extract this
        // as the seriesId and calls getEpisodes(seriesId). Replacing it with
        // ep.href here would cause auto-play to call getEpisodes on an
        // episode URL and get nothing back.
        Options.Resolved(
            match = seriesMatch,
            episodeNumber = ep.number,
            totalEpisodes = episodes.size,
            streams = streams,
            isMovie = false,
            title = title,
            year = year,
            tmdbId = tmdbId,
            tmdbSeason = seasonNumber,
        )
    }

    /**
     * Shared logic: check server cache → search → pick best → store in cache.
     * Returns null when nothing could be resolved (caller reports the failure).
     */
    private suspend fun resolveSeriesHref(
        cacheKey: String?,
        isAnime: Boolean,
        buildQueries: () -> List<String>,
        pickMatch: (List<AshiSearchResult>) -> AshiSearchResult?,
    ): AshiSearchResult? {
        // Cache hit: skip the fuzzy search entirely.
        if (cacheKey != null) {
            val cached = scraperApi.getMatchCache(cacheKey)
            if (cached != null && cached.href.isNotBlank()) {
                return AshiSearchResult(href = cached.href, image = cached.image, title = "")
            }
        }

        val queries = buildQueries()
        val candidates = gatherCandidates(queries, isAnime)
        if (candidates.isEmpty()) return null

        val match = pickMatch(candidates) ?: return null

        // Persist server-side so all future plays (by any user) are instant.
        if (cacheKey != null) {
            scraperApi.putMatchCache(cacheKey, match.href, match.image)
        }
        return match
    }

    /**
     * Build the final playable [MediaItem] from a resolved [Options.Resolved]
     * + the user's chosen [StreamOption].
     */
    fun finishWithStream(opts: Options.Resolved, pick: StreamOption): MediaItem {
        val seriesHref = opts.match.href
        return MediaItem(
            id = "$seriesHref#ep${opts.episodeNumber}#${pick.label}",
            title = if (opts.isMovie) opts.title
                    else "${opts.title} - Ep ${opts.episodeNumber} (${pick.label})",
            description = "",
            image = opts.match.image,
            streamUrl = pick.url,
            releaseYear = opts.year,
            totalEpisodes = opts.totalEpisodes,
            type = if (opts.isMovie) "Movie" else "TV",
            ageRating = null,
            tmdbId = opts.tmdbId,
            tmdbType = if (opts.tmdbId != null) {
                if (opts.isMovie) "movie" else "tv"
            } else null,
            tmdbSeason = if (opts.isMovie) null else opts.tmdbSeason,
            tmdbEpisode = if (opts.isMovie) null else opts.episodeNumber,
        )
    }

    /**
     * Run each query, source-filter, dedupe by href, return everything we
     * found. Letting [pickBest] choose across the union is more robust than
     * picking inside each query's results.
     */
    private suspend fun gatherCandidates(
        queries: List<String>,
        isAnime: Boolean,
    ): List<AshiSearchResult> {
        val seen = HashSet<String>()
        val out = ArrayList<AshiSearchResult>()
        for (q in queries) {
            val raw = scraperApi.search(q)
            val filtered = filterBySource(raw, isAnime)
            for (r in filtered) {
                if (seen.add(r.href)) out.add(r)
            }
        }
        return out
    }

    private fun filterBySource(
        results: List<AshiSearchResult>,
        isAnime: Boolean,
    ): List<AshiSearchResult> {
        return if (isAnime) {
            results.filter { it.href.startsWith("Animekai:", ignoreCase = true) }
        } else {
            results.filterNot { it.href.startsWith("Animekai:", ignoreCase = true) }
        }
    }

    /* ---------- Scored matcher ---------- */

    private fun pickBest(
        title: String,
        year: String?,
        season: Int?,
        candidates: List<AshiSearchResult>,
    ): AshiSearchResult? {
        if (candidates.isEmpty()) return null
        val scored = candidates.map { it to scoreCandidate(title, year, season, it) }
        val best = scored.maxByOrNull { it.second } ?: return null
        return if (best.second >= 250) best.first else null
    }

    private fun scoreCandidate(
        target: String,
        year: String?,
        season: Int?,
        candidate: AshiSearchResult,
    ): Int {
        val t = target.trim().lowercase()
        val rawC = candidate.title.trim().lowercase()
        if (t.isBlank() || rawC.isBlank()) return 0

        val cStripped = rawC
            .replace(Regex("\\s*\\([^)]*\\)\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        var score = when {
            rawC == t || cStripped == t -> 1000
            cStripped.startsWith("$t ") || cStripped.startsWith("$t:") -> 800
            cStripped.startsWith(t) -> 700
            else -> {
                val tWords = t.split(Regex("\\s+")).filter { it.length >= 2 }
                val cWords = cStripped.split(Regex("[\\s\\-:!?]+"))
                    .filter { it.length >= 2 }
                if (tWords.isEmpty() || cWords.isEmpty()) 0
                else {
                    val matches = tWords.count { tw -> cWords.any { it.startsWith(tw) } }
                    val ratio = matches.toDouble() / tWords.size
                    (ratio * 500).toInt()
                }
            }
        }

        if (year != null && rawC.contains(year)) score += 80

        if (season != null) {
            score += seasonAdjustment(rawC, season)
        }

        return score
    }

    private fun seasonAdjustment(candidateLower: String, requestedSeason: Int): Int {
        val seasonNameRe = Regex("\\bseason\\s+$requestedSeason\\b")
        val trailingNumRe = Regex("(?:^|[\\s:!\\-])$requestedSeason\\b\\s*$")
        val anyTrailingNumRe = Regex("(?:^|[\\s:!\\-])(\\d+)\\b\\s*$")
        val anySeasonNameRe = Regex("\\bseason\\s+(\\d+)\\b")

        if (seasonNameRe.containsMatchIn(candidateLower)) return 250
        if (trailingNumRe.containsMatchIn(candidateLower)) return 200

        anySeasonNameRe.find(candidateLower)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
            if (n != requestedSeason) return -200
        }
        anyTrailingNumRe.find(candidateLower)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
            if (n in 2..9 && n != requestedSeason) return -150
        }

        return if (requestedSeason == 1) 0 else -25
    }
}
