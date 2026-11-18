package com.podcastapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.podcastapp.playback.NotificationPermission
import com.podcastapp.ui.PodcastAppRoot
import com.podcastapp.ui.theme.PodcastAppTheme

/**
 * 单 Activity + Compose。
 *
 * 兼容性要点：
 * - enableEdgeToEdge 统一处理 Android 15 强制全面屏与低版本状态栏；
 * - 前台服务不在启动时拉起（避免 Android 8+ 崩溃），由播放动作触发；
 * - 通知权限仅在 Android 13+ 申请，拒绝后不阻塞使用。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            PodcastAppTheme {
                PodcastAppRoot()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (!NotificationPermission.required()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
