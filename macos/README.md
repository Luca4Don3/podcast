# podcast for macOS

独立桌面客户端，最低 macOS 13.0。使用 AppKit / Objective-C++、AVFoundation 与系统 libxml2，复用 Apple 目录里的可移植 C++ 单集规划逻辑；不需要自建服务端，也不构建 iOS 工程。

## 构建 Universal 2

在 macOS 上安装 Xcode 或 Command Line Tools，以及 CMake 3.22 或更新版本。在仓库根目录执行：

```sh
cmake -S macos -B build/macos -G 'Unix Makefiles' \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_ARCHITECTURES='x86_64;arm64'
cmake --build build/macos --parallel
ctest --test-dir build/macos --output-on-failure
lipo -archs build/macos/podcast.app/Contents/MacOS/podcast
codesign --verify --strict --verbose=2 build/macos/podcast.app
```

单配置生成器产物是 `build/macos/podcast.app`（如果指定 `-B build`，则为 `build/podcast.app`）。上述构建应输出 `x86_64 arm64` 两个切片；在两种 Mac 上分别运行时，系统选择对应切片。用 `open build/macos/podcast.app` 打开。CI 可直接运行 `build/macos/podcast.app/Contents/MacOS/podcast --smoke-test`，不需要登录桌面、接入网络或使用扬声器。

## 使用与验收

- **Open Audio File…**：打开本机音频；或把 HTTP(S) 音频直链粘贴到地址框，点 **Play URL**。使用 **Play / Pause** 控制播放，播放器报告可定位区间后才启用滑块；无区间的直播或流式来源可能不可定位。音频容器、编码和网络兼容性由系统 AVFoundation 决定。
- **Import URL**：输入公开 RSS 2.0、Atom 或 OPML 地址。**Import XML File…** 同样支持本地 RSS、Atom、OPML 文档。双击单集或选择后点 **Play Selected**。只接受 HTTP(S) enclosure；相对 URL 需要 HTTP(S) 来源或适当的 `xml:base`。每份 XML 上限 4 MiB，单 feed 最多导入 500 条，OPML 最多 100 个带地址的 outline。OPML 中的 feed 逐个下载，可取消并保留已导入的单集；不会递归导入嵌套的 OPML 文档。
- 普通 HTTP 用于兼容旧播客源，不加密，建议优先使用 HTTPS。仅导入用户提供的来源及其引用的地址；不接受 URL 内嵌用户名和密码。当前版本的导入列表仅在本次会话保留，不包含订阅持久化、自动刷新、离线缓存、进度保存、平台分享链接解析、登录或 DRM 功能。
- CTest 覆盖 RSS、Atom、OPML 解析及输入边界，并以生成的本地 WAV 验证 AVPlayer 就绪、时长和定位。手动验收另需：用本机音频确认出声和播放/暂停；用直链分别确认 HTTP 和 HTTPS；用包含成功和失败 feed 的 OPML 检查列表、进度提示和取消；用不可定位的直播检查滑块禁用。离线测试不能代替这些 GUI 和网络验收。

## 签名边界

默认构建对成品做 **ad hoc 签名**，使用 `codesign --sign -`，不需要证书或私钥。仅用于本地及 CI 验证，不能当成已通过 Gatekeeper、公证或可对外分发的包。

发行方若拥有 **Developer ID Application** 身份，请用独立构建目录（例如 `build/macos-release`），保留双架构和 Release 参数，再加 `-DPODCAST_ADHOC_SIGN=OFF` 禁用 bundle 的 ad hoc 签名步骤。构建后在发行方受控环境签名：

```sh
codesign --force --options runtime --timestamp --sign 'Developer ID Application: <name> (<team-id>)' \
  build/macos-release/podcast.app
codesign --verify --strict --verbose=2 build/macos-release/podcast.app
```

之后由发行方完成封装、公证和 stapling。仓库和构建不创建、不导入签名私钥，也不执行正式签名或公证。
