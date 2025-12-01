package uk.anttheantster.antplayertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class LicenseCheckResult(
    val valid: Boolean,
    val status: String,
    val message: String
)

class LicenseApi(
    private val baseUrl: String = "https://api.anttheantster.uk"
) {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun checkLicense(
        licenseKey: String,
        deviceId: String,
        appVersion: String
    ): LicenseCheckResult = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().apply {
            put("licenseKey", licenseKey)
            put("deviceId", deviceId)
            put("appVersion", appVersion)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/license/check")
            .post(bodyJson.toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext LicenseCheckResult(
                        valid = false,
                        status = "http_error",
                        message = "HTTP ${response.code}"
                    )
                }
                val text = response.body?.string() ?: "{}"
                val json = JSONObject(text)
                return@withContext LicenseCheckResult(
                    valid = json.optBoolean("valid", false),
                    status = json.optString("status", "unknown"),
                    message = json.optString("message", "")
                )
            }
        } catch (e: Exception) {
            return@withContext LicenseCheckResult(
                valid = false,
                status = "network_error",
                message = e.message ?: "Network error"
            )
        }
    }
}
