# 播客 App（Android + 自建后端）

参考 Apple Podcasts 的信息架构与交互，面向 Android 的音频导入与播放工具，默认配套可自托管的 C++ 后端（保留 Go 实现）。
账号体系自建（邮箱 + 密码注册/登录），数据（订阅、进度、收藏、待播、播放列表、偏好）全部存在你的云服务器上；
默认仅提供用户自行导入 RSS / 电台音源的空间，以及少量中国大陆官方内容入口；不默认接入全球目录、不预置未经授权的直播流。

## 功能

**Android 客户端**
- 资料库：继续收听（跨设备续播）、订阅列表、收藏、待播清单
- RSS 导入：粘贴自己有权使用的订阅地址
- 电台：自行导入音源、收藏、播放；大陆官方入口在系统浏览器打开
- 节目页：封面/简介/订阅开关、单集列表、手动刷新单集
- 播放器：Media3 + ExoPlayer 后台播放、媒体通知、锁屏与耳机控制
- 播放：0.8x–2.0x 变速、快进/快退、拖动进度、每 5 秒回传进度
- 设置：服务器地址切换（无需重编译）、昵称、改密码、跳转秒数与倍速偏好、退出登录
- 令牌：Access(15m) + Refresh(30d) 自动续期与旋转，401 静默重放

**后端（Go，单二进制）**
- 认证：注册 / 登录 / 刷新（旋转）/ 退出，Argon2id 密码哈希（兼容 bcrypt 自动升级）
- 内容：RSS 抓取入库（ETag/Last-Modified 条件请求），单集解析（时长/发布时间/封面）
- 资料库：订阅、播放进度、收藏/待播/下载标记、播放列表、用户偏好
- 同步：POST /api/v1/sync 一次请求完成「上传本地变更 + 拉取云端快照」
- 存储：SQLite（WAL），默认 /data/podcast.db，无外部依赖，单机即可跑
- 部署：Dockerfile + docker compose（api + Caddy 自动 HTTPS）

> 后端不托管或转播音频，客户端直接播放用户导入的地址。直连、免费可听或来源在中国大陆，均不代表自动取得版权许可；公开运营仍需核实授权与适用资质。

## 目录结构

```
podcastapp/
├── server-cpp/             # C++20 后端（默认）
├── server/                 # Go 后端（备选）
│   ├── cmd/server/         # main
│   ├── internal/
│   │   ├── api/            # 路由 + handlers + iTunes 目录客户端
│   │   ├── auth/           # Argon2id 密码、JWT、刷新令牌
│   │   ├── config/         # 环境变量配置
│   │   ├── httpx/          # JSON/错误/中间件（鉴权、CORS、日志、panic 恢复）
│   │   ├── rss/            # RSS 2.0 + iTunes 扩展解析
│   │   └── store/          # SQLite 访问层 + 幂等迁移
│   └── Dockerfile
├── android/                # Kotlin + Jetpack Compose 客户端
│   └── app/src/main/java/com/podcastapp/
│       ├── data/           # Retrofit API、DTO、DataStore 会话、仓库
│       ├── playback/       # Media3 播放器 + MediaSessionService
│       └── ui/             # 资料库/RSS导入/电台/节目页/播放器/设置
├── deploy/                 # docker compose + Caddyfile + 环境变量示例
└── Makefile
```

## 网络电台（自定义音源）

定位是**用户自行导入与管理音源**，不是全球电台聚合平台：

