# Android 4.0 兼容预览（Podcast Lite）

这是独立 APK，包名 `com.podcastapp.legacy`，最低 Android 4.0 / API 14。适用目标包括低内存手机、可侧载普通 Android APK 的车机和电视盒子。现代 Android 应用继续使用自己的包名、构建和发布流程；两版可以共存，不共享资料库。

## 低资源策略

- 系统 View、可见的 D-pad 焦点和原生 MediaPlayer，不依赖 AndroidX、Compose、Media3、WebView 或 JNI；一个 APK 不按 CPU 拆分。
- 仅音频，无封面加载、动画和后台定时刷新。前台播放；网络订阅在后台线程处理，取消后不更新界面。
- 本地文件通过系统选择器打开；早期或厂商设备若没有相应文件管理器，可能无法选文件。电视上的文本输入依赖系统输入法。没有 Android Auto 或 Android Automotive 驾驶应用认证；车机仅在安全停车时手动操作。
- 旧 ROM 的音频解码、TLS 版本、证书库和媒体重定向能力不一致。使用平台信任链，不跳过证书检查，不把 HTTPS 订阅重定向到 HTTP。用户明确输入的 HTTP 源仍可用，但明文传输只应在可信网络使用；HTTPS 失败时不自动降级。
- 这是最小兼容版，不等同于现代版：后台媒体会话、离线自动缓存、NAS/网盘适配、来源认证和图形效果不属于该预览的验收范围。

## 构建和核对

使用 JDK 17 或 21、Android SDK Platform 35 和 Build Tools 35.0.0。在仓库根目录执行：

```sh
bash android/gradlew -p android/legacy --no-daemon lintDebug assembleDebug
bash tests/verify-android-legacy-apk.sh
```

[独立验证工作流](<../../.github/workflows/android-legacy.yml>) 检查 manifest、Android 4.0 所需的 APK v1 签名、Dalvik DEX 035 和不含原生库。预览产物使用调试签名，不是正式发行包，也不能保证跨构建机的调试签名更新兼容。

## 设备验收

| 设备 | 重点 |
| --- | --- |
| API 14（Android 4.0–4.0.2） | 独立验证安装、冷启动、浏览列表、本地和公开音频；不能用 API 15 代替。 |
| API 15（Android 4.0.3–4.0.4） | 安装、启动、D-pad 移焦、RSS/Atom/OPML、播放/暂停/跳转。 |
| 低内存手机 | 小竖屏和横屏、重进或旋转、缓慢/断开网络、播放器释放。 |
| 车机和电视 | 从桌面打开、遥控器和音量键、横屏安全边距、厂商解码及音频切换。 |

安装测试包后，建议先用不含凭据的本地 MP3 和自己有权访问的订阅验收。旧设备联调应使用隔离网络。能安装、模拟器能播放、真机表现是三类独立证据；设备兼容性以实际测试记录为准。
