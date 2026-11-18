package com.podcastapp.playback

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放服务：媒体通知、锁屏控制、耳机控制由 Media3 托管。
 *
 * 兼容性：
 * - 仅在开始播放时由 [start] 拉起，避免 Android 8+ 前台服务超时崩溃；
 * - Android 12+ 后台启动限制由调用方保证在可见界面触发；
 * - 服务被系统回收后，播放状态仍由 [PlaybackHolder] 中的单例保持。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val player: ExoPlayer = PlaybackHolder.requirePlayer(this)
            mediaSession = MediaSession.Builder(this, player).build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 只释放会话，播放器由全局 holder 持有，避免切后台/重建后无法续播
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** 安全启动：任何异常都不应导致 App 崩溃。 */
        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
