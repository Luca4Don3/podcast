# Podcast Linux

原生 GTK3 / GStreamer 播客播放器。不依赖浏览器、WebView 或自建服务端。

- **Open audio…** 打开本机普通音频文件；输入直接 HTTP(S) 音频 URL，点 **Play URL** 播放。
- 支持暂停/继续、停止、音量和进度；只有媒体可 seek 时才能拖动进度条。
- **Import feed URL** / **Open XML…** 导入 RSS 2.0、Atom 或 OPML。双击或按 Enter 播放单集／打开 OPML 的订阅；导入后的列表只保留在当前会话。OPML 不会自动抓取子订阅，程序也不自动播放媒体、不运行脚本。

## Debian 12 (Bookworm)

构建和无显示 smoke：

```sh
sudo apt-get update
sudo apt-get install --no-install-recommends build-essential cmake pkg-config \
  libgtk-3-dev libgstreamer1.0-dev libcurl4-openssl-dev libxml2-dev \
  gstreamer1.0-plugins-base gstreamer1.0-plugins-good glib-networking ca-certificates
# 可选：使 CTest 加入真实 X11 窗口 smoke
sudo apt-get install --no-install-recommends xvfb xauth

```

在仓库根目录执行：

```sh
cmake -S linux -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel 2
ctest --test-dir build --output-on-failure
cmake --install build --prefix "$PWD/.temp/podcast-linux-stage"
build/podcast-linux

```

产物是动态链接的 `build/podcast-linux`，安装后是 `<prefix>/bin/podcast-linux`，不捆绑共享库或 GStreamer 插件。只运行已编译程序的机器可安装：

```sh
sudo apt-get install --no-install-recommends libgtk-3-0 libgstreamer1.0-0 libcurl4 libxml2 \
  gstreamer1.0-plugins-base gstreamer1.0-plugins-good glib-networking ca-certificates \
  gstreamer1.0-alsa

```

需要本机有效的 X11/Xwayland 或 Wayland 会话及音频输出。已有 PulseAudio 或 PipeWire-Pulse 的桌面可用 `gstreamer1.0-pulseaudio` 替换 `gstreamer1.0-alsa`。WAV/MP3/Ogg 由 base/good 插件支持；AAC 等额外解码可按需安装 `gstreamer1.0-libav`。若 CJK 字体缺失可另装 `fonts-noto-cjk`。

## ABI 与验证

使用各架构的 Debian 12 原生用户空间与 GCC（实体机、虚拟机或 QEMU/binfmt 容器均可），同一份 C 源码各产出一个 ELF；不要把某架构的 ELF 当成通用二进制。Debian 官方 [GTK3 开发包](https://packages.debian.org/bookworm/libgtk-3-dev)覆盖下表，包括两种小端 MIPS。真正交叉构建需要目标 sysroot、对应编译器和目标 `pkg-config` 路径；不要链接宿主库。原生用户空间是推荐的验收路径。

| Debian 架构 | GNU triplet | ELF / ABI | 动态加载器 |
| --- | --- | --- | --- |
| amd64 | x86_64-linux-gnu | ELF64 little-endian x86-64 SysV | /lib64/ld-linux-x86-64.so.2 |
| i386 | i686-linux-gnu | ELF32 little-endian x86 SysV | /lib/ld-linux.so.2 |
| arm64 | aarch64-linux-gnu | ELF64 little-endian AArch64 LP64 | /lib/ld-linux-aarch64.so.1 |
| armhf | arm-linux-gnueabihf | ELF32 little-endian ARM EABI5 hard-float | /lib/ld-linux-armhf.so.3 |
| mipsel | mipsel-linux-gnu | ELF32 little-endian MIPS32r2 o32 | /lib/ld.so.1 |
| mips64el | mips64el-linux-gnuabi64 | ELF64 little-endian MIPS64r2 n64；不是 n32 | /lib64/ld.so.1 |

```sh
dpkg --print-architecture
readelf -hW build/podcast-linux
readelf -lW build/podcast-linux
readelf -AW build/podcast-linux
env -u DISPLAY -u WAYLAND_DISPLAY build/podcast-linux --smoke-test
GDK_BACKEND=x11 xvfb-run -a build/podcast-linux --window-smoke-test

```

核心 smoke 不需要显示、音频设备或公网，检查 URI、三种 XML、解析边界、取消，以及无声 WAV 管线的暂停/定位/EOS。窗口 smoke 启动同一个 GTK 窗口，确认窗口和输入框已映射后退出；CMake 在构建时检测 `xvfb-run`、`xauth`、`Xvfb` 后注册它（交叉构建不自动注册）。以上测试不等价于验证实际声卡或所有公网源。

## 网络与边界

仅接受无 URL 用户信息的 HTTP(S)，建议用 HTTPS。HTTPS 使用系统 CA；RSS/Atom/OPML 下载最多 5 次重定向，初始为 HTTPS 不降级到 HTTP，连接最多 4 秒、网络总预算 12 秒；媒体仅接受直链，不跟随重定向，不支持 HLS/DASH 等清单。导入上限 2 MiB、200 条、OPML 32 层，不加载 DTD、外部实体或 XInclude。音频通过 GStreamer 流式解码，不使用浏览器、后台服务或持久缓存。这是本机客户端，而非访问隔离服务：不会隔离私网地址；仅导入或打开自己信任的来源。此版本不支持来源认证、订阅持久化、账号同步或 DRM。
