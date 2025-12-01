package uk.anttheantster.antplayertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// One item from /search (what your Node server returns)
data class AshiSearchResult(
    val href: String,
    val image: String,
    val title: String
)

class RemoteSearchApi(
    private val baseUrl: String
) {
    private val client = OkHttpClient()

    // Call /search?query=...
    suspend fun search(query: String): List<AshiSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/search?query=$encoded"

        val request = Request.Builder()
            .url(url)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = JSONArray(body)

                val results = mutableListOf<AshiSearchResult>()
                for (i in 0 until jsonArray.length()) {
                    val obj: JSONObject = jsonArray.getJSONObject(i)
                    val href = obj.optString("href", "")
                    val image = obj.optString("image", "")
                    val title = obj.optString("title", "")

                    // Skip useless entries
                    if (href.isNotBlank()) {
                        results.add(
                            AshiSearchResult(
                                href = href,
                                image = image,
                                title = title
                            )
                        )
                    }
                }
                return@withContext results
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    suspend fun getDetails(url: String): AshiDetails? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null

        val encoded = URLEncoder.encode(url, "UTF-8")
        val fullUrl = "$baseUrl/details?url=$encoded"

        val request = Request.Builder().url(fullUrl).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val obj = JSONObject(body)

                return@withContext AshiDetails(
                    description = obj.optString("description", "Unknown"),
                    aliases = obj.optString("aliases", "Unknown"),
                    airdate = obj.optString("airdate", "Unknown")
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun getEpisodes(url: String): List<AshiEpisode> = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext emptyList()

        val encoded = URLEncoder.encode(url, "UTF-8")
        val fullUrl = "$baseUrl/episodes?url=$encoded"

        val request = Request.Builder().url(fullUrl).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()

                val body = response.body?.string() ?: return@withContext emptyList()
                val arr = JSONArray(body)
                val result = mutableListOf<AshiEpisode>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.has("error")) continue  // skip error items

                    val number = obj.optInt("number", -1)
                    val href = obj.optString("href", "")
                    if (number > 0 && href.isNotBlank()) {
                        result.add(AshiEpisode(number, href))
                    }
                }

                return@withContext result.sortedBy { it.number }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    suspend fun getStreamOptions(episodeHref: String): List<StreamOption> = withContext(Dispatchers.IO) {
        if (episodeHref.isBlank()) return@withContext emptyList()

        val encoded = URLEncoder.encode(episodeHref, "UTF-8")
        val fullUrl = "$baseUrl/stream?url=$encoded"

        val request = Request.Builder().url(fullUrl).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val rawBody = response.body?.string() ?: return@withContext emptyList()
                val body = rawBody.trim()

                // Try to parse response as a JSON object.
                // If that fails, try to unwrap a JSON STRING containing JSON text.
                val obj: JSONObject = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    // Maybe the server returned a JSON string of JSON text, like "\"{...}\""
                    val unwrapped = if (body.startsWith("\"") && body.endsWith("\"")) {
                        body.substring(1, body.length - 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                    } else {
                        body
                    }

                    try {
                        JSONObject(unwrapped)
                    } catch (e2: Exception) {
                        // Still not valid JSON object → give up gracefully
                        return@withContext emptyList()
                    }
                }

                val options = mutableListOf<StreamOption>()

                // 1Movies-style: { "stream": "https://...", "subtitles": ... }
                if (obj.has("stream")) {
                    val urlStream = obj.optString("stream", "")
                    if (urlStream.isNotBlank()) {
                        options.add(StreamOption(label = "Original", url = urlStream))
                    }
                }

                // Animekai-style: { "streams": ["Hardsub English","https://...", ...], "subtitles": "" }
                if (obj.has("streams")) {
                    val arr = obj.getJSONArray("streams")
                    var i = 0
                    while (i + 1 < arr.length()) {
                        val label = arr.optString(i)
                        val urlStream = arr.optString(i + 1)
                        if (urlStream.startsWith("http")) {
                            options.add(StreamOption(label = label, url = urlStream))
                        }
                        i += 2
                    }
                }

                return@withContext options
            }
        } catch (e: Exception) {
            // Network/parse error → no options
            return@withContext emptyList()
        }
    }



    // Call /stream?url=...
    suspend fun resolveStream(href: String): String = withContext(Dispatchers.IO) {
        if (href.isBlank()) return@withContext ""

        val encoded = URLEncoder.encode(href, "UTF-8")
        val url = "$baseUrl/stream?url=$encoded"

        val request = Request.Builder()
            .url(url)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ""
                }

                val body = response.body?.string() ?: return@withContext ""
                val obj = JSONObject(body)

                // Two possible shapes from ashi.js:
                // 1) { "stream": "https://....m3u8", "subtitles": ... }
                // 2) { "streams": [ "Hardsub English", "https://...", ... ], "subtitles": "" }
                if (obj.has("stream")) {
                    return@withContext obj.optString("stream", "")
                }

                if (obj.has("streams")) {
                    val arr = obj.getJSONArray("streams")
                    // Find first element that looks like a URL
                    for (i in 0 until arr.length()) {
                        val value = arr.optString(i)
                        if (value.startsWith("http")) {
                            return@withContext value
                        }
                    }
                }

                return@withContext ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext ""
        }
    }
}

data class AshiDetails(
    val description: String,
    val aliases: String,
    val airdate: String
)

data class AshiEpisode(
    val number: Int,
    val href: String
)

data class StreamOption(
    val label: String,   // "Hardsub English", "Dubbed English", etc.
    val url: String
)