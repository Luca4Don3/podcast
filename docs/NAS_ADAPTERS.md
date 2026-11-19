# NAS 接入与验收

NAS 型号和文件协议是两个不同的维度：先确认设备提供哪个文件服务，再选择 App 对应的来源。启用 SMB 不要求同时启用 WebDAV；选 SMB 时不经过 HTTP 网关或播客服务端。

## 设备与协议速查

| 设备或系统 | 厂商确认的接入线索 | 注意事项 |
| --- | --- | --- |
| 极空间 | [极空间 Z4 产品文档](https://zspace.cn/z4/)公开列有 SMB/CIFS；[T2S 兼容列表](https://www.zspace.cn/t2s/compatibility.html)直接列出 SMB 2/3；已开启 WebDAV 的机型可继续使用既有 WebDAV 来源。 | 按实机固件检查文件服务开关、共享名、用户权限和协商的 SMB 版本；不把厂商远程访问账号当作 SMB 账号。[极空间官方 Skill/CLI](https://download.zspace.cn/#skill) 是另一条接入路径，不等于面向原生移动端的稳定文件 API。 |
| TrueNAS Community Edition（FreeNAS 系列） | [TrueNAS SMB 共享指南](https://www.truenas.com/docs/scale/shares/smb/addmanagesmbshares/)、[开源中间件](https://github.com/truenas/middleware)。 | 使用独立 SMB 用户并给数据集 ACL 授权。[TrueNAS API](https://api.truenas.com/)也提供单独的文件操作，但不用于 App 的 SMB 播放链路；当前 WebDAV 另由 [TrueNAS 应用目录](https://apps.truenas.com/catalog/webdav)提供。 |
| OpenMediaVault（OMV） | [OMV Samba 服务文档](https://docs.openmediavault.org/en/latest/administration/services/samba.html)、[开源仓库](https://github.com/openmediavault/openmediavault)。 | 启用 SMB/CIFS，再发布一个只读共享；OMV 的共享文件夹存在不等于已经通过 SMB 发布。 |
| 群晖 Synology DSM | 标准 SMB；群晖另外公开 [File Station API 指南](https://www.synology.com/en-global/support/developer)。 | File Station 是另一个可独立做的厂商文件接口，不把 SMB 浏览包装成 File Station API。 |
| 威联通 QNAP QTS | [QTS Samba 设置](https://docs.qnap.com/operating-system/qts/5.2.x/en-us/configuring-samba-microsoft-networking-settings-7447174D.html)。 | 不把 QTS 管理接口或浏览器 File Station 内部请求当成原生客户端公开 API。 |
| 飞牛 fnOS | [飞牛官方开发文档](https://developer.fnnas.com/api/overview)提供面向 NAS 本机应用的开放接口。 | 不等于外部移动端可直接使用的远程文件 API；[用户协议](https://fnnas.com/terms)区分产品与第三方开源组件，不把整个 fnOS 写成开源。 |

以上是厂商提供服务的证据，不是每个型号或固件的实测结论。NFS、SFTP、厂商文件 API 也是独立协议，不能从 SMB 或 WebDAV 推断已适配。

## 只读 SMB 的验收要求

- 在 NAS 开启 SMB 2/3，保留 SMB1 禁用。通过可信局域网或已授权的私网访问，不把 TCP 445 开放给互联网。
- 使用只读音频账号或最小授权目录；优先填服务器地址、实际共享名、可选根目录、该来源的用户名和密码。不要使用 NAS 管理员账号。
- SMB 签名校验数据完整性，不是加密。若需保密，选客户端和服务端均支持且强制执行的 SMB3 加密策略，或使用已有的可信私网传输；不得静默降级。
- 验证空目录、中文与空格、音频文件枚举、大文件从任意偏移播放、切换和取消。错误认证、目录越界、不可达时应明确提示；鉴权材料不应出现在媒体 URI、通知、日志或普通 JSON。
- 优先在隔离的 Samba 测试环境使用合成数据验证；只有在实际 NAS 上再次完成协议协商、浏览以及播放，才能注明对应型号、固件和设备已实测。

### Android Samba 集成夹具

GitHub Actions 手工构建可以开启 `smb_fixture_test`，或在有 Docker daemon 的 Linux 测试机运行：

```sh
cd android
bash ../tests/run-smb-fixture.sh -- ./gradlew --no-daemon :app:testCompactUnitTest --tests "*SmbFixtureTest"
```

夹具仅监听主机的 `127.0.0.1` 随机端口，使用合成的用户名、凭据和内容，不访问用户真实 NAS，也不启动应用服务端。只读配置要求签名、禁用 SMB1；测试按偏移读取已知字节。这不是手机上的真实媒体解码或固件兼容性验收。
