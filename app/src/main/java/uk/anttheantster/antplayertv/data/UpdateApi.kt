package uk.anttheantster.antplayertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class UpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val apkUrl: String,
    val changelog: String
)

class UpdateApi(
    private val baseUrl: String = "https://api.anttheantster.uk"
) {
    private val client = OkHttpClient()

    suspend fun checkUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/app/version")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                return@withContext UpdateInfo(
                    latestVersionCode = json.optInt("versionCode", 0),
                    latestVersionName = json.optString("versionName", "0.0.0"),
                    apkUrl = json.optString("apkUrl", ""),
                    changelog = json.optString("changelog", "")
                )
            }
        } catch (e: Exception) {
            return@withContext null
        }
    }
}
