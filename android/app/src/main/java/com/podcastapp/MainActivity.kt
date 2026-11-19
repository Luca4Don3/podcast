package com.podcastapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.podcastapp.playback.NotificationPermission
import com.podcastapp.ui.PodcastAppRoot
import com.podcastapp.ui.SharedImport
import com.podcastapp.ui.adaptive.rememberFoldingFeatures
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

    private var pendingImport by mutableStateOf<SharedImport?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingImport = sharedImport(intent)
    }

    private fun sharedImport(intent: Intent?): SharedImport? {
        val value = intent?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            ?: intent?.data?.toString()?.takeIf { it.isNotBlank() }
        return value?.let { SharedImport(it) }
    }

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
        pendingImport = sharedImport(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            PodcastAppTheme {
                PodcastAppRoot(
                    activity = this@MainActivity,
                    folds = rememberFoldingFeatures(this@MainActivity),
                    sharedImport = pendingImport,
                    onSharedImportConsumed = { pendingImport = null },
                )
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
