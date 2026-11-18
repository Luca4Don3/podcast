package com.podcastapp.data

import com.podcastapp.data.remote.AddStationRequest
import java.net.URI

/** Only validates URL syntax. Reachability never establishes permission to use a source. */
object HttpSourceUrl {
    fun parse(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "请填写 HTTP(S) URL" }
        require(value.none { it.isWhitespace() || it.isISOControl() }) { "URL 不能包含空格或控制字符" }
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("URL 格式无效")
        }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "仅支持 HTTP(S) URL"
        }
        require(!uri.host.isNullOrBlank() && !uri.rawAuthority.isNullOrBlank()) { "URL 缺少有效主机名" }
        require(uri.rawUserInfo == null) { "请勿在 URL 中嵌入用户名或密码" }
        require((uri.port == -1 || uri.port in 1..65535) && !uri.rawAuthority.endsWith(":")) {
            "URL 端口无效"
        }
        return uri.scheme.lowercase() + value.substring(value.indexOf(':'))
    }
}

object AudioSourceImport {
    const val MAX_BATCH_SIZE = 100

    fun single(name: String, url: String): AddStationRequest {
        val validUrl = HttpSourceUrl.parse(url)
        val title = name.trim()
        require(title.none { it.isISOControl() }) { "名称不能包含控制字符" }
        return AddStationRequest(
            name = title.ifBlank { URI(validUrl).host },
            streamUrl = validUrl,
        )
    }

    /** Blank lines are ignored; any other invalid line rejects the entire batch before the API call. */
    fun batch(text: String): List<AddStationRequest> {
        val spans = NativeSourceLines.scan(text)
        val size = spans.size / NativeSourceLines.FIELDS
        require(size > 0) { "请粘贴至少一行音源 URL" }
        require(size <= MAX_BATCH_SIZE) { "每次最多导入 "+ MAX_BATCH_SIZE + " 条，请分批导入" }
        val requests = ArrayList<AddStationRequest>(size)
        val errors = mutableListOf<String>()
        val firstLines = HashMap<String, Int>(size * 2)
        for (offset in spans.indices step NativeSourceLines.FIELDS) {
            val lineNumber = spans[offset]
            val start = spans[offset + 1]
            val end = spans[offset + 2]
            val pipes = spans[offset + 3]
            val pipe = spans[offset + 4]
            try {
                require(pipes <= 1) { "仅支持每行 URL 或 名称|URL（URL 中的 | 请编码为 %7C）" }
                require(pipes == 0 || pipe > start) { "分隔符前缺少名称" }
                val request = if (pipes == 0) single("", text.substring(start, end))
                    else single(text.substring(start, pipe), text.substring(pipe + 1, end))
                val previousLine = firstLines.putIfAbsent(request.streamUrl, lineNumber)
                require(previousLine == null) { "与第 "+ previousLine + " 行的 URL 重复" }
                requests += request
            } catch (error: IllegalArgumentException) {
                errors += "第 "+ lineNumber + " 行：" + error.message
            }
        }
        require(errors.isEmpty()) { errors.joinToString("\n") }
        return requests
    }
}
