package com.podcastapp.data.feed

import androidx.annotation.Keep
import java.io.File

internal interface OfflineCacheFiles {
    fun targetCount(policy: Int, available: Int): Int
    fun fileSize(directory: File, name: String): Long
    fun commit(directory: File, part: String, name: String, expectedSize: Long): Int
    fun prune(directory: File, keep: Collection<String>): Int
}

@Keep
internal object NativeOfflineCache : OfflineCacheFiles {
    init { System.loadLibrary("podcast_native") }

    override fun targetCount(policy: Int, available: Int) = nativeTargetCount(policy, available)
    override fun fileSize(directory: File, name: String) = nativeFileSize(directory.path, name)
    override fun commit(directory: File, part: String, name: String, expectedSize: Long) =
        nativeCommit(directory.path, part, name, expectedSize)
    override fun prune(directory: File, keep: Collection<String>) =
        nativePrune(directory.path, keep.joinToString("\n"))

    fun hasMediaSignature(bytes: ByteArray, length: Int) = nativeHasMediaSignature(bytes, length)

    private external fun nativeHasMediaSignature(bytes: ByteArray, length: Int): Boolean
    private external fun nativeTargetCount(policy: Int, available: Int): Int
    private external fun nativeFileSize(directory: String, name: String): Long
    private external fun nativeCommit(directory: String, part: String, name: String, expectedSize: Long): Int
    private external fun nativePrune(directory: String, keep: String): Int
}
