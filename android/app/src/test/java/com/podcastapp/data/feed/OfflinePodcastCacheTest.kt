package com.podcastapp.data.feed

import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.data.remote.PodcastDto
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflinePodcastCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val mp3 = "ID3".toByteArray() + ByteArray(1024)
    private val filename = Regex("""[0-9a-f]{64}\.(mp3|audio)(\.part)?""")

    private val disk = object : OfflineCacheFiles {
        override fun targetCount(policy: Int, available: Int) = if (policy == -1) available else minOf(policy, available)
        override fun fileSize(directory: File, name: String): Long = File(directory, name).length()
        override fun commit(directory: File, part: String, name: String, expectedSize: Long): Int {
            val file = File(directory, part)
            assertEquals(name + ".part", part)
            return if (file.length() == expectedSize && expectedSize > 0 && file.renameTo(File(directory, name))) 0 else -1
        }
        override fun prune(directory: File, keep: Collection<String>): Int = directory.listFiles().orEmpty().count {
            filename.matches(it.name) && it.name !in keep && it.delete()
        }
    }

    private fun feed(id: String = "local-feed-test", count: Int = 12): LocalFeed {
        val url = "https://feed.example/$id.xml"
        return LocalFeed(id, url, url, "DIRECT", PodcastDto(id = id), (1..count).map { i ->
            EpisodeDto(id = "$id-$i", podcastId = id, audioUrl = "https://cdn.example/$i.mp3?sig=synthetic",
                publishedAt = "2026-11-${i.toString().padStart(2, '0')}T00:00:00Z")
        })
    }

    private val successful = OfflineMediaDownloader { _, _, _, part, progress ->
        part.writeBytes(mp3)
        progress(mp3.size.toLong(), null)
        mp3.size.toLong()
    }

    @Test fun policiesPersistAndPrunePerFeed() = runBlocking {
        val one = feed()
        val two = feed("local-feed-other")
        val cache = OfflinePodcastCache(temp.root, disk, successful)
        assertEquals(OfflinePolicy.NONE, cache.policy(one))
        val progress = mutableListOf<OfflineProgress>()
        assertEquals(5, cache.update(one, OfflinePolicy.LATEST_5, onProgress = { progress += it }).completed)
        assertTrue(progress.any { it.bytes == mp3.size.toLong() })
        assertEquals(listOf(7, 8, 9, 10, 11), one.episodes.indices.filter { cache.readyFile(one, one.episodes[it]) != null })
        val restarted = OfflinePodcastCache(temp.root, disk, successful)
        assertEquals(OfflinePolicy.LATEST_5, restarted.policy(one))
        assertEquals(10, restarted.update(one, OfflinePolicy.LATEST_10).completed)
        assertEquals(12, restarted.update(one, OfflinePolicy.ALL).completed)
        restarted.update(two, OfflinePolicy.ALL)
        restarted.update(one, OfflinePolicy.LATEST_5)
        assertNull(restarted.readyFile(one, one.episodes.first()))
        assertNotNull(restarted.readyFile(two, two.episodes.first()))
        restarted.update(one, OfflinePolicy.NONE)
        assertEquals(OfflinePolicy.NONE, restarted.policy(one))
        assertEquals(0, temp.root.resolve(sha256Hex(one.feedUrl)).list().orEmpty().size)
        assertTrue(temp.root.walk().none { it.name.contains("synthetic") })
    }

    @Test fun refreshFetchesFeedFirstAndFailedFeedLeavesCacheAlone() = runBlocking {
        val original = feed(count = 1)
        val cache = OfflinePodcastCache(temp.root, disk, successful)
        cache.update(original, OfflinePolicy.ALL)
        val oldFile = cache.readyFile(original, original.episodes.single())!!
        assertTrue(refreshFeedBeforeCache({ Result.failure(IOException("fake offline")) }) {
            fail("cache was updated after feed request failed")
        }.isFailure)
        assertTrue(oldFile.isFile)
        val updated = original.copy(episodes = emptyList())
        val steps = mutableListOf<String>()
        refreshFeedBeforeCache({ steps += "feed"; Result.success(updated) }) { fresh ->
            steps += "cache"
            cache.update(fresh)
        }.getOrThrow()
        assertEquals(listOf("feed", "cache"), steps)
        assertFalse(oldFile.exists())
    }

    @Test fun downloadFailureAndCancellationPreserveExistingAndDeleteParts() = runBlocking {
        val original = feed(count = 2)
        OfflinePodcastCache(temp.root, disk, successful).update(original, OfflinePolicy.ALL)
        val changed = original.copy(episodes = listOf(original.episodes.last().copy(audioUrl = "https://cdn.example/changed.mp3")))
        val failed = OfflinePodcastCache(temp.root, disk, OfflineMediaDownloader { _, _, _, dest, _ ->
            dest.writeBytes(mp3)
            throw IOException("simulated truncation")
        })
        assertEquals(1, failed.update(changed).failed)
        assertNotNull(failed.readyFile(original, original.episodes.first()))
        assertNull(failed.readyFile(changed, changed.episodes.single()))
        val downloading = CompletableDeferred<Unit>()
        val cancelled = OfflinePodcastCache(temp.root, disk, OfflineMediaDownloader { _, _, _, dest, _ ->
            dest.writeBytes(mp3)
            downloading.complete(Unit)
            awaitCancellation()
        })
        val job = launch { cancelled.update(changed) }
        withTimeout(5000) { downloading.await(); job.cancelAndJoin() }
        assertNotNull(cancelled.readyFile(original, original.episodes.first()))
        assertTrue(temp.root.walk().none { it.name.endsWith(".part") })
    }

    @Test fun refreshingKeepsPlaybackFileUntilItIsReleased() = runBlocking {
        val original = feed(count = 1)
        val cache = OfflinePodcastCache(temp.root, disk, successful)
        cache.update(original, OfflinePolicy.ALL)
        val file = cache.selectPlaybackFiles(original.episodes) { original }.single()!!
        cache.update(original.copy(episodes = emptyList()))
        assertTrue(file.isFile)
        cache.clearPlaybackFiles()
        cache.update(original.copy(episodes = emptyList()))
        assertFalse(file.exists())
    }

    @Test fun filenameAndStreamingRejectNonMediaAndBoundUnknownLengths() = runBlocking {
        val episode = feed(count = 1).episodes.single()
        assertTrue(filename.matches(offlineName(episode)!!))
        assertNotEquals(offlineName(episode), offlineName(episode.copy(audioUrl = episode.audioUrl + "x")))
        for (url in listOf("file:///tmp/a.mp3", "https://u:p@pod.example/a.mp3", "https://a.example/a.M3U8?x=y",
            "https://a.example/a.html")) assertNull(offlineName(episode.copy(audioUrl = url)))
        val good = temp.root.resolve("good.part")
        assertEquals(mp3.size.toLong(), streamOfflineBody(mp3.inputStream(), -1, good) { _, _ -> })
        assertArrayEquals(mp3, good.readBytes())
        suspend fun rejected(bytes: ByteArray, length: Long, bound: Long): Throwable? = runCatching {
            streamOfflineBody(bytes.inputStream(), length, temp.root.resolve("bad-$length-$bound.part"), bound) { _, _ -> }
        }.exceptionOrNull()
        assertTrue(rejected("#EXTM3U\nsegment.ts".toByteArray(), -1, 4096) is UnsupportedOfflineMedia)
        assertTrue(rejected("<!DOCTYPE html>".toByteArray(), -1, 4096) is UnsupportedOfflineMedia)
        assertTrue(rejected(mp3, -1, 100) is IOException)
        assertTrue(rejected(mp3, mp3.size.toLong() + 1, 4096) is IOException)
        assertTrue(rejected(mp3, 4100, 4096) is IOException)
    }

    @Test fun credentialsAndTransportDoNotCrossOriginsOrReturnAfterRedirect() = runBlocking {
        val feed = "https://feed.example/feed.xml"
        val access = OfflineSourceAccess(SourceCredentials(kind = CredentialKind.BEARER, secret = "synthetic"),
            SourceTransport(requireHttps = true, customCaPem = "synthetic", clientKeyPem = "synthetic", digest = true))
        val requests = mutableListOf<Pair<String?, OfflineSourceAccess>>()
        var next = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fake")
            when (next++) {
                0 -> response.code(302).header("Location", "https://feed.example:444/clip.mp3").body("".toResponseBody()).build()
                1 -> response.code(302).header("Location", "https://feed.example:443/clip2.mp3").body("".toResponseBody()).build()
                else -> response.code(200).header("Content-Type", "audio/mpeg").body(mp3.toResponseBody()).build()
            }
        }.build()
        val downloader = OfflineHttpDownloader { request, actual ->
            requests += request.header("Authorization") to actual
            client.newCall(request)
        }
        downloader.download(feed, "https://feed.example:443/clip.mp3", access, temp.root.resolve("clip.part")) { _, _ -> }
        assertEquals(listOf("Bearer synthetic", null, null), requests.map { it.first })
        assertEquals(listOf(access, OfflineSourceAccess(transport = SourceTransport(requireHttps = true)),
            OfflineSourceAccess(transport = SourceTransport(requireHttps = true))), requests.map { it.second })
        assertEquals(OfflineSourceAccess(transport = SourceTransport(requireHttps = true)),
            offlineHopAccess(feed.toHttpUrl(), "http://feed.example/clip.mp3".toHttpUrl(), access))
    }
}
