package com.podcastapp.data.repository

import com.podcastapp.data.remote.ApiErrorBody
import com.podcastapp.data.remote.ApiException
import java.io.IOException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * 把 Retrofit 的 HttpException / IOException 转成带中文提示的 [ApiException]。
 */
suspend fun <T> apiCall(block: suspend () -> T): T {
    return try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: HttpException) {
        val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val parsed = raw?.let {
            runCatching { errorJson.decodeFromString<ApiErrorBody>(it) }.getOrNull()
        }
        val message = when {
            !parsed?.message.isNullOrBlank() -> parsed.message
            e.code() == 401 -> "登录状态已失效，请重新登录"
            e.code() == 403 -> "没有权限执行该操作"
            e.code() == 404 -> "请求的资源不存在"
            e.code() >= 500 -> "服务器开小差了，请稍后再试"
            else -> "请求失败（" + e.code() + "）"
        }
        throw ApiException(e.code(), message, e)
    } catch (e: IOException) {
        throw ApiException(0, "网络连接失败，请检查网络或服务器地址", e)
    } catch (e: IllegalArgumentException) {
        throw ApiException(-1, "服务器地址格式不正确", e)
    }
}
