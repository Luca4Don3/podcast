package com.podcastapp.data

import kotlinx.serialization.Serializable

/** Website links only: deliberately has no playback URL or directory identifier. */
@Serializable
data class OfficialSourceLink(
    val name: String,
    val websiteUrl: String,
    val region: String,
    val publisher: String,
)

object OfficialSources {
    val mainland: List<OfficialSourceLink> = listOf(
        OfficialSourceLink(
            name = "云听",
            websiteUrl = "https://www.radio.cn/pc-portal/home/",
            region = "中国大陆",
            publisher = "中央广播电视总台 · 央广云听文化传媒有限公司",
        ),
        OfficialSourceLink(
            name = "央广网",
            websiteUrl = "https://www.cnr.cn/",
            region = "中国大陆",
            publisher = "中央广播电视总台 · 央广网",
        ),
    )
}
