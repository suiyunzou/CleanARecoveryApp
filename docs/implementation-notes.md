# yt-dlp 移植实施笔记

> 本文件按 [yt-dlp 移植实施计划](./yt-dlp移植实施计划.html) 推进，记录每个里程碑已完成的工作、关键决策与偏离。
> 边界情况遇到必须偏离计划时，**选保守方案**，在 `Deviations` 下记录后继续。

---

## 当前里程碑：M2 · 智能 + 合并

计划验收标准：
- Bilibili/YouTube 1080p 自动选 `bv+ba` 并合并为 mp4
- 后处理阶段能展示/推进：下载 → 合并/转封装 → 清理 → 完成
- 重复链接通过归档自动跳过
- 真机集中验证前，JVM/编译层至少覆盖选择策略和编排器接入

M2 工作分三条主线：
1. **智能格式选择**：完整 `SmartFormatSelector`（D5）
2. **后处理接入**：`SmartPostProcessor` + `FFmpegMergerPP` + `FFmpegVideoRemuxerPP`
3. **下载产物闭环**：双轨临时文件 → 合并产物 → 成功清理 → 归档

---

## Progress

### 架构地基

#### 1. 统一数据模型（对应计划 §2）
- [x] `ExtractorResult.Format` 扩展：新增 `protocol` / `formatId` / `fps` / `language` / `httpHeaders`
  - 复用方式：保留旧 10 参构造器（标记 `@Deprecated`），委托新全参构造器；新增 `Format.Builder` 供新代码使用
  - 旧调用点（6 个文件）零改动，确保不破坏现有 Bilibili / YouTube / TikTok / Douyin / Generic 提取器
- [x] `ExtractorResult` 顶层扩展：`duration` / `thumbnailUrl` / `description` / `uploaderId` / `webpageUrl` / `extractorKey`（归档键）
  - 同样保留旧构造器，新增带归档字段的构造器
- [x] `DownloadTask` 扩展：`requestedFormats` / `info` / `archiveKey`
  - 旧 `url` / `mimeType` 字段保留兼容（旧 Service 仍读这两个字段）

#### 2. 类型接口层（对应计划 §3）
- [x] `DownloadOptions`：Q1-Q8 默认值写入（maxConcurrentDownloads=2、conflictStrategy=ASK、maxRetries=3 等）
- [x] `RequestedFormats`：1 或 2 条 `Format` + `needsMerge` 标志
- [x] `QualityHint` 枚举：AUTO / P2160 / P1080 / P720 / AUDIO_ONLY
- [x] `FileConflictStrategy` 枚举：ASK / SKIP / RENAME
- [x] `FileDownloader` 接口 + `DownloaderRegistry`（PROTOCOL_MAP: http/https/m3u8/m3u8_native/dash）
- [x] `PostProcessor` 接口 + `PostProcessorChain` + `PostProcessWhen` 枚举（8 阶段）
- [x] `DownloadArchive`：`isDownloaded` / `markDownloaded`，持久化到 `archive.txt`（对齐 yt-dlp）

#### 3. 中心编排器（对应计划 §3.1）
- [x] `YoutubeDL.extractAndDownload(url, opts)` 六阶段：
  1. `ExtractorRegistry.match(url)`
  2. `extractor.extract(url)` → `ExtractorResult`（info_dict）
  3. `DownloadArchive.isDownloaded()` → 幂等跳过
  4. `SmartFormatSelector.select(result, hint)` → `RequestedFormats`（M2 已接入完整 D5 策略）
  5. `DownloaderRegistry.route(fmt).download(fmt, out, cb)` → `DownloadResult`
  6. `PostProcessorChain.runAll(result, POST_PROCESS)`（M2 已注册 Merger/Remuxer/Cleanup，JNI bridge 缺口见 D-M2-01）
  - ⑥.7 集成 P0 B2：下载完成调用 `MediaScannerNotifier.scanFile` 通知相册

