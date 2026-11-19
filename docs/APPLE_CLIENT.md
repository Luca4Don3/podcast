# podcast：iPhone 与 iPad

一个原生 SwiftUI 客户端 target，最低 iOS／iPadOS 16。它和 Android 连接同一个自建后端，但功能不是完全对等。

- iPad 宽窗使用侧栏和详情双栏；窄分屏和 iPhone 由系统折叠为紧凑导航。iPad 声明四种屏幕方向，不强制全屏。
- 首版使用现有账号登录、查看订阅和单集、播放／暂停及拖动可定位的点播进度。先在现有客户端导入 RSS 并订阅；目前不支持注册、RSS 导入、离线同步、电台、私有文件音源或后台播放。
- 输入系统可验证证书的 HTTPS API 地址。登录密码和会话令牌不存盘；退出或重新启动需重新登录。Apple 客户端不会把播客账号令牌附在媒体请求里。

## 构建与安装

用装有完整 Xcode 的 Mac 打开 [Xcode 工程](<../apple/podcast.xcodeproj/project.pbxproj>)，运行 podcast scheme，分别选择 iPhone、iPad 模拟器。检查 iPad 横屏、竖屏、窄分屏，以及 iPhone 返回导航。可用一个仅含自有测试 RSS 的账号验证播放。

- 苹果 API 单测：`swift test --package-path apple`。
- 模拟器包：`bash apple/build-simulator.sh output/apple`。生成的 `podcast-<版本>-ios-simulator.zip` 是模拟器专用；可解压后在已启动的模拟器里安装其中的 `podcast.app`（例如 `xcrun simctl install booted podcast.app`）。不能装到真机。
- Apple 和 Android 的 CI 读取同一个版本号和递增构建号；候选版的 `-rcN` 保留在 tag／产物名中，Apple 的 marketing version 用数字部分。手动 CI 构建只产出 Actions artifacts，不发布或覆盖历史 Release。
- 真机调试请在 Xcode 为目标选择自己的开发团队。TestFlight／App Store 分发需具备已配置的 Apple Developer 团队、签名和审核流程；本仓库不内置签名凭证，不自动上传 App Store。

Linux、Windows、macOS 桌面播放器独立于 Apple 移动端 target，未随本次改动交付。服务器二进制不是桌面播放器。
