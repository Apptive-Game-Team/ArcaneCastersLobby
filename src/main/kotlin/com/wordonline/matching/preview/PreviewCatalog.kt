package com.wordonline.matching.preview

data class PreviewCatalog(
    val status: String,
    val revision: String?,
    val magics: List<PreviewEntry> = emptyList(),
    // Registry ID only. The client never supplies a server URL.
    val serverId: Long? = null,
)

data class PreviewEntry(val name: String, val hash: String?, val bytes: Int, val available: Boolean)
