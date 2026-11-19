# Windows W0 原生原型

纯 C++17 + Win32 窗口 + C++/WinRT（仅用于系统媒体播放器），无 Electron、无 WebView2、无 Windows App SDK、无后端。产物约 195 KB（x64）/ 187 KB（ARM64）。

## 当前功能

- 打开本地音频文件或输入公开 http(s) URL（拒绝含用户信息的地址），播放/暂停、停止、±15/30 秒跳转、倍速切换。
- 使用 Windows 系统 MediaPlayer，自动接入系统媒体控制；进度由事件驱动，暂停/闲置不轮询。
- 复用共享缓存核心的数量策略、媒体文件头和文件名校验；Windows 文件后端用 Win32 API，拒绝链接与多硬链接，同卷原子发布。

## 构建与验证

在装有 MSVC 与 Windows SDK 的环境，用 CMake 配置 windows/ 目录（-A x64 或 -A ARM64），Release 构建，再用 ctest 运行缓存与启动测试。
启动冒烟：podcast.exe --smoke-test（创建窗口与播放器后立即退出，不访问网络）。CI 同时交叉编译 ARM64。

## 边界

这不是可用的桌面版：没有订阅/OPML、资料库、断点续播、队列、缓存界面或自动更新；没有签名与安装包；没有真机播放、媒体键、混合 DPI 与性能实测。后续范围见 docs/WINDOWS_CLIENT.md。
