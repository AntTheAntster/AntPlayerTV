package uk.anttheantster.antplayertv.model

data class AuthorInfo(
    val name: String = "",
    val icon: String = ""
)

data class SourceInfo(
    val sourceName: String = "",
    val iconUrl: String = "",
    val author: AuthorInfo = AuthorInfo(),
    val version: String = "",
    val language: String = "",
    val streamType: String = "",
    val quality: String = "",
    val baseUrl: String = "",
    val searchBaseUrl: String = "",
    val scriptUrl: String = "",
    val type: String = "",
    val asyncJS: Boolean = false,
    val softsub: Boolean = false,
    val downloadSupport: Boolean = false
)
