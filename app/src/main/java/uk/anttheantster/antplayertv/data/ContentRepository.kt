package uk.anttheantster.antplayertv.data

import android.content.Context
import uk.anttheantster.antplayertv.model.MediaItem

data class SourceConfig(
    val id: String,
    val displayName: String,
    val assetFileName: String
)

class ContentRepository(private val context: Context) {

    // For now we only have one source, backed by content.json.
    // Later you can add more SourceConfig entries here.
    private val sources = listOf(
        SourceConfig(
            id = "ashi",
            displayName = "Ashi Catalog",
            assetFileName = "ashi_catalog.json"
        )
    )


    fun loadAllItems(): List<MediaItem> {
        return sources.flatMap { source ->
            LocalJsonSource(context, source.assetFileName).loadItems()
        }
    }
}