#### 4. 嗅探路径适配（对应计划 §2.3 / D2）
- [x] `SniffedUrlAdapter`：把 `MediaResource`（WebView 嗅探瞬态对象）转译为 `ExtractorResult.Format`，补 `protocol` / `formatId`

### M2 · 智能 + 合并

#### 1. SmartFormatSelector（对应计划 §1.1 / D5）
- [x] 新增 `SmartFormatSelector`
  - `AUTO`：优先选择 ≥1080p 的音视频合一轨；否则自动选择最佳 `videoOnly + audioOnly`
  - `P2160/P1080/P720`：按用户档位过滤，避免显式档位被更高画质合一轨越级；无合适分轨时才回退到可用合一轨
  - `AUDIO_ONLY` / `DownloadOptions.audioOnly`：选择最佳纯音频，必要时回退合一轨
- [x] `YoutubeDL.stageSelectFormats()` 改为委托 `SmartFormatSelector`，移除 M1 内联简化策略
- [x] 新增 `SmartFormatSelectorTest` 覆盖 AUTO、双轨、显式档位、audio-only 四个规则

#### 2. 后处理链（对应计划 §1.3 / §5⑤）
- [x] 新增 `SmartPostProcessor`：固定注册 M2 默认 PP，运行时按 `DownloadResult` 自动 no-op
- [x] 新增 `FFmpegMergerPP`
  - `RequestedFormats.needsMerge=true` 时执行
  - 输入：`*.video.<ext>` + `*.audio.<ext>`
  - 输出：最终 `{base}.mp4`
  - 成功后更新 `DownloadResult.outputFile`
- [x] 新增 `FFmpegVideoRemuxerPP`
  - 单轨 `.ts/.m2ts` 输出自动转封装到 `.mp4`
  - 成功后更新 `DownloadResult.outputFile`
  - 转封装目标若已有同名 `.mp4`，自动避让为 `_1/_2`，避免绕过下载前冲突策略覆盖用户文件
- [x] 新增 `TemporaryFilesCleanupPP`
  - 成功路径删除双轨/转封装临时文件
  - 失败路径保留源文件，符合 Q7 "失败后保留 7 天供续传" 的方向
- [x] `PostProcessorChain` 在 PP 失败时写入 `DownloadResult.error`，避免合并失败后误归档/误报成功

#### 3. 下载产物闭环
- [x] `YoutubeDL.stageDownload()` 双轨路径改为：
  1. 先解析最终输出 `{base}.mp4`，应用冲突策略
  2. 下载视频到 `{base}.video.<ext>`
  3. 下载音频到 `{base}.audio.<ext>`
  4. 交给 `FFmpegMergerPP` 生成最终 mp4
- [x] `DownloadResult.isSuccess()` 改为严格检查 `outputFile` 存在；新增 `hasDownloadedSources()` 表示"下载阶段成功但后处理尚未完成"
- [x] `YoutubeDL.extractAndDownload()` 改为下载阶段检查 `hasDownloadedSources()`，后处理后再检查 `isSuccess()`

### P0 盲点修复

