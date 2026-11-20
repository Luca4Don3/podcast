# Linux 与 macOS 桌面预览

桌面端直接连接用户提供的公开音源，不要求账号或自建服务。Linux 使用 C11 与 GTK 3／GStreamer，macOS 使用 Objective-C++、AppKit／AVFoundation；两端不捆绑浏览器运行时。

## 架构与产物

| 平台 | 架构 / ABI | 构建产物与验证方式 |
| --- | --- | --- |
| Linux（基于 Debian 12 用户空间） | `amd64`、`i386`（i686） | 各自的 ELF 可执行文件和启动入口；x86 runner 上检查 ELF 并运行 CTest。 |
| Linux（基于 Debian 12 用户空间） | `arm64`（AArch64）、`armhf`（ARMv7 硬浮点） | `arm64` 使用 ARM runner；`armhf` 使用 QEMU 用户态。 |
| Linux（基于 Debian 12 用户空间） | `mipsel`（MIPS32r2 / o32）、`mips64el`（MIPS64r2 / n64） | 小端 MIPS 两个独立包；使用 QEMU 用户态。未覆盖大端 MIPS、软浮点或 OpenWrt/musl。 |
| macOS 13 及以后 | Intel `x86_64`、Apple Silicon `arm64` | 同一个 Universal 2 `.app` 含两个切片；构建检查切片，各自 runner 运行 CTest。预览为 ad hoc 签名，不是公证发行包。 |

[桌面预览工作流](<../.github/workflows/desktop-preview.yml>) 与 Android/iPhone/iPad、Windows 发布隔离。工作流上传预览产物，不创建 Release 或改动历史产物。判定兼容性以实际通过的作业为准。Linux tar.gz 不内置系统图形、音频和编解码库，不能从 CPU 名称推断发行版兼容。

## 验收边界

- ELF / Mach-O 检查只证明二进制架构正确；QEMU 和虚拟显示冒烟不替代物理设备、真实声卡、HiDPI 或发行版兼容性测试。
- 默认测试使用合成数据并避免访问用户订阅或私人账号。请用自己有权访问的 RSS 与音频验证交互、解码、播放/暂停和定位。
- 预览范围不等同于移动端：本机音频播放不等于自动离线下载，四档离线缓存、网盘/NAS、来源认证、系统媒体控制、桌面签名公证均不得从架构构建结果推断。
