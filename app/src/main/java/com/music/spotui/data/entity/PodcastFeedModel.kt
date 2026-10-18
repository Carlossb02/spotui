package com.music.spotui.data.entity

data class PodcastCategoryModel(
    val id: String,
    val name: String,
    val params: String? = null,
    val browseId: String? = null,
)

data class PodcastSectionModel(
    val title: String,
    val shows: List<PodcastModel> = emptyList(),
    val episodes: List<SongsModel> = emptyList(),
)

data class PodcastFeedModel(
    val categories: List<PodcastCategoryModel> = emptyList(),
    val selectedCategory: PodcastCategoryModel? = null,
    val sections: List<PodcastSectionModel> = emptyList(),
)
