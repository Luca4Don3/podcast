> 历史调研：这里记录过 NAS/WebDAV 等后端协议设想，并非当前客户端分发能力。当前接入范围见 [平台接入说明](<PLATFORM_ADAPTERS.md>)。

# 企业网络、NAS 和云盘适配调研

状态：**方案与公开文档核查，不是已实现清单**。现有 Android 1.3.2 可连接 HTTP(S) 播客 API，可按网络路径使用 Tailscale；尚未对接下表的文件连接器，也未实测飞连租户、NAS、云盘 OAuth 或设备组合。

## 1. 统一入口，分开实现

| 用户界面 | 管什么 | 谁保管凭据 |
| --- | --- | --- |
| 设置 → 服务器地址 | 登录自建播客 API、测试 HTTP(S) 可达性。飞连和 Tailscale 在系统或企业终端层接入，不另造 URL 协议。 | 播客 App 继续使用自己的会话。企业账号与设备策略由飞连／Tailscale 管理。 |
| 导入 → 文件音源（拟增） | 添加授权来源、浏览目录、选择音频、继续收听。按「NAS／主机」「对象存储」「个人网盘」选择类型，不与服务器地址混用。 | 私有存储凭据只交给授权连接器；独立于播客登录，短时下载地址不入资料库。 |
| 导入 → RSS／电台（已存在） | 保留现有 HTTP(S) 直连流程，不把文件协议、直播流和分享网页混为一谈。 | 不向陌生媒体主机附带播客或网盘凭据。 |

建议 Android 仍用 Media3，通过 HTTPS 播放；协议适配优先放在 **C/C++ 可选服务端连接器**。不要在 APK 内集成所有 SDK，也不为每家厂商复制播放器。Docker 不是设备兼容证明：极空间、群晖、QNAP、飞牛等按**型号、系统版本和实际开通的协议**验收。NAS 若只能存文件，可把连接器部署在有访问权限的另一台主机。

## 2. 网络接入矩阵

