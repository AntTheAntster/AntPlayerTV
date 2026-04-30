package uk.anttheantster.antplayertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/* ---------- Data classes ---------- */

/** A single browse / search hit returned from /tmdb/search. */
data class TmdbCard(
    val tmdbId: Int,
    val type: String,           // "tv" or "movie"
    val title: String,
    val year: String?,          // e.g. "2008"
    val posterUrl: String,
    val backdropUrl: String,
    val rating: Double?,        // 0..10, nullable when TMDB has no votes
    val ratingCount: Int,
    val synopsis: String,
)

/** Per-season summary returned inside [TmdbTitle]. */
data class TmdbSeasonSummary(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val airDate: String?,       // ISO (yyyy-mm-dd) or null
    val posterUrl: String,
)

/** Full title — drives the Details screen. */
data class TmdbTitle(
    val tmdbId: Int,
    val imdbId: String?,
    val type: String,
    val title: String,
    val originalTitle: String,
    /** ISO 639-1 code, e.g. "en", "ja", "ko". Used to route streams. */
    val originalLanguage: String?,
    val year: String?,
    val yearRange: String,      // "2008–2013" / "2020–" / "" if unknown
    val posterUrl: String,
    val backdropUrl: String,
    val rating: Double?,
    val ratingCount: Int,
    val ageRating: String,      // "TV-MA", "PG-13", "" if missing
    val synopsis: String,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val numberOfSeasons: Int,
    val numberOfEpisodes: Int,
    val inProduction: Boolean,
    val seasons: List<TmdbSeasonSummary>,
)

/** One episode inside a fetched season. */
data class TmdbEpisode(
    val episodeNumber: Int,
    val name: String,
    val synopsis: String,
    val stillUrl: String,
    val airDate: String?,
    val runtimeMinutes: Int?,
    val rating: Double?,
)

/** Result of /tmdb/season/:id/:season. */
data class TmdbSeason(
    val seasonNumber: Int,
    val name: String,
    val synopsis: String,
    val episodes: List<TmdbEpisode>,
)

/** Item returned from the server's browse cache API. */
data class BrowseCard(
    val tmdbId: Int,
    val tmdbType: String,      // "tv" or "movie"
    val title: String,
    val year: String?,
    val posterUrl: String,
    val backdropUrl: String,
    val overview: String,
    val genres: List<String>,
    val keywords: List<String>,
    val voteAverage: Double?,
    val ageRating: String,
    val isAnime: Boolean,
)

fun BrowseCard.toTmdbCard() = TmdbCard(
    tmdbId    = tmdbId,
    type      = tmdbType,
    title     = title,
    year      = year,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    rating    = voteAverage,
    ratingCount = 0,
    synopsis  = overview,
)

/* ---------- Client ---------- */

