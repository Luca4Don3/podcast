> 历史资料：当前纯客户端不依赖自建服务端；网络存储内容仅供旧版本参考。

# 服务器网络与 NAS／网盘接入

「服务器地址」用来连接本项目的播客 API；NAS 和网盘是音频存储。两者可位于同一台机器，也可以完全分开。

## 连接播客服务器

登录前或登录后进入 **服务器地址（可选） → 测试连接**。未指定路径时自动加 /api/v1/；反向代理可输入完整路径，例如 https://podcast.example/podcast/api/v1/。测试访问 healthz，不发送账号或令牌；成功表示播客 API 可达，不代表媒体文件也可播放。

| 方式 | 示例（用自己的地址替换） | 条件 |
| --- | --- | --- |
| HTTPS | https://podcast.example/ | 推荐；证书须受 Android 系统信任且与主机名匹配，不跳过校验。 |
| HTTP | http://192.168.1.20:8080/ | 显式写 http://；仅用于可信内网或确认受保护的网络路径。手动登录、令牌和内容在 HTTP 层本身不加密；含密码的导入链接仍只接受 HTTPS。 |
| Tailscale + HTTPS | https://nas.example.ts.net/ | 手机与服务设备须在可互访的 tailnet 中。可用 [MagicDNS](https://tailscale.com/docs/features/magicdns) 和 [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve) 提供 tailnet 内的 HTTPS；App 自身不建立 VPN。 |
| Tailscale + HTTP | http://100.64.1.2:8080/ 或 http://[fd7a:115c:a1e0::12]:8080/ | 手机先连接 Tailscale。服务须监听可达地址，端口、tailnet 访问规则和防火墙须放行。Tailscale 封装不等于 HTTP 自身启用 TLS。 |

如果反向代理重定向到 HTTPS，直接填写最终的 HTTPS 地址。DNS 错误先确认手机的 Tailscale 没有把 App 排除、MagicDNS 已启用、主机名正确；IP 通而域名不通通常要查 DNS，而不是重输密码。不要为了连通而暴露管理面板或关闭 TLS 验证。

如果 API 已通过[项目 Compose 配置](<../deploy/docker-compose.yml>)映射到宿主机 `127.0.0.1:8080`，可保留回环绑定，让同一宿主上的 Tailscale Serve 将该端口代理到 tailnet 的 HTTPS 地址。Compose 的默认回环映射不能直接通过设备的 Tailscale IP 加端口访问；IPv6 地址通过客户端解析也不代表后端已启用可达的 IPv6 监听。无需为 Serve 开放公网端口。

## 极空间与网盘

| 场景 | 可行做法 | 当前客户端边界 |
| --- | --- | --- |
| 在极空间跑播客 API | 以设备后台实际提供的容器功能为准。比如 [极空间 T6](https://www.zspace.cn/t6/) 官方资料列出 Docker／Compose。部署前核对对应机器的 CPU 架构、镜像、持久化目录和端口；没有容器功能可在另一台主机跑后端。先确认宿主机的后端健康检查通过，再设置 Serve；不要仅凭 Compose 端口映射判断 API 已可达。 | 手机填写播客 API 地址，不填极空间管理地址。未实测具体型号部署。 |
| 极空间只存音频 | 具备 SMB 的机型（如 [极空间 Z4](https://www.zspace.cn/z4/)）可供有权限的服务器只读挂载；若设备软件提供 WebDAV，也可单独进行有授权的读取集成。之后由媒体服务提供 HTTP(S) 音频，并用 RSS 订阅。 | App 目前不浏览 SMB／WebDAV 目录，也不把管理页或分享页当作音频流。 |
| 网盘文件 | 优先用提供方官方接口，例如[坚果云 WebDAV](https://help.jianguoyun.com/?p=2064) 的应用专用密码，或 [OneDrive／Microsoft Graph](https://learn.microsoft.com/en-us/graph/api/driveitem-get-content?view=graph-rest-1.0) 的授权下载。 | 尚无网盘账号适配器。普通分享网页不能直接导入；稳定且有权使用的音频直链可通过 RSS enclosure 提供。临时授权地址会过期，不适合作为长期订阅地址。 |

验证媒体时，至少检查 RSS 和音频分别可访问，音频请求返回真实音频而非 HTML，拖动进度正常（服务端宜支持 Range）。私有文件由有权限的代理或官方接口提供，不抓取分享网页内部接口、不绕过登录或提取码。

建议下一步先开发独立的只读文件音源模块：与播客服务器登录分开，优先做 HTTPS WebDAV 与服务器端只读目录；浏览目录、选文件，再验证权限、续播与 Range。飞连、FTP／SFTP、NFS、SMB 及各对象存储与个人网盘的建议见[协议适配矩阵](<PROTOCOL_ADAPTERS.md>)。

## 验收清单

- 在同一手机分别测试局域网 HTTP、系统信任证书的 HTTPS、手机移动数据下的 Tailscale IP 和 MagicDNS；再断开 VPN，检查提示和重新联网后的离线进度补传。
- 在另一连接保持可用时断开原 Wi-Fi；原网络的丢失事件不能把新默认网络标为离线。没有可用路由时才显示离线；网络可用不代表特定服务器一定可达。
- 测试无效证书、跳转、NAS 管理页，确认连接测试失败且不发送认证信息；测试带密码的 HTTP 导入链接被拒绝。

1.3.2 精简版在宿主 JVM 上通过 77 项测试，包含 IP／MagicDNS 样式地址解析、HTTP 健康检查、HTTPS 导入、拒绝重定向与旧路由丢失回调。同一包体签名、四 ABI 和 16 KiB 对齐检查通过。没有真实 tailnet、极空间或网盘播放联调结果。