| # | 盲点 | 状态 | 实现方式 |
|---|------|------|----------|
| A1 | Service 非 Foreground | ✅ | `BackgroundDownloadService` 加 `foregroundServiceType=dataSync` + 常驻通知；AndroidManifest 声明 `FOREGROUND_SERVICE_DATA_SYNC`；API 34+ 用 `startForeground(id, n, FGS_TYPE_DATA_SYNC)` |
| A2 | 队列纯内存 | ✅ | 新建 `DownloadTaskDbHelper`（SQLite，表 `tasks`）；`DownloadQueueManager.init(Context)` 恢复未完成任务 + 重置 RUNNING→PENDING + 恢复 `taskIdGenerator`；`enqueue`/`processTask`/`cancelAll` 全量持久化；`BackgroundDownloadService.onCreate` 调 `init(this)` |
| A5 | 权限未运行时请求 | ✅ | `UniversalDownloadActivity.onCreate` 调 `requestRuntimePermissions()` 批量请求 POST_NOTIFICATIONS + READ_MEDIA_VIDEO(13+) + WRITE_EXTERNAL_STORAGE(10-)；MANAGE_EXTERNAL_STORAGE(11+) 走 Toast 引导 + 手动跳设置页 |
| B1 | 无 .nomedia | ✅ | `YoutubeDL.ensureOutDir` 创建目录后写 `.nomedia`；`ensureNomedia(dir)` 已存在则跳过 |
| B2 | 完成后不通知 MediaScanner | ✅ | 新建 `MediaScannerNotifier.scanFile()` 用 `MediaScannerConnection.scanFile` 显式单文件扫描（不受 .nomedia 影响）；`YoutubeDL` ⑥.7 调用扫描 outputFile + downloadedFiles |
| C1 | .meta 只存整数 | ✅ | `HlsDownloader` 改用 JSON `.ytdl` 文件，存 `fragment_index` + `total_fragments` + `urls_sha256`（SHA-256 指纹检测播放列表变更）；不匹配则删 .part 从头下载 |
| C2 | 无 Content-Range 校验 | ✅ | `UniversalDownloadManager.doDownloadWithResume` 校验 `Content-Range: bytes {start}-` 的 start == 已有大小；不匹配时 log + 删 .part 从头下载；416 重连路径也注入 headers + Cookie |
| D1 | WebView Cookie 不共享 | ✅ | 三层注入全局 `CookieJar`：`UniversalDownloadManager.applyRequestHeaders`（HTTP FD）+ `ExtractorHttp.openConnection`（提取器）+ `HlsDownloader.applyCookie`（HLS FD）；`HlsFD.download` 从 `UniversalDownloadManager.getGlobalCookieJar()` 获取 jar |

> P1 A6（FileProvider 路径）顺带完成：`file_paths.xml` 添加 `DataRecovery/Downloads` external-path。

---

## Deviations

> 当遇到边界情况必须偏离计划时，在此记录：题号 / 偏离内容 / 保守理由 / 影响范围。

### D-M1-01 · 构建验证因网络阻塞延期

- **偏离内容**：M1 收尾的 `gradlew assembleDebug` 构建验证未能执行。
- **触发原因**：本机网络无法访问 `dl.google.com`（TLS 握手失败：`Remote host terminated the handshake`），
  AGP 8.7.3 的 `com.android.tools:sdk-common:31.7.3` / `repository:31.7.3` 既无法在线拉取也未缓存到本地
  Gradle caches（`--offline` 同样失败：`No cached version ... available for offline mode`）。
- **保守方案**：
  1. **不修改** `settings.gradle` 的仓库声明（避免给其他开发者引入镜像副作用，亦不删除官方源）；
  2. **不创建** androidx.annotation 桩文件做独立 `javac` 验证（被用户取消，过度工程）；
  3. 改为对本次新增/修改代码做**人工 API 签名核对**：
     - `DownloadTaskDbHelper.insertTask(DownloadTask)` / `getMaxTaskId()` → `int` /
       `getRestorableTasks()` → `List<DownloadTask>` / `updateStatus(int, TaskStatus, String)` /
       `updateResult(int, String, long, String)` 五个方法签名与 `DownloadQueueManager` 调用点逐一比对一致；
     - `DownloadTask` 包私有构造器 `(id, url, mime, pageUrl, pageTitle, priority)` 与 `getRestorableTasks` 重建逻辑一致；
     - `init(Context)` 幂等（`initialized` 双重检查锁）；`enqueue`/`processTask`/`cancelAll` 持久化调用均带 `dbHelper != null` 守卫，未初始化时安全退化为无持久化模式；
     - `BackgroundDownloadService.onCreate` 仅新增一行 `queueManager.init(this)`，无类型风险。
- **影响范围**：仅 M1 验证阶段，不影响代码逻辑。恢复网络后或配置内网镜像后需补跑一次 `gradlew assembleDebug` 确认。
- **遗留任务**：网络恢复后补跑构建；如持续无法访问 Google 源，可在 `settings.gradle` 的
  `pluginManagement.repositories` 与 `dependencyResolutionManagement.repositories` 顶部插入阿里云镜像
  （`https://maven.aliyun.com/repository/google` 与 `.../public`），届时单独记一条 Deviation。

