package com.mingeek.studiopop.data.youtube

enum class PrivacyStatus(val apiValue: String) {
    PUBLIC("public"),
    UNLISTED("unlisted"),
    PRIVATE("private"),
}

data class VideoMetadata(
    val title: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val categoryId: String = "22",
    val privacy: PrivacyStatus = PrivacyStatus.PRIVATE,
)
