# podcast（纯客户端）

参考 Apple Podcasts 的信息架构与交互，面向 Android、iPhone 和 iPad 的播客接收与播放客户端。应用直接读取公开 RSS/Atom、OPML 和平台分享链接；**不再要求自建后端，也不作为播客分发平台**。

公开仓库：<https://github.com/Luca4Don3/podcast>。当前开发版本：1.5.0（client-only，尚未发布到 GitHub Release）。

新版本 tag 仅构建 Android 手机／平板包和 iPhone／iPad 模拟器包；历史 Release 不覆盖。Apple 真机包仍需自己的签名。

## 平台接入

- 直接导入公开 RSS 2.0 / Atom 订阅地址，客户端解析节目与单集，音频从原始发布方地址播放。
- 导入 OPML，一次迁移已有订阅列表。
- 识别 Apple Podcasts 分享链接，使用官方 iTunes Lookup 取得公开 feed；Spotify、小宇宙、喜马拉雅的公开节目页链接可识别平台，但**不会伪造 RSS**——需要节目自身的公开 feed 才能订阅。
- 不绕过付费、地区限制、登录墙或 DRM；免费可听不等于取得转播授权。

详见 [平台接入说明](<docs/PLATFORM_ADAPTERS.md>)。

## 客户端能力

**Android**
- 资料和播放：订阅、单集列表、封面、简介、Media3 后台播放、媒体通知、锁屏与耳机控制、变速、跳转与进度。
- 多屏：根据当前窗口安全区、刘海/挖孔和折叠铰链调整底部导航、侧栏和播放器；支持窄长外屏、普通直屏、宽屏、折叠展开与横屏。
- 导入：公开 RSS/Atom、OPML、Apple Podcasts 分享链接；其他平台页面识别后提示补充公开 feed。

**iPhone / iPad**
- SwiftUI 通用应用；iPad 宽窗双栏，窄分屏和 iPhone 紧凑导航；支持四方向和辅助大字号。
- 已移除后端账号登录；订阅与单集元数据保存在本机，公开 feed 与 Apple 分享链接可直接解析。

## 构建

- Android：`cd android && ./gradlew :app:testCompactUnitTest :app:exportCompactApks`。
- Apple 模拟器：`swift test --package-path apple`，然后 `bash apple/build-simulator.sh output/apple`。
- GitHub Actions 的 `release` 只构建并上传客户端产物；服务端二进制不再进入 Release。

## 目录状态

```
podcastapp/
├── android/                # Android 客户端
├── apple/                  # iPhone / iPad 客户端与共享解析层
├── docs/                   # 平台接入、多屏适配与历史协议资料
├── server/  server-cpp/  deploy/  # 历史自建后端，已退出构建和发布（源码删除待确认）
```

多屏适配见 [Android 多屏适配](<docs/ANDROID_ADAPTIVE.md>) 与 [Apple 客户端](<docs/APPLE_CLIENT.md>)。
