> 历史资料：文件音源属于旧的自建后端方案。1.5.0 客户端不再以内置服务端作为分发入口；公开播客请通过 RSS/平台分享链接接收。

# 文件音源（C++ 后端 + Android）

入口：Android 登录后，进入「导入 → 文件音源」。新功能默认关闭，旧 RSS、电台和 Go 后端不受影响。需要有权限的文件，不支持分享网页、直播播放列表、上传或删除。

## 只读目录（NAS 的 SMB／NFS 挂载）

1. 在 Linux 后端宿主机上先准备一个**专用音频目录**：可以是本地目录，也可以是管理员用只读账号挂载的 SMB 2/3 或 NFS 目录。不能指向系统目录、数据库或用户主目录。保留只读挂载和 NAS 自身的只读访问限制。
2. 确认 C++ 服务运行身份可读取目录。Docker 部署时只把该目录以只读方式挂载到容器，如 /media/audio，再使用容器内绝对路径。服务不会执行挂载或接受任意客户端路径。
3. 先注册账号，登录后从 GET /api/v1/me 的 user.id 取得对应 ID。管理员填写 FILE_MEDIA_OWNER_ID 和 FILE_MEDIA_ROOT，再按现有流程部署新版 C++ 后端。未配置、其他账号或 Go 旧后端不会显示目录。

运行行为：目录每层最多 500 个原始条目，过多时请分子目录；显示受支持的常见音频文件（MP3、M4A/M4B、AAC、WAV、FLAC、OGG/Opus）。非普通文件和符号链接不作为音源。读文件按区间分块，不把整个文件放进内存。真实 SMB／NFS 性能和阻塞行为取决于 NAS、宿主机挂载和网络。

## HTTPS WebDAV（编译时可选）

C++ 构建时启用 PODCAST_ENABLE_WEBDAV=ON，需要 libcurl 7.62+ 和 libxml2 开发包；Docker 构建可用同名 build arg。运行时填写 FILE_MEDIA_DAV_URL（最终 HTTPS WebDAV **目录**）、FILE_MEDIA_OWNER_ID。需要 Basic 鉴权时同时设置 FILE_MEDIA_DAV_USER 和 FILE_MEDIA_DAV_PASSWORD_FILE，把密码通过仅服务用户可读的文件提供，勿填在 URL 中。没有鉴权则两项留空。

后端按系统信任链验证 TLS 证书，不跳转。客户端不会接收 WebDAV 凭据。建议选专用只读账号，以便在 NAS／网盘侧再限制目录。WebDAV 上游须支持长度查询和正确的字节范围响应才能可靠拖动；请分别验证列目录、顺序读及非零偏移。

## 授权与使用限制

- 当前一个服务实例由管理员把文件源授权给一个明确的已有账号 ID；不根据邮箱猜测、不提供公开目录。文件 URL 只携带针对该文件的随机播放票据，2 小时有效，重启服务器失效。用完不保存在资料库，失败可重新选文件。
- 播放票据本身有权限。移动端到后端建议 HTTPS；不要在反向代理的访问日志里记录流媒体 URL 查询串，也不要公开分享播放地址。
- 本轮文件播放不写入现有播客进度库，不提供跨设备断点同步。飞连／Tailscale 如有授权的系统路由，可用于访问后端，App 不建立 VPN。SFTP／FTP、直接 SMB 客户端、阿里云／百度云专用适配尚未实现；提供标准 HTTPS WebDAV 的服务可在相应权限下尝试。

## 构建与验收

- Linux x86_64 测试二进制需 glibc 2.38+、GLIBCXX_3.4.31、OpenSSL 3 和 SQLite 共享库；WebDAV 版还需 libcurl 与 libxml2。其他系统（包括 ARM NAS）请用本机编译或按 [Dockerfile](<../server-cpp/Dockerfile>) 为目标架构构建，不直接运行 x86_64 测试件。
- 后端已以 WebDAV 开启和关闭两种方式构建；验证了 HTTPS 回环连接、鉴权、目录、定长 Range 与拒绝错误上游响应。只读目录黑盒测试覆盖账号隔离、Unicode、HEAD、206/416、64 MiB 文件尾部读取及票据重启失效。未对真实 NAS、网盘或手机做联调，未部署或重启现有服务。

## 平台构建现状

- Android 手机与平板共用同一包，已在 GitHub Actions 成功构建并导出通用包及四种 ABI 包。
- 自托管后端：Linux x86_64 在 CI 中构建成功。macOS 与 Windows 后端尚无成功构建记录：macOS 因托管 runner 容量不足被取消，Windows 存在 POSIX 时间与 socket API 依赖，尚未完成移植。
- 另有 [iPhone／iPad 客户端](<APPLE_CLIENT.md>)，当前不提供文件音源浏览；Linux、Windows、macOS 桌面播放器尚未交付。服务器二进制不是桌面客户端。
