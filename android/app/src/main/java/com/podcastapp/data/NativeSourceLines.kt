package com.podcastapp.data

/** The native scanner is loaded on first bulk import, never during app startup. */
internal object NativeSourceLines {
    const val FIELDS = 5
    const val MAX_ROWS = 101 // One sentinel row tells the caller the batch exceeds 100.
    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("podcast_native")
            true
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private external fun scanNative(text: String): IntArray?

    internal fun scanNativeOrNull(text: String): IntArray? = if (loaded) {
        try { scanNative(text) } catch (_: LinkageError) { null }
    } else null

    fun scan(text: String): IntArray = scanNativeOrNull(text) ?: scanManaged(text)

    /** Same compact span format as JNI; this also keeps unsupported hosts usable. */
    internal fun scanManaged(text: String): IntArray {
        val spans = IntArray(MAX_ROWS * FIELDS)
        var end = 0
        var line = 1
        var written = 0
        while (end < text.length && written < spans.size) {
            var first = -1
            var last = 0
            var pipe = -1
            var pipes = 0
            while (end < text.length) {
                val ch = text[end]
                if (ch == '\r' || ch == '\n') break
                if (!ch.isWhitespace()) {
                    if (first < 0) first = end
                    last = end + 1
                    if (ch == '|') {
                        if (pipe < 0) pipe = end
                        ++pipes
                    }
                }
                ++end
            }
            if (first >= 0) {
                spans[written++] = line
                spans[written++] = first
                spans[written++] = last
                spans[written++] = pipes
                spans[written++] = pipe
            }
            if (end < text.length) {
                val ch = text[end++]
                if (ch == '\r' && end < text.length && text[end] == '\n') ++end
            }
            ++line
        }
        return spans.copyOf(written)
    }
}
