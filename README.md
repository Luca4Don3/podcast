# podcast（纯客户端）

参考 Apple Podcasts 的信息架构与交互，面向 Android、iPhone/iPad、Windows、Linux 和 macOS 的纯客户端播客接收与播放应用。应用直接读取公开 RSS/Atom、OPML 和平台分享链接；**不要求自建后端或应用账号，也不作为播客分发平台**。来源级能力按平台分别实现与验收。

公开仓库：<https://github.com/Luca4Don3/podcast>。当前开发版本：1.9.1（Android/iOS 客户端版本；桌面和 Android 4.0 兼容包是独立预览，尚未进入正式 Release）。

版本 tag 的正式 Release 仍只构建 Android 手机／平板包和 iPhone／iPad 模拟器包；Windows、Linux、macOS 以及 Android 4.0 兼容 APK 由独立工作流产出预览制品，不创建 Release、不覆盖历史产物。Apple 真机包仍需自己的签名。

## 平台接入

- 直接导入公开 RSS 2.0 / Atom 订阅地址，客户端解析节目与单集，音频从原始发布方地址播放。
- 导入 OPML，一次迁移已有订阅列表。
- 识别 Apple Podcasts 分享链接，使用官方 iTunes Lookup 取得公开 feed；Spotify、小宇宙、喜马拉雅的公开节目页链接可识别平台，但**不会伪造 RSS**——需要节目自身的公开 feed 才能订阅。
- 来源级认证与加密：支持链接内账号密码/Token/API Key、Digest、可选仅 HTTPS（默认允许 HTTP，建议 HTTPS）、自定义 CA 与 mTLS；凭据和私钥加密保存在本机，只发送给对应来源。
- 网络音频：通用 WebDAV、坚果云、极空间 WebDAV、飞连企业网络（WebDAV）；新增 SMB 2/3 直连 NAS 来源，支持只读浏览和按偏移读取。
- OneDrive 提供官方设备码授权与只读音频浏览；无需自建后端，授权不等于已完成真实租户验收。
- 不绕过付费、地区限制、登录墙或 DRM；免费可听不等于取得转播授权。

详见 [平台接入说明](<docs/PLATFORM_ADAPTERS.md>)。

## 客户端能力

- **移动端离线缓存**：Android 与 iPhone/iPad 的每个订阅可选不缓存、最新 5 集、最新 10 集或全部，支持手动更新缓存。详见 [离线缓存](<docs/OFFLINE_CACHE.md>)；桌面预览尚未接入自动离线缓存。

**Android**
- 资料和播放：订阅、单集列表、封面、简介、Media3 后台播放、媒体通知、锁屏与有线/蓝牙耳机控制、音频焦点与断开暂停、变速、跳转与进度。
- 多屏：根据当前窗口安全区、刘海/挖孔和折叠铰链调整底部导航、侧栏和播放器；支持窄长外屏、普通直屏、宽屏、折叠展开与横屏。
- 导入：公开 RSS/Atom、OPML、Apple Podcasts 分享链接；支持从系统分享菜单接收播客链接，其他平台页面识别后提示补充公开 feed。

**Android 4.0 兼容预览**
- [Podcast Lite](<android/legacy/README.md>) 是独立的 API 14 兼容 APK，不改变现代 Android 版本。以本地或公开音频、订阅导入和遥控器操作为主；设备兼容性以测试记录为准。

**iPhone / iPad**
- SwiftUI 通用应用；iPad 宽窗双栏，窄分屏和 iPhone 紧凑导航；支持四方向和辅助大字号。
- 已移除后端账号登录；订阅与单集元数据保存在本机，公开 feed 与 Apple 分享链接可直接解析。

**Windows 预览**
- 原生 C++/Win32 与系统 MediaPlayer，无 Electron、WebView2 或额外媒体运行时；三栏订阅界面、OPML 导入、后台刷新和手动队列见 [Windows 预览](<windows/README.md>)。

**Linux / macOS 预览**
- Linux 使用 C11、GTK 3 和 GStreamer；macOS 使用 AppKit/AVFoundation 并构建 Intel + Apple Silicon Universal 2。两端支持本地音频、公开 HTTP(S) 音频和订阅导入，详见 [桌面预览](<docs/DESKTOP_PREVIEW.md>)。

## 构建

- Android：`cd android && ./gradlew :app:testCompactUnitTest :app:exportCompactApks`。
- Android 4.0 兼容预览：`bash android/gradlew -p android/legacy --no-daemon lintDebug assembleDebug`，然后 `bash tests/verify-android-legacy-apk.sh`。
- Apple 模拟器：`swift test --package-path apple`，然后 `bash apple/build-simulator.sh output/apple`。
- Windows：`cmake -S windows -B build -A x64`（或 `-A ARM64`），构建后运行 CTest；原生 CI 矩阵见 [windows.yml](<.github/workflows/windows.yml>)。
- Linux / macOS：按 [桌面预览](<docs/DESKTOP_PREVIEW.md>) 中的架构矩阵和命令构建；对应工作流为 [desktop-preview.yml](<.github/workflows/desktop-preview.yml>)。
- GitHub Actions 的 `release` 只构建并上传移动端客户端产物；服务端二进制不再进入 Release。预览工作流的制品都不发布 Release。

## 目录状态

```
podcastapp/
├── android/                # Android 客户端与 Android 4.0 兼容预览
├── apple/                  # iPhone / iPad 客户端与共享解析层
├── windows/                # Windows 原生 C++ 预览
├── linux/                  # Linux 原生 GTK3 / GStreamer 预览
├── macos/                  # macOS AppKit / AVFoundation Universal 2 预览
├── docs/                   # 平台接入、多屏适配、桌面与历史协议资料
├── tests/                  # 跨平台构建与制品验证脚本
├── server/  server-cpp/  deploy/  # 历史自建后端，已退出构建和发布（源码删除待确认）
```

多屏适配见 [Android 多屏适配](<docs/ANDROID_ADAPTIVE.md>) 与 [Apple 客户端](<docs/APPLE_CLIENT.md>)。平台状态与验收边界见 [Android 4.0 兼容预览](<android/legacy/README.md>)、[Windows 桌面路线](<docs/WINDOWS_CLIENT.md>) 与 [桌面预览](<docs/DESKTOP_PREVIEW.md>)。后续交互开发见 [播客交互路线](<docs/PODCAST_UX_ROADMAP.md>)。
