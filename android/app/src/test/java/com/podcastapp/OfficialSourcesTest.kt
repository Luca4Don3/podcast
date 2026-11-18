package com.podcastapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialSourcesTest {
    @Test fun allowsOnlyTwoSpecifiedMainlandOfficialWebsites() {
        assertEquals(listOf("https://www.radio.cn/pc-portal/home/", "https://www.cnr.cn/"),
            OfficialSources.mainland.map { it.websiteUrl })
        assertEquals(listOf("云听", "央广网"), OfficialSources.mainland.map { it.name })
        assertTrue(OfficialSources.mainland.size <= 2)
        assertTrue(OfficialSources.mainland.all { it.region == "中国大陆" && it.publisher.isNotBlank() })
    }

    @Test fun officialLinkDtoHasNoStreamOrThirdPartyDirectoryFields() {
        OfficialSources.mainland.forEach { source ->
            val fields = Json.encodeToJsonElement(source).jsonObject
            assertEquals(setOf("name", "websiteUrl", "region", "publisher"), fields.keys)
            assertFalse(fields.containsKey("streamUrl"))
            assertFalse(fields.containsKey("externalId"))
            assertFalse(fields.containsKey("source"))
        }
    }

    @Test fun linksAreHttpWebpagesNotStreamPlaylistsOrDirectoryApis() {
        OfficialSources.mainland.forEach { source ->
            assertEquals(source.websiteUrl, HttpSourceUrl.parse(source.websiteUrl))
            assertFalse(source.websiteUrl.contains("m3u8"))
            assertFalse(source.websiteUrl.contains("radio-browser"))
            assertFalse(source.websiteUrl.contains("itunes"))
        }
    }
}
