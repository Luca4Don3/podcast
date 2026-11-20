# Windows 原生预览版

C++17、Win32 系统控件和 Windows MediaPlayer；不需要账号、播客服务端、Electron 或 Windows App SDK。

## 使用

1. 在订阅地址栏粘贴公开 RSS/Atom 地址并点「添加订阅」，或选择「导入 OPML」打开本地文件。网络检查在后台执行，可取消；刷新失败不会用空结果替换旧订阅。单个 XML 限 4 MiB，OPML 最多 100 条带订阅地址的项目。
2. 选中订阅后选择单集，「播放单集」或双击播放；也可直接打开本地文件或加载公开 HTTP(S) 音频。
3. 用「移到队首」「加入队尾」管理待播，可手动播放、上移、下移或移除。编辑时不会切换当前媒体；队列顺序保存在本机。当前不自动连播。

## 构建与验证

用 MSVC 和 Windows SDK 配置 CMake：x64 用 `-A x64`，ARM64 用 `-A ARM64`，构建 Release 后运行 CTest。[Windows 工作流](<../.github/workflows/windows.yml>) 分别检查 runner 架构、EXE 的 PE Machine、运行测试并上传 `windows-x64` 和 `windows-arm64`。

## 边界

仍是未签名预览版。尚未接入四档自动离线缓存和断点续播；媒体键、真实 Windows 设备的播放、DPI 和性能仍待人工验收。详情见 [Windows 桌面路线](<../docs/WINDOWS_CLIENT.md>)。
