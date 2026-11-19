# Windows 桌面版路线

状态：W0 最小原型已实现并通过 Windows CI（x64/ARM64 编译、缓存与启动测试）。还不是可用的桌面版：没有订阅/资料库界面、发布包签名或真机播放验收。Windows 是独立的纯客户端，不复用历史后端进程，也不要求注册。优先 Windows 11（仍在支持周期内的版本）、原生 x64 与 ARM64；不把 Windows App SDK 的 API 下限当成应用已支持的系统版本。

## 技术选择

| 选项 | 结论 |
| --- | --- |
| **C++ + Win32 + 按需 C++/WinRT** | 首选。使用系统控件和事件循环，直接复用现有 C++ 核心；只在调用系统 WinRT API 时使用 C++/WinRT。遵循系统主题、键盘焦点、DPI 和 Narrator，不用自绘替代已有系统控件。 |
| Rust 或 Zig + Win32 | 都是原生编译方案。若后续独立模块有内存安全、构建或实测收益，可通过 C ABI 单独评估；首版不重写现有 C++，也不为混用语言增加构建和维护成本。 |
| WinUI 3 + C++/WinRT + XAML | 仅作界面对照方案。若 Win32 达不到无障碍或交互验收，且实测满足资源预算，再决定是否引入 Windows App SDK。 |

桌面版不使用 Electron，不依赖 WebView2 做主界面，也不默认引入额外媒体运行时。语言只是起点，是否轻量以实测为准。

- **桌面交互**：左侧导航（首页、资料库、下载、来源），中间列表/详情，底部常驻播放栏，右侧队列可折叠。窄窗口保留在播状态并折叠侧栏。提供右键菜单、键盘焦点与快捷键，输入框中按空格不得误触播放。
- **系统播放**：优先用 `Windows.Media.Playback.MediaPlayer`。验证远程 HTTP(S) 和本地缓存的播放、跳转、倍速，以及系统媒体控制和媒体键。若系统播放器的默认媒体会话不满足需求，再验证桌面窗口手动接入，避免同一播放器出现两套控制。严格区分公开流和带凭据媒体；不能把自定义认证交给一个无法约束重定向的通用播放器连接。
- **本地数据**：订阅、单集、进度和队列放当前用户的 App 数据目录，缓存独立，系统凭据存储保护来源秘密。导入只读 RSS/Atom 与 OPML，解析禁止外部实体，本地状态与凭据分开，现有来源认证规则不降级。

## C++ 复用边界

- [共享缓存核心](<../apple/NativeCache/PodcastNativeCache.cpp>) 的数量规则、媒体文件头和文件名校验可复用；现有文件读写、发布和裁剪依赖 POSIX，**不能原样在 MSVC 构建**。
- 先把通用逻辑与存储实现拆开，保留现有移动端 C ABI。Win32 文件后端需验证 Unicode 路径、链接和 reparse point 拒绝、正规文件长度、同卷发布、失败保留旧文件、播放占用时安全延迟裁剪。不能用简单复制后覆盖替代同卷发布，也不把单测通过等同于断电耐久性保证。
- 现有 RSS/Atom、网络认证和下载流程部分在各平台代码中；它们是参考和测试素材，不是现成的 Windows 依赖。将续播排序、队列增删/重排、缓存数量策略优先做成不依赖平台的 C++ 模块，不重新实现音频解码或 TLS。

## 性能验收

- 同一台机器、同一系统版本，用 Release 构建和固定的订阅、音频样本测试空资料库、大资料库、暂停闲置、最小化播放和缓存刷新。分别记录冷启动至可操作的 p50/p95、私有工作集、CPU 时间与唤醒次数、安装体积和下载峰值内存；测试 x64，获取 ARM64 实机后同样跑一遍。
- 闲置或暂停时不持续轮询、不定时重绘播放器；最小化播放只保留必要的进度保存，不刷新不可见界面。大列表按需创建行，音频分块下载，封面内存缓存与下载并发均设上限。
- W0 留下实测基线，W1 开发前按基线设定资源预算；后续若超预算，先定位 UI、网络或媒体开销，不凭换语言宣称提速。

## 交付与验收

| 阶段 | 范围 | 验收门槛 |
| --- | --- | --- |
| W0 最小原型（已实现） | windows/ 原生 Win32 窗口、本地文件与公开 http(s) URL 播放、播放/暂停/停止/跳转/倍速、系统媒体会话；共享缓存核心 MSVC 移植 | Windows x64/ARM64 CI 编译通过；缓存与启动测试 2/2 通过。未验证：真机播放、媒体键、性能基线、ARM64 实机。 |
| W1 可用桌面版 | RSS/Atom、OPML、本机资料库、断点续播、可排序队列、四档离线缓存和刷新、已下载列表 | 自有测试 feed 完成从导入到播放；重启续播和队列顺序不变；断网可播放完整下载，部分传输不会被发布；系统媒体键在窗口最小化后仍生效。验证 100%/150%/200% 缩放、混合 DPI 双屏、键盘操作和 Narrator。 |
| W2 来源与分发 | 来源级 HTTP 认证/自定义 TLS、WebDAV、SMB、OneDrive 逐项接入；签名安装包 | Windows 先按各接口独立验收，不因移动端已有而自动标记可用；签名前只提供内部构建，签名后验收干净机器安装、升级保留资料库和卸载行为。按需要产出 x64/ARM64 MSIX 或合包。 |

安装签名、Windows 真机/交互桌面、Windows ARM64 设备和真实 NAS 联调均需独立验证；CI 编译通过不能替代它们。

## 官方资料

- [WinUI 3](<https://learn.microsoft.com/en-us/windows/apps/winui/winui3/>) · [Windows App SDK 平台要求](<https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/system-requirements>)
- [Windows MediaPlayer 控件](<https://learn.microsoft.com/en-us/windows/apps/develop/ui/controls/media-playback>) · [MediaPlayer 系统媒体控制](<https://learn.microsoft.com/en-us/uwp/api/windows.media.playback.mediaplayer.systemmediatransportcontrols>)
- [Windows 打包与 x64/ARM64](<https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/deploy-overview>) · [高 DPI 桌面应用](<https://learn.microsoft.com/en-us/windows/win32/hidpi/high-dpi-desktop-application-development-on-windows>) · [键盘快捷键](<https://learn.microsoft.com/en-us/windows/apps/develop/input/keyboard-accelerators>)
