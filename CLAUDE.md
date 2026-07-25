# CLAUDE.md

## 项目

- **清寻恢复**：non-root、copy-only Android 文件恢复。Java + Gradle。
- 恢复链路：扫描 → 复制到 `DataRecovery`。**禁止**删改被扫描源文件。
- 附加：酷狗音乐、视频链接提取/下载、文件浏览器（`recycle/` 回收站，与恢复无关）。
- 代码根：`app/src/main/java/com/example/cleanrecovery/`。

## 环境

- Windows 10 + PowerShell 7；命令用 `.\gradlew.bat`，**禁止** `./gradlew`。
- JDK **17**；compileSdk/minSdk/targetSdk = **35/23/35**。
- 须配置 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir`。

## 命令

| 场景 | 命令 |
| --- | --- |
| 构建 | `.\gradlew.bat :app:assembleDebug` |
| Lint | `.\gradlew.bat :app:lintDebug` |
| 单测 | `.\gradlew.bat :app:testDebugUnitTest` |
| 仪器测试 | `.\gradlew.bat :app:connectedDebugAndroidTest` |
| 酷狗 API | `.\gradlew.bat runRealDataTest` |
| 酷狗云歌单 | `.\gradlew.bat runRemotePlaylistRealDataTest` |
| 清理 | `.\gradlew.bat clean` |

## 结构（改代码前先找对包）

- 新扫描策略 → `algorithm/` + `AlgorithmRegistry`
- 恢复落盘 → `recovery/`（`RecoveryCopier`）
- 链接提取 → `extractor/` + `ExtractorRegistry`
- 酷狗 → `music/api/`、`music/security/`
- UI → `ui/`、`music/ui/`（**禁止**直接 HTTP）
- 复用逻辑 → `util/`

## 硬约束

- **禁止** 扫描逻辑写进 Activity。
- **禁止** 恢复链路删改源文件；**禁止** 与 `recycle/` 混写。
- `CandidateSourceKind` **必须**如实；**禁止** 缓存/carving 伪造成 `MEDIASTORE_TRASH`。
- 新 Extractor **必须**注册到 `ExtractorRegistry`。
- **禁止** 提交 `local.properties`、密钥、token。
- **禁止** 擅自升级 AGP/Gradle/androidx/material/gson。
- **禁止** 删除用户已有改动。
- **必须** "开发参考酷狗API NodeJS版接口：https://kugoumusicapi-docs.4everland.app/#/?id=kugoumusic-api"；代码参考:https://github.com/MakcRe/KuGouMusicApi
## 改完必跑

- 算法 / 音乐 / 提取 → `testDebugUnitTest`
- 酷狗 API → `runRealDataTest`
- 酷狗云歌单 → `runRemotePlaylistRealDataTest`
- UI / 资源 → `assembleDebug`
- 构建/测试跑不通 → **必须**说明原因，**禁止**假装通过。