- **自行导入**：保存自己有权使用的 HTTP(S) 电台流；支持单条地址和批量粘贴列表（单次最多 100 条）
- **格式**：MP3 / AAC / Ogg / Opus / FLAC / HLS，实际能否播放取决于流、设备解码器和访问条件
- **连接测试**：仅检测服务器侧可达性与响应类型，不是内容合法性、接入授权或手机可播放的证明
- **大陆官方入口**：[云听](<https://www.radio.cn/pc-portal/home/>)、[央广网](<https://www.cnr.cn/>)，点击在浏览器打开；不是 App 内置音源或授权转播
- **默认关闭外部目录**：`RADIO_BROWSER_URL` 与 `ITUNES_SEARCH_URL` 留空，不向全球目录发起请求；不删除已有个人音源
- **音频播放**：导入的电台与播客共用 ExoPlayer，直播模式不展示播客进度与倍速控制

详见 [docs/RADIO.md](docs/RADIO.md)。

## 后端选型：Go 版 与 C++ 版

两个后端**接口完全一致**，Android 客户端不需要改动，任选其一部署：

| | Go 版 `server/` | C++ 版 `server-cpp/`（默认） |
| --- | --- | --- |
| 语言 | Go 1.23 | **C++20** |
| 二进制 | ~12 MB | **~640 KB** |
| 常驻内存 | ~15 MB | **~7 MB** |
| 密码哈希 | Argon2id | scrypt（OpenSSL） |
| HTTP | net/http | 自研 socket + 线程池 |
| 构建依赖 | 仅 Go 工具链 | cmake + libssl-dev + libsqlite3-dev |
| 部署 | 静态二进制 | 动态链接，或 alpine 镜像 |

```bash
# C++ 版（默认）
make build-cpp && make test-cpp && make run-cpp

# Go 版
make build-go && make test-go && make run-go
```

> 两者共用同一份 SQLite 表结构，但密码哈希算法不同，用户表不能直接互换（详见 `server-cpp/README.md`）。

## 快速开始

### 1) 本地起后端

```bash
cd server
cp .env.example .env
go run ./cmd/server
curl -s localhost:8080/healthz
```

首个注册用户自动成为 admin：

```bash
curl -sX POST localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"supersecret123","displayName":"我"}'
```

### 2) 编译 Android App

```bash
cd android
./gradlew :app:assembleDebug
# 可选：给自有部署预设默认地址
# ./gradlew -Ppodcast.defaultApiBaseUrl=https://podcast.example.com/ :app:assembleDebug
```

在 App 底部「设置 → 服务器地址（可选）」可保存自定义地址、测试连接或清空恢复默认。登录前也有同名入口。只填域名或域名加端口时，默认用 HTTPS；只填站点根地址时会自动使用 `/api/v1/`。如反代使用特殊 API 路径，请填写完整 API 基址。

构建时可用上述 Gradle 属性，或在 Android 本地属性文件中设置 `podcast.defaultApiBaseUrl`。两者都未设置时不连接任何服务器，注册、登录和云端功能需配置地址后才能使用。实际更换服务器需要重新登录，不影响原服务器上的数据。

### 3) 部署到云服务器

```bash
cd deploy
cp server.env.example server.env      # 填 JWT_SECRET、域名等
APP_DOMAIN=podcast.yourdomain.com docker compose up -d --build
```

Caddy 自动申请 HTTPS 证书；后端只监听 127.0.0.1:8080，由 Caddy 反代 /api/* 与 /healthz。
数据持久化在 podcast-data 卷（容器内 /data/podcast.db），备份该卷即可。

## 后端配置（环境变量）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| APP_ADDR | :8080 | 监听地址 |
| APP_ENV | development | production 时强制要求 JWT_SECRET |
| APP_DATA_DIR | ./data | SQLite 数据目录 |
| DATABASE_DSN | 空 | 自定义 DSN，留空自动生成 |
| JWT_SECRET | 开发默认值 | 生产必须设置，建议 openssl rand -base64 48 |
| ACCESS_TOKEN_TTL | 15m | 访问令牌有效期 |
| REFRESH_TOKEN_TTL | 720h | 刷新令牌有效期 |
| SIGNUP_INVITE_CODE | 空 | 填了则注册必须带邀请码 |
| CORS_ALLOWED_ORIGINS | * | 逗号分隔来源 |
| RSS_USER_AGENT | PodcastApp/1.0 | 抓取 RSS 的 UA |
| ITUNES_SEARCH_URL | 空（禁用） | 仅管理员明确配置后启用兼容接口；自行核实内容授权 |
| RADIO_BROWSER_URL | 空（禁用） | 仅管理员明确配置后启用兼容接口；不自动回退全球目录 |

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/v1/auth/register | 注册（首个用户为 admin） |
| POST | /api/v1/auth/login | 登录 |
| POST | /api/v1/auth/refresh | 刷新令牌（旋转） |
| POST | /api/v1/auth/logout | 退出（可 allDevices） |
| GET, PATCH | /api/v1/me | 当前用户 / 改昵称 |
| POST | /api/v1/me/password | 修改密码（踢掉其它设备） |
| GET, PUT | /api/v1/settings | 播放偏好 |
| GET, POST | /api/v1/podcasts | 目录 / 添加 RSS |
| GET | /api/v1/podcasts/{id} | 节目详情 |
| POST | /api/v1/podcasts/{id}/refresh | 刷新单集 |
| GET | /api/v1/podcasts/{id}/episodes | 单集分页 |
| GET | /api/v1/episodes/{id} | 单集详情 |
| GET | /api/v1/discover/search?q= | 外部目录搜索 |
| GET | /api/v1/subscriptions | 订阅列表 |
| PUT, DELETE | /api/v1/subscriptions/{podcastId} | 订阅 / 取消 |
| GET, PUT | /api/v1/progress | 播放进度 |
| GET, PUT | /api/v1/actions | 收藏/待播/下载 |
| GET, POST | /api/v1/playlists | 播放列表 |
| POST | /api/v1/sync | 双向同步快照 |
| GET | /healthz | 健康检查（无需鉴权） |

## 测试

```bash
(cd server && go test ./...)
ctest --test-dir server-cpp/build --output-on-failure
# 使用已构建的二进制；仅访问本机测试源，不依赖公共目录
python3 tests/content_scope_smoke.py server-cpp/build/podcast-server server/bin/podcast-server
```

覆盖：健康检查、注册/登录/刷新旋转与重放拦截、订阅与单集解析（含 hh:mm:ss 时长）、
进度与收藏、播放列表、sync 快照、未鉴权访问。

## 后续可扩展

- 下载/离线：客户端接入 Media3 DownloadManager，服务端已有 downloaded 标记同步
- 通知：服务端已有 notify 字段与单集发布时间，可加定时抓取 + FCM
- 授权内容：取得内容方明确接入许可后，再添加可在 App 内播放的大陆音源；审核要点见 [内容来源说明](<docs/CONTENT_SOURCES.md>)

## Android APK 命名与版本

应用显示名为 **podcast**。分发文件名固定为 `podcast-<版本>.apk`：例如稳定版 `podcast-1.5.apk`、修订版 `podcast-1.5.1.apk`、候选版 `podcast-1.6-rc1.apk`。版本参考 Linux 的 `主版本.次版本[.修订号]` 和 `-rcN` 规则，不在文件名追加构建渠道或 ABI。Android 的 `versionCode` 独立递增，候选版和正式版每次发布都要增加；应用 ID 保持不变。

在 `android/` 下运行 `./gradlew :app:exportCompactApks` 导出精简测试包。通用包位于 `app/build/outputs/podcast/compact/<版本>/`，四种 ABI 的包在各自的子目录下，文件名相同。正式签名配置就绪后用 `:app:exportReleaseApks`；正式包和测试包分目录发布，不混用签名。

## 下载

- [v1.4.1 测试版](https://github.com/Luca4Don3/podcast/releases/tag/v1.4.1) · [podcast-1.4.1.apk](https://github.com/Luca4Don3/podcast/releases/download/v1.4.1/podcast-1.4.1.apk) · [SHA-256](https://github.com/Luca4Don3/podcast/releases/download/v1.4.1/SHA256SUMS)
- 测试包支持 **Android 7.0（API 24）及以上**，包含 ARM 和 x86 的 32／64 位变体。测试包使用调试签名，不作为正式商店发行件。
- C++ 后端默认从源码或 Docker 构建。只读 NAS 和 HTTPS WebDAV 的配置见[文件音源指南](<docs/FILE_MEDIA.md>)，服务器地址导入见[服务器配置](<docs/SERVER_SETUP.md>)。
- 新版标签同步构建 Android 手机／平板、iPhone／iPad 模拟器及 Linux 后端；真机 Apple 签名和桌面客户端不包含在内。详见[Apple 客户端指南](<docs/APPLE_CLIENT.md>)。

本仓库不提交真实服务器地址、凭据、用户数据库、签名密钥或构建缓存。请自行选择可信的 HTTPS 服务并替换示例密钥。
