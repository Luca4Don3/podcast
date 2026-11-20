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
- **飞连**：独立的「飞连企业网络（WebDAV）」来源，使用飞连客户端或企业 MDM 已下发的系统路由。从输入的企业 WebDAV 地址执行只读试连（PROPFIND Depth:0），不自动跳转；分别提示登录页或非 WebDAV、需要来源自己的凭据、权限拒绝、响应异常等。试连不保存配置。它检测的是目标服务，不是 VPN 是否启用，也不收集飞连密码、读浏览器 Cookie、建立 VPN 或绕开企业策略。仅浏览器可用的[应用网关](https://docs.volcengine.com/docs/feilian/application-gateway-overview)不等于存在原生 WebDAV 地址。
- **OneDrive**：独立来源，使用 Microsoft [设备码 OAuth](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code)和 [Graph 文件接口](https://learn.microsoft.com/en-us/graph/api/driveitem-get-content?view=graph-rest-1.0)。授权时去微软页面输入设备码；App 不接收微软账号密码。支持只读目录浏览和选择音频。令牌留在本次应用会话，不保存下载地址，也不向媒体 CDN 发送 Graph 令牌。
- **阿里云盘个人版**：已核实[Android 官方 SDK](https://github.com/alibaba/aliyunpan-android-sdk)与 [iOS 官方 SDK](https://alibaba.github.io/aliyunpan-ios-sdk/)都提供公开客户端 PKCE 路径。它与阿里云 PDS 是不同产品；本次没有接入 SDK，不把官方可适配写成已实现。
- **百度网盘**：[设备码授权](https://pan.baidu.com/union/doc/使用入门/接入授权/设备码模式授权/)获取设备码与兑换令牌的条件不同；兑换和刷新需要 SecretKey，不能打包进客户端。未接入。
- **Google Drive**：优先评估 [drive.file 与 Picker](https://developers.google.com/workspace/drive/api/guides/api-specific-auth) 的按文件授权，不申请全盘权限；本次未实现。
- **系统文件选择器**：Android SAF 与 Apple Files / 文档选择器是独立的系统提供者路径；本次不将它算作厂商登录适配。

### 配置与验收

- **OneDrive**：使用在 Microsoft Entra 中注册的公开客户端应用 ID，启用公开客户端流，并配置目标帐户类型及委托权限 Files.Read；请求 offline_access 以在当前会话刷新授权。不需要在客户端配置 secret 或回调 URL。组织可通过[条件访问策略](https://learn.microsoft.com/en-us/entra/identity/conditional-access/policy-block-authentication-flows)禁用设备码流；不绕开这一限制。
- **飞连**：先由企业侧在飞连建立授权网络，确保本应用和目标域名/IP、端口在分流允许范围内。再将管理员提供的原生 WebDAV 地址填入来源，点击「测试企业 WebDAV（不保存）」。需要鉴权时，填 WebDAV 来源自己的凭据，不填飞连登录凭据。

## 传输、认证与键盘

默认同时支持 http 与 https，建议优先使用 https；只有用户为某个来源勾选“仅允许 HTTPS”时才拒绝 http 地址与降级跳转。Android 的 Cleartext 策略与 Apple 的 ATS 已允许 HTTP；自定义 CA、mTLS、Digest 与 Basic/Bearer/API Key 均为可选，凭据只随对应来源请求发送。

每来源的认证材料可来自 URL userinfo/token/api_key，也可单独填写。凭据、证书私钥与传输材料按来源加密保存，跨主机/端口变化/HTTPS 降级时会剥离。

两端使用系统输入法和标准文本控件，通过系统 IME 动作、焦点导航与窗口 insets 适配键盘，不实现自定义键盘。

## 合规边界

- 不托管、转播或再分发音频；用户可对订阅音频启用 [仅本机离线缓存](<OFFLINE_CACHE.md>)。
- 不破解付费内容、地区限制、登录墙或 DRM。
- 不使用抓包得到的私有接口、SSO Cookie 或提取码绕过。
- 用户导入的链接仅用于本机订阅和播放。

## 当前限制

- WebDAV Range、坚果云应用密码、极空间固件差异和真实 NAS 网络路径尚未做真机/服务端联调。
- 实现与真实环境验收分开：未使用真实 OneDrive 应用/账户或飞连租户联调；不承诺所有企业租户、分流和证书组合。
- 任何“可访问”都不代表版权授权，公开运营仍需自行核实。
