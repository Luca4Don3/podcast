# 播客平台接入

本项目的目标是**接收公开播客内容**，不是自建播客目录或分发服务。客户端解析用户提供的订阅地址，并直接向内容发布方请求音频。

## 接入层级

| 层级 | 能力 | 说明 |
|---|---|---|
| 直接订阅 | RSS 2.0 / Atom feed | 必须来自节目发布方或用户提供；客户端解析标题、简介、单集 enclosure。 |
| 订阅迁移 | OPML | 读取 outline 的 xmlUrl/title，去重后导入。 |
| Apple Podcasts 分享链接 | `podcasts.apple.com/.../id<数字>` | 通过 Apple 官方 iTunes Lookup API 取得公开 feedUrl。 |
| 其他平台分享链接 | Spotify / 小宇宙 / 喜马拉雅 | 识别平台与节目 ID；不把网页当成 RSS，也不抓取受限内容。只有节目自身提供公开 feed 时才可订阅。 |

官方 API 通常需要开发者凭据、用户授权和平台审核；未取得这些条件前不会声称已接入。公开网页链接的解析只用于识别平台，不代表获得内容授权。

## 传输加密与摘要认证

默认同时支持 http 与 https，建议优先使用 https；只有用户为某个来源勾选“仅允许 HTTPS”时才拒绝 http 地址与降级跳转。Android 的 Cleartext 策略与 Apple 的 ATS 已允许 HTTP，因此 HTTP 源在两端都可使用；对安全性要求高的来源请自行开启“仅允许 HTTPS”。其它可选项包括：自定义 CA 证书（与系统证书合并校验，不关闭证书验证）、mTLS 客户端证书与私钥、HTTP Digest 挑战认证。Digest 只在服务端返回 401 挑战后计算 Authorization，不预发明文密码。证书私钥与摘要凭据同样只进本机的加密存储；跨主机、端口变化或 HTTPS 降级时剥离认证与客户端证书。

Apple 的 iOS mTLS 需要在“客户端证书”字段填入证书与密钥打包后的 PKCS#12 Base64，并填写其密码；macOS 支持证书 PEM + 私钥 PEM。真机路径仍需在 iOS SDK/Xcode 环境验证。OAuth、设备码和音频内容本身加密尚未实现，不声称支持。

## 来源认证

部分播客源要求账号密码或密钥。客户端支持每个来源单独配置：链接中的 `user:pass@` 会转换为 HTTP Basic，`?token=`/`?access_token=` 转换为 Bearer，`?api_key=`/`?key=` 转换为 API Key 请求头；也可以在导入页单独填写这三类凭据。凭据会从保存的 feed URL 中移除，按来源加密保存（Android EncryptedSharedPreferences，Apple Keychain），只随该来源请求发送，不经过任何中间服务。跨主机或 HTTPS 降级跳转会剥离认证头。

## 接收分享链接

Android 支持系统分享菜单中的 `text/plain` 链接，以及来自 Apple Podcasts、Spotify、小宇宙、喜马拉雅公开节目页的 HTTP(S) 链接。应用会提取链接、自动打开导入页并开始解析；解析结果仍受上述公开 feed 规则限制。

## 合规边界

- 不托管、转播、缓存或再分发音频。
- 不破解付费内容、地区限制、登录墙或 DRM。
- 用户导入的链接仅用于本机订阅和播放。

## 音频输出

外接耳机、蓝牙耳机和车机的音频路由由系统完成，不按设备型号适配。Android 使用 Media3 的媒体音频属性、音频焦点和“音频设备断开即暂停”；Apple 使用 `AVAudioSession` 的 `.playback`/`.spokenAudio`，监听输出路由变化和来电等中断，并接入系统播放/暂停遥控命令。应用不需要 Bluetooth 连接权限即可把音频输出到已配对设备。系统事件处理不等于真机兼容性测试。

## 输入与键盘

两端均使用系统输入法和标准文本控件，不实现自定义键盘。Android 通过窗口 insets 与 IME 的 Next/Done 动作保持输入框可滚动、焦点可递进、提交按钮可达；Apple 使用 SwiftUI 标准键盘类型、`submitLabel`、`onSubmit` 与 `@FocusState`，并允许滚动手势收起键盘。URL、用户名和请求头名称关闭自动大写与纠正；密码、令牌和私钥使用系统安全输入。

## 当前限制

- Spotify、小宇宙、喜马拉雅尚未提供无需凭据且稳定的公开 feed 解析；分享链接导入会提示用户补充 feed。
- 平台页面结构变化不应导致 App 崩溃；解析失败只返回安全错误。
- 任何“可访问”都不代表版权授权，公开运营仍需自行核实。