### D-M1-02 · MANAGE_EXTERNAL_STORAGE 采用引导而非强制跳转

- **偏离内容**：计划 §A5 描述"MANAGE_EXTERNAL_STORAGE 走 Settings intent"，实现上改为 `Toast` 提示 +
  提供 `launchAllFilesAccessSettings()` 由用户主动触发，而非 `onCreate` 中自动 `startActivity`。
- **保守理由**：Android 11+ 的"所有文件访问"权限敏感，`onCreate` 自动跳系统设置页会让用户困惑
  （应用刚打开就跳走），且 Google Play 对该权限有审核风险。改为提示 + 用户主动触发更合规。
- **影响范围**：仅 `UniversalDownloadActivity` 启动流程；用户仍可一键跳设置，功能不缺失。

### D-M1-03 · C1 续传指纹用 SHA-256(分片 URL 列表) 而非单分片 URL

- **偏离内容**：计划 §C1 描述 `.ytdl` 存 `fragment_url`（单分片），实现上改为存 `urls_sha256`
  （所有分片 URL 拼接后的 SHA-256 指纹）。
- **保守理由**：单分片 URL 仅能检测该分片是否变更，无法检测播放列表整体重排（插入/删除/顺序变化）。
  用整体 SHA-256 指纹更严格——任何分片 URL 变化都会触发"从头下载"，避免续传到错误的拼接顺序。
- **影响范围**：仅 `HlsDownloader` 续传判定逻辑；不匹配时删 `.part` 从头下载，行为更保守。

### D-M2-01 · libav JNI 桥缺失，M2 先用 MediaMuxer 后备路径

- **偏离内容**：计划 D1 选定"复用现有 `.so` + JNI 封装"实现 `FFmpegMergerPP` / `RemuxPP`。
  当前仓库只有 `app/src/main/jniLibs/armeabi-v7a/libavcodec.so` / `libavformat.so` / `libavutil.so`，
  没有 NDK/CMake 配置、JNI bridge 源码或 libav 头文件。直接声明 native 合并方法会导致运行时
  `UnsatisfiedLinkError`，比不接入更危险。
- **保守方案**：
  1. 新增 `FFmpegNative` 抽象，只检测 libav 可用性，不声明不可实现的 native 合并 API；
  2. `FFmpegMergerPP` / `FFmpegVideoRemuxerPP` 先走 Android `MediaMuxer` 后备实现，覆盖常见 mp4/m4a 双轨合并与 `.ts` 转封装；
  3. 对 MediaMuxer 不支持的编码/容器，PP 写入 `DownloadResult.error`，保留源文件，不归档、不误报完成。
- **保守理由**：保证 M2 下载链路可运行且失败可见；避免引入不可调用的 JNI 空壳。后续补齐 NDK bridge 时只需替换
  `FFmpegNative.merge/remux`，上层 PP 接口不变。
- **影响范围**：仅 M2 后处理能力边界。真机验证时若遇到 WebM/AV1/Opus 等 MediaMuxer 不支持组合，会保留双轨源文件并提示合并失败。
- **遗留任务**：单独补 `externalNativeBuild` / JNI bridge / libav headers 后，将 `FFmpegNative.isBridgeAvailable()` 切到真实能力检测。

### D-M2-02 · Gradle 单元测试被 AAPT2/UCRT 环境阻塞

- **偏离内容**：`.\gradlew.bat :app:testDebugUnitTest --tests "com.example.cleanrecovery.ytdlp.SmartFormatSelectorTest"` 未能执行到测试阶段。
- **触发原因**：`:app:processDebugResources` 阶段 AAPT2 daemon 启动失败，错误提示：
  `Please check if you installed the Windows Universal C Runtime`。
