# 网络电台（自定义音源）

除了 RSS 导入，本项目提供用户自行导入、管理和播放有权使用的网络音源的空间。
不默认聚合全球电台，不预置未经确认授权的直播流；少量大陆内容通过官方网页入口提供。
授权边界与来源依据见 [内容来源说明](<CONTENT_SOURCES.md>)。

## 支持的音源类型

| 类型 | 示例 | 说明 |
| --- | --- | --- |
| MP3 直播流 | `https://example.com/live.mp3` | 最常见，Icecast/Shoutcast |
| AAC / HE-AAC | `https://example.com/live.aac` | 移动网络更省流量 |
| Ogg / Opus | `https://example.com/stream.opus` | 开源编码，低码率音质好 |
| FLAC | `https://example.com/lossless.flac` | 无损，流量大 |
| **HLS (m3u8)** | `https://example.com/live/index.m3u8` | 自动识别，Media3 原生支持 |
| 直链音乐文件 | 任意 `.mp3/.m4a` | 当作"单集电台"播放 |

服务端通过 URL 特征器自动标记 HLS（`.m3u8` 或 `/hls/`），
客户端据此决定是否需要 ExoPlayer 的 HLS 模块。

表中 example.com 地址均为格式示例，不是可用或获授权的内置音源；具体格式能否播放取决于流、设备和访问条件。

## 导入与官方入口

### 1. 自行导入音源

App → 电台 → 我的音源，填写名称和 HTTP(S) 流地址。只导入自己有权使用的内容。

连接测试返回服务器侧可达性、Content-Type、是否 HLS 和延迟。
此结果不检查版权，不保证手机端可以收听，也不绕过地区或鉴权限制。不同后端的探测读取策略不同，不应把它当作文件下载工具。

```
GET /api/v1/stations/probe?url=<encoded stream url>
→ {"reachable":true,"statusCode":200,"contentType":"audio/mpeg","isHls":false,"latencyMs":1923}
```

### 2. 大陆官方入口

提供 [云听](<https://www.radio.cn/pc-portal/home/>)、[央广网](<https://www.cnr.cn/>) 两个官方网页入口，点击在系统浏览器打开。
入口不是直播流，不会自动加入个人电台，也不代表本 App 已取得内置播放或转播授权。

默认关闭 Radio-Browser 与 Apple 等外部目录搜索：

```dotenv
RADIO_BROWSER_URL=
ITUNES_SEARCH_URL=
```

未设置、空字符串或空白值均为关闭。兼容 API 保留，禁用时返回空列表与 `enabled:false`，不访问外部目录。
如旧部署配置过 URL，需显式清空；仅修改示例文件不会覆盖旧环境变量。
管理员自行配置兼容目录前仍须核实内容授权与地区适用性，不以国家筛选代替版权审核。

### 3. 批量粘贴导入

每行一个 URL，或 `名称|URL`，单次最多 100 条。此功能导入用户提供的列表，不再提供全球目录“全部导入”。

后端接口：

```
POST /api/v1/stations/import
{"stations":[{"name":"A","streamUrl":"https://a.example.com/1.mp3"}, ...]}
→ {"imported":2,"items":[...]}
```

单次最多 100 条。客户端在提交前逐行校验，无效行和同一批次的重复 URL 会提示行号并阻止提交；失败时保留输入。
服务端按 `UNIQUE(user_id, stream_url)` 更新已有记录，避免反复导入产生重复条目。

## 数据模型

```sql
stations
├── id, user_id      -- 自行导入（user_id 非空），属于当前用户
├── name, stream_url -- UNIQUE(user_id, stream_url) 去重
├── homepage, favicon, genre, country, language
├── codec, bitrate, is_hls
├── source           -- manual；保留历史 directory 数据兼容
└── external_id      -- 目录中的 UUID，便于回查

station_favorites    -- 用户收藏（与"我的电台"合并展示）
└── UNIQUE(user_id, station_id)
```

## 播放行为

- 电台与播客**共用同一个 ExoPlayer 实例**，切换时自动停掉上一路。
- 电台走 `MediaItem.LiveConfiguration`（targetOffsetMs = 5s），
  由 ExoPlayer 处理直播缓冲与重连。
- 直播**没有总时长**，UI 不显示进度条，也不会上报播放进度（避免污染"继续收听"）。
- 媒体通知、锁屏信息取自 `MediaMetadata`（电台名 + 类型 + 台标）。

## 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/v1/stations | 我的电台（自建 + 收藏） |
| POST | /api/v1/stations | 添加自定义流 |
| DELETE | /api/v1/stations/{id} | 删除自建电台 |
| PUT/DELETE | /api/v1/stations/{id}/favorite | 收藏 / 取消收藏 |
| GET | /api/v1/stations/probe?url= | 探测流可用性 |
| GET | /api/v1/discover/stations | 兼容目录接口，默认禁用 |
| POST | /api/v1/stations/import | 批量导入 |

## 兼容性注意

- **HTTP 明文流**：很多电台仍是 `http://`，Android 9+ 默认禁止明文流量，
  本项目在 [网络安全配置](<../android/app/src/main/res/xml/network_security_config.xml>) 中已放开（生产可收紧为按域名白名单）。
- **HLS 的 .ts 分片**：Media3 的 `media3-exoplayer-hls` 已包含在依赖中，无需额外配置。
- **封面加载**：台标多为外部 URL，用 Coil 异步加载，失败时显示默认图标。
- **版权**：不托管或转播音频；这并不豁免内容使用许可与公开运营的合规义务。

## 验证范围

回归使用本机测试源和虚构域名，不用第三方目录搜索结果充当内容许可证明。
重点覆盖目录默认禁用、显式配置兼容服务、个人音源导入/列表/收藏/删除、用户隔离及输入错误。
Android 测试覆盖导入列表解析和官方外链元数据；编译通过不等于真机播放验证。

## 未验证

- 未在真机上播放过长时段直播（弱网重连、切后台续播）。
- 未测试需要鉴权/Geo 限制的流（如部分地区性电台）。
