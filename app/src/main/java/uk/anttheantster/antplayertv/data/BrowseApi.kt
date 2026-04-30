package uk.anttheantster.antplayertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class BrowseApi(private val baseUrl: String) {
    private val client = OkHttpClient()

    suspend fun getPopular(type: String? = null, limit: Int = 20): List<BrowseCard> =
        fetchCards("$baseUrl/api/browse/popular", type, limit)

    suspend fun getTrending(type: String? = null, limit: Int = 20): List<BrowseCard> =
        fetchCards("$baseUrl/api/browse/trending", type, limit)

    suspend fun getNew(type: String? = null, limit: Int = 20): List<BrowseCard> =
        fetchCards("$baseUrl/api/browse/new", type, limit)

    suspend fun getTopRated(type: String? = null, limit: Int = 20): List<BrowseCard> =
        fetchCards("$baseUrl/api/browse/top-rated", type, limit)

    suspend fun getGenres(type: String? = null): List<String> = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/api/browse/genres")
            if (type != null) append("?type=$type")
        }
        val body = httpGet(url) ?: return@withContext emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    suspend fun getFilter(
        type: String? = null,
        genre: String? = null,
        yearFrom: Int? = null,
        yearTo: Int? = null,
        ageRating: String? = null,
        minRating: Double? = null,
        tag: String? = null,
        sort: String = "rating_desc",
        page: Int = 1,
        limit: Int = 30,
    ): List<BrowseCard> = withContext(Dispatchers.IO) {
        val params = buildList {
            if (type != null)      add("type=$type")
            if (genre != null)     add("genre=${URLEncoder.encode(genre, "UTF-8")}")
            if (yearFrom != null)  add("year_from=$yearFrom")
            if (yearTo != null)    add("year_to=$yearTo")
            if (ageRating != null) add("age_rating=${URLEncoder.encode(ageRating, "UTF-8")}")
            if (minRating != null) add("min_rating=$minRating")
            if (tag != null)       add("tag=${URLEncoder.encode(tag, "UTF-8")}")
            add("sort=$sort")
            add("page=$page")
            add("limit=$limit")
        }
        val url = "$baseUrl/api/browse/filter?${params.joinToString("&")}"
        val body = httpGet(url) ?: return@withContext emptyList()
        parseCards(body)
    }

    /**
     * Self-warming cache. Call after a successful scraper resolution so
     * the title surfaces in cache-backed search next time. Idempotent
     * server-side; safe to fire on every successful play.
     */
    suspend fun registerVerified(
        tmdbId: Int,
        tmdbType: String,
        scraperHref: String?,
        scraperImage: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("tmdbId", tmdbId)
            put("tmdbType", tmdbType)
            if (!scraperHref.isNullOrBlank())  put("scraperHref",  scraperHref)
            if (!scraperImage.isNullOrBlank()) put("scraperImage", scraperImage)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/browse/cache")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private suspend fun fetchCards(endpoint: String, type: String?, limit: Int): List<BrowseCard> =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(endpoint)
                append("?limit=$limit")
                if (type != null) append("&type=$type")
            }
            val body = httpGet(url) ?: return@withContext emptyList()
            parseCards(body)
        }

    private fun httpGet(url: String): String? = try {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun parseCards(body: String): List<BrowseCard> {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<BrowseCard>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val genresArr  = o.optJSONArray("genres")   ?: JSONArray()
            val keywordsArr = o.optJSONArray("keywords") ?: JSONArray()
            out.add(
                BrowseCard(
                    tmdbId      = o.optInt("tmdbId"),
                    tmdbType    = o.optString("tmdbType", "movie"),
                    title       = o.optString("title", ""),
                    year        = o.optString("year").takeIf { it.isNotBlank() && it != "null" },
                    posterUrl   = o.optString("posterUrl", ""),
                    backdropUrl = o.optString("backdropUrl", ""),
                    overview    = o.optString("overview", ""),
                    genres      = (0 until genresArr.length()).mapNotNull {
                        genresArr.optString(it).takeIf { s -> s.isNotBlank() }
                    },
                    keywords    = (0 until keywordsArr.length()).mapNotNull {
                        keywordsArr.optString(it).takeIf { s -> s.isNotBlank() }
                    },
                    voteAverage = if (o.isNull("voteAverage")) null else o.optDouble("voteAverage"),
                    ageRating   = o.optString("ageRating", ""),
                    isAnime     = o.optBoolean("isAnime", false),
                )
            )
        }
        return out
    }
}