- **保守方案**：
  1. 不修改 Gradle / AGP / 仓库配置；
  2. 用 `javac -encoding UTF-8 -cp D:\ProgramFile\AndroidSDK\platforms\android-35\android.jar`
     对 M2 新增类 + 核心模型做直接编译验证；
  3. 再用 `javac -sourcepath app\src\main\java` 直接编译 `YoutubeDL.java`，确认真实依赖下类型接入通过。
- **验证结果**：
  - M2 新增类与核心模型直接编译通过；
  - `YoutubeDL.java` 真实依赖直接编译通过；
  - 新增 JUnit 测试已写入，但需修复本机 UCRT/AAPT2 环境后由 Gradle 执行。
- **影响范围**：仅本机自动化测试执行；不影响代码层编译签名核对。真机构建前需修复 UCRT 或换可用构建机补跑。

---

## 参考资产复用记录

按计划 §reuse 复用 `D:\trae-project\CleanARecoveryApp\参考\yt-dlp\` 资产：

| 阶段 | 资产路径 | 复用方式 | 状态 |
|------|----------|----------|------|
| YoutubeDL 编排器 | `yt_dlp/YoutubeDL.py` | 参考 | M1 完成（六阶段骨架 + D5 退化版选 format） |
| PostProcessor 接口 | `yt_dlp/postprocessor/common.py` | 直译 | M1 完成（接口 + Chain + 8 阶段枚举，PP 实际实现在 M2） |
| FFmpeg PP | `yt_dlp/postprocessor/ffmpeg.py` | 参考 | M2 部分完成（Merger/Remuxer 上层 PP 接口完成；libav JNI 缺口见 D-M2-01，当前用 MediaMuxer 后备） |
| Archive | `yt_dlp/YoutubeDL.py` archive 字段 | 直译 | M1 完成（`DownloadArchive` + `archive.txt`） |
| CookieJar | `yt_dlp/cookies.py` `YoutubeDLCookieJar` | 参考 | M1 完成（`PersistentCookieJar` Netscape 格式 + `WebViewCookieSync`） |
| HLS .ytdl 续传 | `yt_dlp/downloader/fragment.py` | 直译 | M1 完成（JSON `.ytdl` + `urls_sha256` 指纹） |
| Content-Range 校验 | `yt_dlp/downloader/http.py` | 参考 | M1 完成（`doDownloadWithResume` + 不匹配删 .part） |
| FileDownloader | `yt_dlp/downloader/common.py` | 参考 | M1 完成（`FileDownloader` 接口 + `DownloaderRegistry` PROTOCOL_MAP） |
| 队列持久化 | （无对应 yt-dlp 资产，Android 平台特性） | 自研 | M1 完成（`DownloadTaskDbHelper` SQLite + `init(Context)` 恢复） |
| Foreground Service | （无对应 yt-dlp 资产，Android 平台特性） | 自研 | M1 完成（`dataSync` 类型 + 常驻通知） |
| MediaScanner 通知 | （无对应 yt-dlp 资产，Android 平台特性） | 自研 | M1 完成（`MediaScannerNotifier.scanFile` 显式单文件扫描） |

---

## M1 完成总结

**完成时间**：2026-07-06

### 已交付
- **架构地基**（4 块）：统一数据模型、类型接口层、中心编排器 `YoutubeDL`、嗅探路径适配 `SniffedUrlAdapter`
- **8 条 P0 致命盲点**：A1 / A2 / A5 / B1 / B2 / C1 / C2 / D1 全部修复
- **P1 A6**（顺带）：FileProvider 路径补充 `DataRecovery/Downloads`

### 验收标准达成情况
| 验收项 | 状态 | 说明 |
|--------|------|------|
| 粘贴 Bilibili 链接 → 自动识别+下载+落盘+相册可见 | 🟡 代码完成，待真机验证 | `YoutubeDL` 六阶段 + `MediaScannerNotifier` 已实现；网络恢复后需跑构建+真机测试 |
| 后台不被杀（Foreground Service） | ✅ | `BackgroundDownloadService` 已转 `dataSync` 前台服务 + 常驻通知 |
| 旋转不丢状态（任务持久化） | ✅ | `DownloadTaskDbHelper` SQLite + `init(Context)` 恢复 + `taskIdGenerator` 恢复 |
| YouTube 不 403（WebView Cookie 同步） | 🟡 代码完成，待真机验证 | 三层 Cookie 注入（HTTP FD / 提取器 / HLS FD）已实现；需真机验证 YouTube 实际下载 |

### 遗留任务
1. **构建验证**（D-M1-01）：网络恢复后跑 `gradlew assembleDebug`，如有编译错误立即修复
2. **真机端到端测试**：Bilibili + YouTube 各跑一次完整下载流程
3. **M2 后续补强**：真实 libav JNI bridge + 真机双轨合并验证

---

## M2 阶段进度

**开始时间**：2026-07-06
**最近维护**：2026-07-06

### 已交付
- `SmartFormatSelector` 完整 D5 策略
- `YoutubeDL` 接入智能选择器
- `SmartPostProcessor` 默认后处理链
- `FFmpegMergerPP` / `FFmpegVideoRemuxerPP` / `TemporaryFilesCleanupPP`
- `MediaMuxerUtil` 后备合并/转封装实现
- `SmartFormatSelectorTest` 聚焦测试用例

### 验收标准达成情况
| 验收项 | 状态 | 说明 |
|--------|------|------|
| 1080p 自动选 `bv+ba` 并合并为 mp4 | 🟡 代码完成，待真机验证 | 常见 mp4/m4a 走 MediaMuxer 后备；libav JNI 缺口见 D-M2-01 |
| 重复链接自动跳过 | ✅ | M1 归档已接入；M2 未改变归档路径 |
| 后处理失败不误报成功 | ✅ | PP 失败写入 `DownloadResult.error`，后处理后 `isSuccess()` 严格检查最终文件 |
| 成功后临时文件清理 | ✅ | `TemporaryFilesCleanupPP` 在 `AFTER_MOVE` 执行 |
| 自动化测试 | 🟡 环境阻塞 | Gradle 被 AAPT2/UCRT 阻塞；已做 `javac` 直接编译验证 |

### 遗留任务
1. 修复本机 UCRT/AAPT2 后补跑 `gradlew testDebugUnitTest` 与 `gradlew assembleDebug`
2. 真机验证 Bilibili/YouTube 1080p 双轨合并
3. 补真实 libav JNI bridge，替换 MediaMuxer 后备路径
4. 继续 M3：流水线收敛 + Bilibili WBI + 优酷

### 当前维护状态
- `docs/implementation-notes.md` 已同步到当前 M2 代码状态。
- 当前 Git 状态下，`app/src/main/java/com/example/cleanrecovery/ytdlp/`、`app/src/test/java/com/example/cleanrecovery/ytdlp/` 与本文件仍显示为未跟踪路径；未执行 staging/commit，避免与工作区内其他未提交改动混合。
- 最近一次可用验证：
  - `javac -encoding UTF-8 -cp D:\ProgramFile\AndroidSDK\platforms\android-35\android.jar ...` 编译 M2 新增类 + 核心模型：通过；
  - `javac -encoding UTF-8 -sourcepath app\src\main\java ... YoutubeDL.java` 真实依赖编译：通过；
  - Gradle 测试仍受 D-M2-02 的 AAPT2/UCRT 环境问题阻塞。

---

## 后续里程碑（占位）

- **M2 · 智能+合并**：`SmartFormatSelector` + 后处理合并/转封装 + 归档（进行中，JNI bridge 待补）
- **M3 · 收敛+站点**：`SniffedUrlAdapter` 实际接入 B/C + Bilibili WBI + 优库 + 抖音/YouTube 签名
- **M4 · 登录+质量+P1 盲点**：cookiejar 登录态 + 并发分片 + AES 解密
- **M5 · 进阶**：爱奇艺 + 字幕/章节/SponsorBlock + DASH MPD
