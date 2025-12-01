package uk.anttheantster.antplayertv.data

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import uk.anttheantster.antplayertv.model.ContentRoot
import uk.anttheantster.antplayertv.model.MediaItem

class LocalJsonSource(
    private val context: Context,
    private val assetFileName: String
) {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(ContentRoot::class.java)

    fun loadItems(): List<MediaItem> {
        return try {
            val json = context.assets.open(assetFileName)
                .bufferedReader()
                .use { it.readText() }

            val root = adapter.fromJson(json)
            root?.items ?: emptyList()
        } catch (e: Exception) {
            Log.e("LocalJsonSource", "Error loading $assetFileName", e)
            emptyList()
        }
    }
}