class TmdbApi(
    private val baseUrl: String,
) {
    private val client = OkHttpClient()

    /** Multi-search across movies + TV (no streamability filter). */
    suspend fun search(query: String): List<TmdbCard> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("$baseUrl/tmdb/search?query=$encoded") ?: return@withContext emptyList()

        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyList()
        val arr = obj.optJSONArray("results") ?: return@withContext emptyList()
        parseCards(arr)
    }

    /**
     * Verified-streamable search. Hits the server's `/api/browse/search`
     * endpoint, which runs the same TMDB fuzzy search but post-filters
     * the results against the cache of titles the verify scanner has
     * confirmed are actually streamable. Use this instead of [search] in
     * Browse so the user never sees a "dead title" they can't play.
     */
    suspend fun searchStreamable(query: String, limit: Int = 20): List<TmdbCard> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/api/browse/search?query=$encoded&limit=$limit"
            val body = httpGet(url) ?: return@withContext emptyList()
            val obj = runCatching { JSONObject(body) }.getOrNull()
                ?: return@withContext emptyList()
            val arr = obj.optJSONArray("results") ?: return@withContext emptyList()
            parseCards(arr)
        }

    /** Full title details. [type] must be "tv" or "movie". */
    suspend fun getTitle(type: String, tmdbId: Int): TmdbTitle? = withContext(Dispatchers.IO) {
        val body = httpGet("$baseUrl/tmdb/title/$type/$tmdbId") ?: return@withContext null
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        if (obj.has("error")) return@withContext null
        parseTitle(obj)
    }

    /** Episode list for a given TV season. */
    suspend fun getSeason(tmdbId: Int, seasonNumber: Int): TmdbSeason? = withContext(Dispatchers.IO) {
        val body = httpGet("$baseUrl/tmdb/season/$tmdbId/$seasonNumber") ?: return@withContext null
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        if (obj.has("error")) return@withContext null
        parseSeason(obj)
    }

    /* ---------- Internals ---------- */

    private fun httpGet(url: String): String? {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseCards(arr: JSONArray): List<TmdbCard> {
        val out = ArrayList<TmdbCard>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                TmdbCard(
                    tmdbId = o.optInt("tmdbId"),
                    type = o.optString("type", "movie"),
                    title = o.optString("title", ""),
                    year = o.optString("year").takeIf { it.isNotBlank() && it != "null" },
                    posterUrl = o.optString("posterUrl", ""),
                    backdropUrl = o.optString("backdropUrl", ""),
                    rating = if (o.isNull("rating")) null else o.optDouble("rating"),
                    ratingCount = o.optInt("ratingCount", 0),
                    synopsis = o.optString("synopsis", ""),
                )
            )
        }
        return out
    }

    private fun parseTitle(o: JSONObject): TmdbTitle {
        val seasonsArr = o.optJSONArray("seasons") ?: JSONArray()
        val seasons = ArrayList<TmdbSeasonSummary>(seasonsArr.length())
        for (i in 0 until seasonsArr.length()) {
            val s = seasonsArr.optJSONObject(i) ?: continue
            seasons.add(
                TmdbSeasonSummary(
                    seasonNumber = s.optInt("seasonNumber"),
                    name = s.optString("name", "Season ${s.optInt("seasonNumber")}"),
                    episodeCount = s.optInt("episodeCount", 0),
                    airDate = s.optString("airDate").takeIf { it.isNotBlank() && it != "null" },
                    posterUrl = s.optString("posterUrl", ""),
                )
            )
        }
        val genresArr = o.optJSONArray("genres") ?: JSONArray()
        val genres = ArrayList<String>(genresArr.length())
        for (i in 0 until genresArr.length()) {
            genresArr.optString(i)?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
        }
        return TmdbTitle(
            tmdbId = o.optInt("tmdbId"),
            imdbId = o.optString("imdbId").takeIf { it.isNotBlank() && it != "null" },
            type = o.optString("type", "movie"),
            title = o.optString("title", ""),
            originalTitle = o.optString("originalTitle", ""),
            originalLanguage = o.optString("originalLanguage")
                .takeIf { it.isNotBlank() && it != "null" },
            year = o.optString("year").takeIf { it.isNotBlank() && it != "null" },
            yearRange = o.optString("yearRange", ""),
            posterUrl = o.optString("posterUrl", ""),
            backdropUrl = o.optString("backdropUrl", ""),
            rating = if (o.isNull("rating")) null else o.optDouble("rating"),
            ratingCount = o.optInt("ratingCount", 0),
            ageRating = o.optString("ageRating", ""),
            synopsis = o.optString("synopsis", ""),
            genres = genres,
            runtimeMinutes = if (o.isNull("runtimeMinutes")) null else o.optInt("runtimeMinutes"),
            numberOfSeasons = o.optInt("numberOfSeasons", 0),
            numberOfEpisodes = o.optInt("numberOfEpisodes", 0),
            inProduction = o.optBoolean("inProduction", false),
            seasons = seasons,
        )
    }

    private fun parseSeason(o: JSONObject): TmdbSeason {
        val arr = o.optJSONArray("episodes") ?: JSONArray()
        val episodes = ArrayList<TmdbEpisode>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            episodes.add(
                TmdbEpisode(
                    episodeNumber = e.optInt("episodeNumber"),
                    name = e.optString("name", "Episode ${e.optInt("episodeNumber")}"),
                    synopsis = e.optString("synopsis", ""),
                    stillUrl = e.optString("stillUrl", ""),
                    airDate = e.optString("airDate").takeIf { it.isNotBlank() && it != "null" },
                    runtimeMinutes = if (e.isNull("runtimeMinutes")) null else e.optInt("runtimeMinutes"),
                    rating = if (e.isNull("rating")) null else e.optDouble("rating"),
                )
            )
        }
        return TmdbSeason(
            seasonNumber = o.optInt("seasonNumber"),
            name = o.optString("name", "Season ${o.optInt("seasonNumber")}"),
            synopsis = o.optString("synopsis", ""),
            episodes = episodes,
        )
    }
}
