# 从链接导入服务器

登录前或进入「设置」，打开「服务器地址（可选）」→「从链接导入」。手动粘贴后点「识别链接」，核对预览的服务器地址和登录邮箱，再确认。识别阶段不联网。

| 链接类型 | 示例（全部为虚构信息） | 确认行为 |
| --- | --- | --- |
| 普通地址 | `https://podcast.example/api/v1/` | 只保存地址，不自动登录。 |
| 带凭据的粘贴链接 | `https://podcast.example/api/v1/#username=test%40example.com&password=demo%2B1234` | 只向预览中的 HTTPS API 发送登录请求，登录成功后保存地址和会话。 |
| URL userinfo | `https://test%40example.com:demo%2B1234@podcast.example/api/v1/` | 与片段格式相同；用于导入已有的这类链接。 |

仅支持本项目播客后端的邮箱和密码登录。没有路径时使用 `/api/v1/`；反向代理使用最终的 API 路径，不跟随登录重定向。不接受查询参数、带凭据的 HTTP 链接，或同时使用两种凭据写法。凭据从 UTF-8 百分号编码解码一次；例如 `%40` 为 `@`、`%26` 为 `&`，`+` 按字面处理。为避免歧义，带空白、控制字符或反斜杠的凭据请改用手动登录。

密码在预览中不显示，不写应用配置；保存的是服务器地址和认证令牌。验证失败不替换已保存的地址或登录。带密码的链接本身就是敏感信息，即使片段不会随 HTTP 请求发送，也不要把真实链接发到聊天、网页、工单或日志中。推荐直接在 App 手动登录；此入口不解析 RSS 或电台源的认证信息。

## 模块

- [ServerSetupLink](<../android/app/src/main/java/com/podcastapp/data/serversetup/ServerSetupLink.kt>)：本地解析及规范化，输出干净的地址和内存凭据。
- [ServerSetupLogin](<../android/app/src/main/java/com/podcastapp/data/serversetup/ServerSetupLogin.kt>)：独立 HTTPS 传输，不复用当前账号的令牌或调试日志。
- [ServerSetup](<../android/app/src/main/java/com/podcastapp/data/serversetup/ServerSetup.kt>)：统一执行保存地址或验证并切换会话。复用现有 Android 会话存储及服务端登录接口。
