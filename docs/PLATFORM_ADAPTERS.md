# 播客平台接入

本项目的目标是**接收公开播客内容**，不是自建播客目录或分发服务。客户端解析用户提供的订阅地址，并直接向内容发布方请求音频。

## 接入层级

| 层级 | 能力 | 说明 |
|---|---|---|
| 直接订阅 | RSS 2.0 / Atom feed | 必须来自节目发布方或用户提供；客户端解析标题、简介、单集 enclosure。 |
| 订阅迁移 | OPML | 读取 outline 的 xmlUrl/title，去重后导入。 |
| Apple Podcasts 分享链接 | podcasts.apple.com/.../id数字 | 通过 Apple 官方 iTunes Lookup API 取得公开 feedUrl。 |
| 其他平台分享链接 | Spotify / 小宇宙 / 喜马拉雅 | 识别平台与节目 ID；不把网页当成 RSS，也不抓取受限内容。只有节目自身提供公开 feed 时才可订阅。 |
| 网络存储 | WebDAV / 坚果云 / 极空间（用户自启 WebDAV） | 只读取用户有权访问的音频文件；凭据和传输材料存本机加密区，不进入 URL 或订阅 JSON。 |

官方 API 通常需要开发者凭据、用户授权和平台审核；未取得这些条件前不会声称已接入。公开网页链接的解析只用于识别平台，不代表获得内容授权。

## 网络存储与平台边界

- **WebDAV**：RFC 4918；用 PROPFIND Depth:1 有界列目录，GET/Range 需逐服务实测，206/416 才标为可拖动。
- **坚果云**：官方 WebDAV https://dav.jianguoyun.com/dav/，使用邮箱 + 应用密码，不是网页登录密码。
- **极空间**：没有公开私有 API；需要用户在 NAS 系统设置中开启 WebDAV，并由用户填写实际可达地址。App 不发现设备、不建立 VPN、不使用私有接口。
- **飞连**：火山引擎企业办公安全平台，SSO/OpenAPI 由企业租户管理员配置，没有给任意第三方 App 的公开登录 SDK。系统 VPN 可达不等于 App 内已完成授权；只有管理员配置 SSO 应用后才能接，当前不显示为已支持。
- **百度网盘 / 阿里云盘个人版**：官方开放 API 需注册应用与审核；新百度应用仅可读取 /apps/应用名 目录，不能声称全盘支持。
- **OneDrive / Google Drive**：可走官方 OAuth。OneDrive 用 Microsoft Graph 的 Files.Read；Google 推荐 drive.file + Picker。缺少注册 application/client ID 时不能实现登录。
- **系统文件选择器**：Android SAF 与 iOS UIDocumentPicker 可在无厂商注册时由用户选择云盘/本机文件，作为补充路径，不替代 WebDAV。

当前客户端实现的是 WebDAV 类来源（含坚果云预设和极空间用户自填地址）。需要厂商注册/审核的网盘与企业飞连不会用私有接口或 Cookie 冒充已支持。

## 传输、认证与键盘

默认同时支持 http 与 https，建议优先使用 https；只有用户为某个来源勾选“仅允许 HTTPS”时才拒绝 http 地址与降级跳转。Android 的 Cleartext 策略与 Apple 的 ATS 已允许 HTTP；自定义 CA、mTLS、Digest 与 Basic/Bearer/API Key 均为可选，凭据只随对应来源请求发送。

每来源的认证材料可来自 URL userinfo/token/api_key，也可单独填写。凭据、证书私钥与传输材料按来源加密保存，跨主机/端口变化/HTTPS 降级时会剥离。

两端使用系统输入法和标准文本控件，通过系统 IME 动作、焦点导航与窗口 insets 适配键盘，不实现自定义键盘。

## 合规边界

- 不托管、转播、缓存或再分发音频。
- 不破解付费内容、地区限制、登录墙或 DRM。
- 不使用抓包得到的私有接口、SSO Cookie 或提取码绕过。
- 用户导入的链接仅用于本机订阅和播放。

## 当前限制

- WebDAV Range、坚果云应用密码、极空间固件差异和真实 NAS 网络路径尚未做真机/服务端联调。
- 官方 OAuth 网盘需要应用注册信息与合法回调；飞连需要企业租户配置。未取得前只提供说明，不提供伪登录。
- 任何“可访问”都不代表版权授权，公开运营仍需自行核实。
