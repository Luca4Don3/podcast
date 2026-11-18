# C++ 后端（podcast-server-cpp）

与 `server/`（Go 版）**API 完全一致**的 C++20 实现：同一套 REST 接口、同一份 SQLite 表结构，
Android 客户端无需改动即可切换。

## 技术选型

| 关注点 | 选择 | 理由 |
| --- | --- | --- |
| 语言 | **C++20** | 零 GC、可直接控制内存与线程；`std::string_view`/`std::map` 足够 |
| 构建 | CMake ≥ 3.16 | 跨发行版通用，无额外包管理器 |
| HTTP | 自研（socket + 线程池） | 不引入 Boost/asio 之类重依赖，二进制 640KB |
| TLS/RSS 抓取 | **OpenSSL** 手写 HTTP/1.1 客户端 | 省掉 libcurl 依赖；支持条件请求与重定向 |
| 数据库 | **SQLite3**（系统库，C API） | 与 Go 版共享同一份 db 文件格式 |
| 密码 | **scrypt**（OpenSSL `EVP_PBE_scrypt`） | C 生态里最稳妥的口令哈希；N=2^15,r=8,p=1 |
| 令牌 | JWT HS256 + 不透明刷新令牌 | 与 Go 版一致，客户端可复用 |
| JSON | 自研解析/序列化 | 无第三方依赖，约 300 行 |

## 构建

```bash
# 依赖（Debian/Ubuntu）
sudo apt install -y build-essential cmake libssl-dev libsqlite3-dev

# 依赖（RHEL/Alma/Rocky）
sudo dnf install -y gcc-c++ cmake openssl-devel sqlite-devel

cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j"$(nproc)"
./build/podcast-tests          # 单元测试
./build/podcast-server -env-file .env
```

产物：`build/podcast-server`（约 640 KB）、`build/podcast-tests`。

## 与 Go 版的差异

| 项目 | Go 版（server/） | C++ 版（server-cpp/） |
| --- | --- | --- |
| 密码哈希 | Argon2id | scrypt |
| RSS 解析 | encoding/xml | 自研 XML 解析器 + iconv 转码 |
| HTTP 服务器 | net/http | 自研 socket + 线程池（8 线程） |
| 二进制大小 | ~12 MB | **~640 KB** |
| 启动内存 | ~15 MB | **~5 MB** |
| 字符集支持 | GBK/GB18030/Big5（x/text） | 同左（iconv） |
| API | 完全一致 | 完全一致 |

> 两个后端**共用一个 SQLite 库结构**，但密码哈希算法不同，不能直接互换用户表。
> 哈希不能直接转换算法；重新登录也不能自动解决不兼容。迁移需实现双算法校验后升级，或通过安全的密码重置流程，不应直接替换哈希字段。

## 默认内容边界

本项目提供用户自行导入 RSS / 电台音源的空间，不默认聚合全球内容。
`ITUNES_SEARCH_URL`、`RADIO_BROWSER_URL` 默认留空：未设置、空串或空白都禁用外部目录，不自动回退。
旧部署若已设目录 URL，应由管理员显式清空；不会改动既有个人音源。
App 仅提供云听、央广网的官方网页入口，不把官方可收听误认为已取得第三方接入许可。
参见 [电台使用说明](<../docs/RADIO.md>) 与 [内容来源与授权边界](<../docs/CONTENT_SOURCES.md>)。

## 已实现接口

```
GET  /healthz                          健康检查（含数据库状态）
GET  /api/v1/version                   版本与能力声明
POST /api/v1/auth/register             注册（首个用户为 admin）
POST /api/v1/auth/login                登录
POST /api/v1/auth/refresh              刷新令牌（旋转 + 旧令牌失效）
POST /api/v1/auth/logout               退出（可 allDevices）
GET  /api/v1/me                        当前用户
PATCH /api/v1/me                       改昵称
POST /api/v1/me/password               改密码（踢掉其它设备）
GET  /api/v1/podcasts                  播客列表 / ?scope=subscribed
POST /api/v1/podcasts                  添加 RSS（抓取入库 + 自动订阅）
GET  /api/v1/podcasts/{id}             节目详情
POST /api/v1/podcasts/{id}/refresh     刷新单集
GET  /api/v1/podcasts/{id}/episodes    单集分页
GET  /api/v1/episodes/{id}             单集详情
GET  /api/v1/discover/search?q=        兼容播客目录（默认禁用）
GET  /api/v1/discover/stations         兼容电台目录（默认禁用）
GET/POST /api/v1/stations             用户音源列表 / 手动导入
POST /api/v1/stations/import          用户批量导入（最多 100 条）
DELETE /api/v1/stations/{id}          删除自己的音源
PUT/DELETE /api/v1/stations/{id}/favorite  收藏 / 取消收藏
GET  /api/v1/stations/probe?url=      服务器侧连通性测试（不审核授权）
GET  /api/v1/subscriptions             订阅列表
PUT/DELETE /api/v1/subscriptions/{id}  订阅 / 取消
GET  /api/v1/progress                  最近播放
GET/PUT /api/v1/progress/{episodeId}   单集进度
GET  /api/v1/actions?type=starred      收藏 / 待播列表
PUT  /api/v1/actions/{episodeId}       标记收藏 / 待播
GET/POST /api/v1/playlists             播放列表
GET/DELETE /api/v1/playlists/{id}      播放列表详情 / 删除
PUT/DELETE /api/v1/playlists/{id}/items/{episodeId}  增删单集
GET/PUT /api/v1/settings               播放偏好
POST /api/v1/sync                      增量推送 + 快照拉取
```

## 部署

```bash
# 单机直接跑
APP_ENV=production JWT_SECRET="$(openssl rand -base64 48)" \
APP_DATA_DIR=/var/lib/podcast ./build/podcast-server

# 或 Docker
docker build -t podcast-cpp .
docker run -d -p 127.0.0.1:8080:8080 -v podcast-data:/data \
  -e JWT_SECRET="$(openssl rand -base64 48)" podcast-cpp
```

反向代理可直接复用 [deploy/Caddyfile](../deploy/Caddyfile)（改 `docker-compose.yml` 的 build context 为 `../server-cpp`）。

## 已验证

- `podcast-tests`：JSON 往返/异常输入、Base64URL、SHA-256、URL 编解码、XML 解析（含实体与带前缀标签）、
  UTF-8 校验、时间归一化、字符串工具 —— 全部通过。
- 端到端：健康检查 → 注册 → 添加本地 RSS（2 集，`25:30` 时长解析为 1530s）→ 单集列表 → 订阅列表 →
  进度写入/读取 → sync 快照，全部返回正确；服务器日志 0 ERROR。
- 边界：刷新令牌旋转（旧令牌复用 401）、未鉴权 401、订阅不存在播客 404、非法 JSON 400、分页越界 200。
- 并发：10 并发 × 10 轮 + 20 并发鉴权请求，无崩溃、无 ERROR、进程存活。

## 未验证

- 未在 ARM64 / musl 之外的平台做交叉编译验证（Dockerfile 用 alpine 构建）。
- 未做长时间（>1h）内存与连接泄漏观测。
- TLS 仅对 RSS 抓取生效；服务自身 TLS 交给 Caddy/nginx 反代。