| 类型 | 路径 | 边界／待验证 |
| --- | --- | --- |
| HTTP(S) | 手机直接访问自建播客 API。HTTPS 校验证书和主机名，HTTP 仅用于可信内网或受保护链路。 | 目前带密码的导入链接仅 HTTPS；不改默认安全策略。 |
| Tailscale | 官方客户端或受管理网络已上线后，用机器可达 IP、MagicDNS 域名或 [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve) 的 HTTPS 域名。 | 依赖 tailnet 策略、DNS 和服务监听，App 不建立 VPN。 |
| 火山引擎飞连 | 使用[飞连官方 Android 客户端](https://docs.volcengine.com/docs/feilian/download-client)和租户授权。网络层 VPN 模式下，若目标域名/IP、端口与本 App 的流量均在授权路由内，仍填写正常的 HTTP(S) 地址；极速分流只引入管理员配置的资源。 | 「VPN 路由可达」和「浏览器已过 SSO」是两回事：[飞连应用网关](https://docs.volcengine.com/docs/feilian/application-gateway-overview)校验授权后从浏览器反向代理，不意味着原生 App 可以直连内网源站。需验收移动端策略、企业 DNS、证书、账号有效期和第三方 App 放行，不导入 SSO Cookie，不绕开设备合规。若指的是飞牛 NAS，则归到 NAS 一栏，不当作飞连接入。 |

## 3. NAS 和服务器文件协议

| 类型 | 建议适配 | 首次验收边界 |
| --- | --- | --- |
| SMB 2/3（Samba 为服务端实现之一） | 首选 Linux 宿主机用受限只读共享挂载，然后复用本地目录连接器。确有无需挂载的需求，再评估支持 SMB 2/3 的 `libsmb2` 或 Samba 客户端组件。 | 不启用 SMB1；优先 SMB3，按服务配置要求签名或加密；账号只读、只授权指定 share。不把 SMB 服务暴露公网。 |
| NFSv4 | 宿主机只读挂载指定导出目录，容器只见该目录且仍只读。优先固定授权的主机和身份映射。 | 无需 App 或容器有挂载权限；明确导出范围、UID/GID 或 Kerberos 策略。不要把 NFS 当通用账号密码协议，不跨公网裸露。 |
| WebDAV over HTTPS | 连接器用有界的 `PROPFIND Depth: 1` 查目录，为 App 输出有界列表；不假定远端提供通用分页。按需 GET，验证是否实际支持 Range。支持 NAS 上已启用的标准 WebDAV，以及官方提供应用密码的服务。 | 限制目录深度和响应大小；自签名证书需管理员部署受信任证书或明确维护信任根，不能关闭校验。WebDAV 登录成功不等于可拖动。 |
| SFTP | SSH 上的文件协议。采用 libssh2 或 libcurl 带受支持的 SSH 后端；服务器验证主机密钥，读取指定根目录，支持按偏移读取。 | 主机密钥变化须重新确认；采用受限只读身份，可用密钥，不使用文件型 URL 存密码。无预期目录或权限时清楚报错。 |
| FTPS / FTP | FTPS 优先独立验证 TLS 控制和数据连接；FTP 只为存量设备兼容，默认关闭，由管理员限可信内网启用。 | FTPS、FTP、SFTP 是不同选项；不做自动降级或猜类型。拖进度需验证服务端的偏移读取（FTP/FTPS 的 REST 支持）；不能随机读时不宣称支持 seek。 |
| Android 系统文件（可选） | [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider) 允许用户选择系统或第三方文件提供者暴露的音频，作为本机播放补充。 | 依赖厂商实现，不能替代服务端文件接入；系统授权 URI 不作为跨设备音源，不承诺所有云盘或旧 Android 都能列目录。 |

参考：[SMB 功能与加密](https://learn.microsoft.com/en-us/windows-server/storage/file-server/smb-feature-descriptions)、[Linux NFS 挂载选项](https://man7.org/linux/man-pages/man5/nfs.5.html)、[WebDAV RFC 4918](https://www.rfc-editor.org/info/rfc4918/)。[rclone WebDAV 网关](https://rclone.org/commands/rclone_serve_webdav/)可作为**可选部署或联调工具**；其 Go 二进制不随 APK 分发，也不用它替代默认 C++ 后端。是否采用由实测读速和各后端所支持的能力决定。

型号验收示例：极空间先核对[实际机型提供的文件与共享服务](https://www.zspace.cn/software/index.html)，不要从「支持 SMB／NFS」推断精确方言。群晖可对照 [DSM 服务说明](https://kb.synology.com/en-us/DSM/tutorial/What_network_ports_are_used_by_Synology_services)与实际套件配置。QNAP [QTS 5.2 SSH 设置](https://docs.qnap.com/operating-system/qts/5.2.x/en-us/configuring-ssh-connections-928DF42B.html)明确 SSH 仅限管理员账号；如果设备 SFTP 不能以受限只读身份访问，改用 SMB／WebDAV，而不是给播放器管理员账号。libcurl 的协议支持取决于构建，不用其 SMB 实现替代 [SMB 2/3 客户端](https://github.com/sahlberg/libsmb2)。

## 4. 云服务矩阵

| 类型 | 建议接口 | 边界／验证条件 |
| --- | --- | --- |
| 阿里云 **OSS** | 符合官方签名规则的 API/SDK，配置固定 bucket、region、prefix，使用只读 RAM/STS 权限；按 [GetObject](https://help.aliyun.com/en/oss/developer-reference/getobject) 读取字节范围。 | OSS 与阿里云盘不是一套授权。适配列表和仅收听所需读权限，签名按厂商要求，不保存临时 URL。 |
| 百度智能云 **BOS** | [BOS GetObject API](https://cloud.baidu.com/doc/BOS/s/xkc5pcmcj)，同样用只读 bucket/prefix 与按需下载。 | 与百度网盘不是一套产品。签名、分页、区域和 Range 以 BOS 实测为准，不假定完全等价于 S3。 |
| S3／腾讯云 COS／华为云 OBS | 按 [S3](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html)、[COS](https://cloud.tencent.com/document/product/436/7753)、[OBS](https://support.huaweicloud.com/api-obs/obs_04_0083.html) 分别做配置和验证。成熟 SDK 或外接网关优先，不自行发明签名算法。 | API 有相似处但不是一个驱动无条件兼容；逐家验证签名、区域、endpoint、分页、范围读取，以及请求费用与流量。 |
| 阿里云盘（个人） | [阿里官方 SDK](https://github.com/alibaba/aliyunpan-ios-sdk) 提供个人盘 OpenAPI、应用授权及按需取下载 URL 的示例（不意味着要引入 iOS SDK）。实现前需取得应用资格，确认回调、文件读取范围及分发条款。 | [官方版本对比](https://help.aliyun.com/zh/pds/product-overview1) 区分 PDS 开发者版与阿里云盘企业版，不能把 [PDS 下载 API](https://help.aliyun.com/zh/pds/drive-and-photo-service-dev/developer-reference/api-pds-2022-03-01-getdownloadurl) 当成个人盘接口。个人盘实际审核资格、下载地址有效期及 Range 未验证。 |
| 百度网盘（个人） | [百度网盘开放平台](https://pan.baidu.com/union/VIPdocs/Wlkhx4p8v) 的授权、文件列表、下载接口；播放时取得新下载地址，校验实际权限和配额。 | 普通分享页、提取码和 OAuth 是不同事；需要应用审核与授权。默认目录为 `/apps/{appname}`，不能假定全盘可读；文档有 Range 流程，媒体权限、跳转及配额仍需实测。 |
| 坚果云／OneDrive／Google Drive | 坚果云使用[官方 WebDAV](https://help.jianguoyun.com/?p=2064)；OneDrive 使用 [Microsoft Graph](https://learn.microsoft.com/en-us/graph/api/driveitem-get-content?view=graph-rest-1.0)；Google Drive 使用 [Drive API](https://developers.google.com/workspace/drive/api/guides/manage-downloads) 的授权文件下载。 | Graph 的 Range 要在实际下载 URL 上请求；Google 优先使用仅访问所选文件的 [drive.file 授权范围](https://developers.google.cn/workspace/drive/api/guides/api-specific-auth?hl=en)，不能把它理解为全盘权限。短时链接过期需重新获取。 |
| 其他网盘 | 先确认官方 API、WebDAV 或系统文件选择器，再决定是否加连接器。只开放分享网页的服务保留浏览器打开。 | 不用私有接口，不抓核验流程，不请用户贴 Cookie、真实密钥或完整签名链接。 |

## 5. 首批实现契约与分期

**代码现状决定先做播放网关。** 当前 [C++ 回包结构](<../server-cpp/src/http.hpp#L33-L42>) 的正文是内存中的字符串，[RSS 抓取结果](<../server-cpp/src/fetch.hpp#L21-L41>) 也在内存中；两条路径都不适合承接长音频。当前 [Android 单集播放](<../android/app/src/main/java/com/podcastapp/playback/PlaybackHolder.kt#L499-L509>) 直接把 audioUrl 交给 Media3，不能自动处理云盘换签或把凭据安全转发。保留旧 RSS／电台路径；新增媒体网关可作为独立 C++ 进程或使用独立、限流的流式 I/O，不把完整音频读进内存，不重编码。

| 阶段 | 交付内容 | 放行条件 |
| --- | --- | --- |
| 1：统一能力 | 只读源：已授权宿主目录（覆盖 SMB／NFS 挂载）与 HTTPS WebDAV。App 用统一「文件音源」入口，服务端统一 列目录／文件信息／按范围读，并提供授权播放。 | 使用测试源打通目录、播放、拖动、续播、账号隔离；现有 RSS、电台、Android 7.0+ 四 ABI 不回归。 |
| 2：扩展来源 | SFTP、S3 兼容服务和阿里云 OSS／BOS 等对象存储；飞连与 Tailscale 在阶段 1、2 并行实测。 | 接入选项须运行时确认协议能力，每家对象存储有测试环境，飞连管理员确认策略且通过移动端联调。 |
| 3：厂商授权与旧协议 | 百度网盘、阿里云盘、OneDrive 等按应用审核与用户需求逐家接；FTPS／FTP 做可选兼容，再决定是否需 SMB 直连。 | 具备合法应用授权，验收断网、授权撤销、签名过期、限流；没有授权的服务只列为待接入，不显示为已支持。 |

### 验收项

- **授权与隔离**：管理员显式授权目标服务器与目录，私有源需要账号级授权；每次列目录和播放均检查归属，音源 ID 不等于授权。凭据与业务库分开管理、可撤销；日志不写真实密码、令牌、签名参数。不给客户端通用任意 URL 转发入口。
- **文件与范围**：分页与中文文件名、空目录、超大文件、网络断开、文件移动或变更；支持随机访问时检查 GET／HEAD 和单段 Range 的 206／416 及 Content-Range；远端不支持时标注不可拖动或先下载，不把整文件缓存在内存。
- **媒体会话**：资料库保存稳定文件 ID，不保存签名链接；播放或重连时换短时票据，避免直接把播客 Bearer 传给第三方地址。网关要限定并发、单流缓冲和总内存，压测弱网、跳转、锁屏和多账号。
- **实地联调**：按 Android API 24／当前 API、极空间／另一 NAS、至少一台 SFTP 主机和每家官方存储的测试账号逐项记录。飞连需一组授权租户场景，分别检查系统网络可用性和 HTTP API 可达性。这些均未在本次调研执行。
