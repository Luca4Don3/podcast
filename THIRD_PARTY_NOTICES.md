# 第三方组件

本文件只记录随源码交付的第三方组件。运行时不连接外部播客服务端。

## SMB 2/3

- **Android**：`com.hierynomus:smbj:0.15.0`，Apache License 2.0。仓库缓存有对应 POM；发布构建由 Gradle 解析依赖与传递依赖。
- **Apple**：`sahlberg/libsmb2`，LGPL-2.1-or-later。本项目以本地 SwiftPM 包 `apple/Vendor/LibSMB2` 的动态库方式使用，完整许可证和上游通知保存在 `apple/Vendor/LibSMB2/Upstream`。动态链接是 LGPL 兼容方案的一部分，但上架前仍需单独核验 App Store 签名、用户替换库、许可证全文与源码提供义务；当前只提供源码和模拟器构建，不作为已满足分发义务的证明。

SMB 实现只使用只读文件操作，禁用 SMB1，要求认证并强制签名；`encrypted` 策略要求 SMB3 方言和 SMB3 加密，`tlsOnly` 会拒绝直连 TCP，因为 SMB 签名/SMB3 加密不是 TLS。

上游许可证位置：
- `apple/Vendor/LibSMB2/Upstream/LICENCE-LGPL-2.1.txt`
- `apple/Vendor/LibSMB2/Upstream/COPYING`
- `apple/Vendor/LibSMB2/Upstream/NOTICE-libdcerpc.txt`
