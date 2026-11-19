# podcast：iPhone 与 iPad

一个原生 SwiftUI 客户端 target，最低 iOS／iPadOS 16。它和 Android 连接同一个自建后端，但功能不是完全对等。

- iPad 宽窗使用侧栏和详情双栏；窄分屏和 iPhone 由系统折叠为紧凑导航。iPad 声明四种屏幕方向，不强制全屏。
- 当前版本无需账号：添加公开 RSS/OPML 或 Apple Podcasts 分享链接，订阅与单集元数据保存在本机；支持播放／暂停及拖动可定位的点播进度。Spotify、小宇宙、喜马拉雅分享链接只识别平台，需节目自身公开 feed；暂不支持注册、离线同步、电台、私有文件音源或后台播放。
- 输入系统可验证证书的 HTTPS API 地址。登录密码和会话令牌不存盘；退出或重新启动需重新登录。Apple 客户端不会把播客账号令牌附在媒体请求里。

## 构建与安装

用装有完整 Xcode 的 Mac 打开 [Xcode 工程](<../apple/podcast.xcodeproj/project.pbxproj>)，运行 podcast scheme，分别选择 iPhone、iPad 模拟器。检查 iPad 横屏、竖屏、窄分屏，以及 iPhone 返回导航。可用一个仅含自有测试 RSS 的账号验证播放。

- 苹果 API 单测：`swift test --package-path apple`。手动运行 GitHub Actions 的 `release` 时可勾选 `apple_launch_smoke`，在新建的 iPhone、iPad 模拟器上验证安装和启动。此检查不连接生产服务，也不代替登录、播放和分屏布局验收。
- 模拟器包：`bash apple/build-simulator.sh output/apple`。生成的 `podcast-<版本>-ios-simulator.zip` 是模拟器专用；可解压后在已启动的模拟器里安装其中的 `podcast.app`（例如 `xcrun simctl install booted podcast.app`）。不能装到真机。
- Apple 和 Android 的 CI 读取同一个版本号和递增构建号；候选版的 `-rcN` 保留在 tag／产物名中，Apple 的 marketing version 用数字部分。手动 CI 构建只产出 Actions artifacts，不发布或覆盖历史 Release。
- 真机调试：在 Xcode 的 **Signing & Capabilities** 为 `podcast` target 选自己的 Team，启用自动签名；如果默认 bundle identifier 不归你的团队，请改用团队可用的唯一标识。连接 iPhone 或 iPad、开启开发者模式，选择对应物理设备运行。
- TestFlight：使用有权限的 Apple Developer 团队和同一 bundle identifier 在 App Store Connect 创建或选用既有 app，然后从 Xcode 对通用 iOS 设备 Archive，经 Organizer 验证和上传。应用图标、隐私资料及发布审核另行准备。本仓库不存储签名凭证，不自动上传 TestFlight／App Store。

[iPhone／iPad 模拟器 CI](<https://github.com/Luca4Don3/podcast/actions/runs/44430683923>) 已通过两种设备的安装、启动，以及 10 项 API 单测。尚未进行签名真机验收。

## 多屏布局验收

对照 Apple 的[布局](<https://developer.apple.com/design/human-interface-guidelines/layout>)、[Dynamic Type](<https://developer.apple.com/design/human-interface-guidelines/typography#Supporting-Dynamic-Type>)和[无障碍](<https://developer.apple.com/design/human-interface-guidelines/accessibility>)指南。界面使用系统安全区和当前窗口尺寸类别，不按物理屏幕宽度决定导航。iPhone 声明竖屏和左右横屏，iPad 声明四方向并允许调整窗口大小。

- 小屏或短窗口：确认软键盘弹出时表单仍能滚动，底部播放器不会遮挡列表；点击底栏标题可查看完整、可滚动的播放器。
- 标准字号和最大辅助字号：检查输入标签、长标题和播放控制。用 VoiceOver 检查字段、按钮和进度读数。
- iPad 窄分屏和宽窗口：检查导航返回、窗口调整及横竖屏切换，确认选择与播放状态保留。多型号的自动安装／启动不能替代这些交互检查。

使用专用测试账号，准备至少一个有可定位 HTTPS 单集的订阅。以下为交互验收步骤，不等同于 CI 的启动测试：

1. 登录后在宽屏选择订阅：左侧保留列表，右侧显示匹配单集；切换订阅时不闪回旧单集。
2. 把窗口缩到窄分屏：导航折叠成单栏，可从详情返回订阅。再放宽窗口并旋转，核对导航和当前选集。
3. 播放单集、暂停、拖动进度；没有可定位区间的音源不显示进度滑块。注销后确认播放器和订阅信息清空。

Linux、Windows、macOS 桌面播放器独立于 Apple 移动端 target，未随本次改动交付。服务器二进制不是桌面播放器。
