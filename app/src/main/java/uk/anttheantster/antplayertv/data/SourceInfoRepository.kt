package uk.anttheantster.antplayertv.data

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import uk.anttheantster.antplayertv.model.SourceInfo

class SourceInfoRepository(private val context: Context) {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(SourceInfo::class.java)

    fun loadAshiSource(): SourceInfo? {
        return try {
            val json = context.assets.open("ashi.json")
                .bufferedReader()
                .use { it.readText() }

            adapter.fromJson(json)
        } catch (e: Exception) {
            Log.e("SourceInfoRepository", "Error loading ashi.json", e)
            null
        }
    }
}
