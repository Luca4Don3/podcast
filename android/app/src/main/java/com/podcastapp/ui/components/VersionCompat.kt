package com.podcastapp.ui.components

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * 版本与设备兼容工具：把「按系统版本分支」和「按语言/屏幕格式化」的逻辑收到一处，
 * 避免散落在各 Screen 里造成不同版本行为不一致。
 *
 * 最低支持 Android 7.0 (API 24)；所有判断都做了向下兼容。
 */
object Compat {

    /** Android 8.0：通知渠道、后台执行限制。 */
    fun isOreoOrAbove(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** Android 10：分区存储。 */
    fun isQOrAbove(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Android 12：动态取色、前台服务限制收紧。 */
    fun isSOrAbove(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Android 13：通知运行时权限。 */
    fun isTiramisuOrAbove(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Android 14：前台服务类型显式声明。 */
    fun isUpsideDownCakeOrAbove(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Android 15：强制全面屏（edge-to-edge）。 */
    fun isVanillaIceCreamOrAbove(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    /** 中文/日文/韩文环境。 */
    fun isCjkLocale(): Boolean {
        val lang = Locale.getDefault().language
        return lang == "zh" || lang == "ja" || lang == "ko"
    }

    /** 右到左语言（阿拉伯语、希伯来语等），用于验证镜像布局。 */
    fun isRtlLocale(): Boolean {
        val tag = Locale.getDefault().toLanguageTag()
        return tag.startsWith("ar") || tag.startsWith("he") || tag.startsWith("fa") ||
            tag.startsWith("ur") || tag.startsWith("yi")
    }

    /** 小屏（< 360dp 宽，如 480x800 老机型）。 */
    fun isCompactScreen(context: android.content.Context): Boolean =
        widthDp(context) < 360f

    /** 平板 / 折叠屏展开（>= 600dp 宽）。 */
    fun isWideScreen(context: android.content.Context): Boolean =
        widthDp(context) >= 600f

    /** 系统的超大字体设置（fontScale > 1.3），用于压缩固定高度组件。 */
    fun isLargeFont(context: android.content.Context): Boolean =
        context.resources.configuration.fontScale > 1.3f

    fun widthDp(context: android.content.Context): Float {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels / metrics.density
    }
}

/** 系统状态栏高度，避免内容压到状态栏下。 */
@Composable
fun statusBarHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** 系统导航栏 / 手势条高度。 */
@Composable
fun navigationBarHeight(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** 底部安全区：至少保留 8dp，避免与手势条重叠。 */
@Composable
fun safeBottomPadding(base: Dp = 0.dp): Dp = base + navigationBarHeight().coerceAtLeast(8.dp)
