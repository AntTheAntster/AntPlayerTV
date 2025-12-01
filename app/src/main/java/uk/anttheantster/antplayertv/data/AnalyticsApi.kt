package uk.anttheantster.antplayertv.data

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class AnalyticsApi(
    private val baseUrl: String = "https://api.anttheantster.uk"
) {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Fire-and-forget logging of a playback start.
     */
    fun logPlay(
        licenseKey: String?,
        deviceId: String,
        title: String,
        episodeLabel: String,
        watchType: String
    ) {
        val json = JSONObject().apply {
            put("licenseKey", licenseKey ?: JSONObject.NULL)
            put("deviceId", deviceId)
            put("title", title)
            put("episodeLabel", episodeLabel)
            put("watchType", watchType)
        }

        val request = Request.Builder()
            .url("$baseUrl/api/analytics/play")
            .post(json.toString().toRequestBody(jsonMediaType))
            .build()

        // async, we don't care about result in the app
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // ignore – analytics failure should never break playback
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
