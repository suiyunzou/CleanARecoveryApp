# 枢（Shu）

枢是一款 Android 多功能应用，将网页浏览、文件管理、音乐播放、视频下载与本地代理集中在一起，方便在日常使用中切换。

[下载最新版](https://github.com/suiyunzou/CleanARecoveryApp/releases/latest) · [查看版本记录](https://github.com/suiyunzou/CleanARecoveryApp/releases) · [反馈问题](https://github.com/suiyunzou/CleanARecoveryApp/issues)

## 主要功能

- **网页浏览**：书签与历史记录、脚本管理、主页定制、二维码扫描。
- **文件管理**：浏览本地文件，扫描可恢复的文件。
- **音乐与视频**：音乐播放、视频下载与播放。
- **本地代理**：配置和使用代理服务。

文件恢复能力受 Android 权限、存储加密及文件状态影响，无法保证找回已经彻底删除的数据。在线内容和代理连接需要相应服务可用。

## 下载与安装

1. 打开 [最新版本页面](https://github.com/suiyunzou/CleanARecoveryApp/releases/latest)。
2. 在 **Assets（资源）** 中下载以 `.apk` 结尾的安装包，不要选择 Source code 压缩包。
3. 打开 APK，按 Android 提示允许当前来源安装应用，并完成安装。

项目最低支持 Android 7.0（内置 yt-dlp 的 Python/QuickJS 运行库要求 API 24；实际功能可用性受系统版本和设备权限影响）。安装包名称为 `CleanARecovery-<版本编号>.apk`。

### 视频下载

在下载页粘贴视频链接或分享文案，每批最多 20 条。解析后可为每条视频选择实际提供的画质、编码或纯音频，再开始下载。下载按顺序执行，单条失败不会中止其他条目；暂停后可继续，返回页面仍可查看进度、已下载量及已知的总大小。输入为空时显示粘贴图标，有内容时显示清空图标。

下载使用实际 yt-dlp、QuickJS 和 FFmpeg，包含 HLS 分片与 DASH 音视频合并。当前优先验收 YouTube、B站和抖音；登录、会员、地区及站点验证仍由原网站决定。出现会话或验证提示时，在应用浏览器打开原链接处理后重试失败条目的解析。只显示当前账号可取得的格式，不承诺获取所有平台的全部清晰度。

当前验收状态：YouTube、B站已有多画质、不同编码及纯音频的实际下载验证；抖音已验证两档竖屏画质、分享页及短链，短链样本完整时长超过 5 分钟。三平台混合批量已在 Release 页面选画质后完成下载，并检查实际分辨率、音轨和时长。扩展的微博、X/Twitter 视频及 SoundCloud 音频也已完成 Release 实际下载验证。

抖音通过独立插件补充请求签名，按分辨率和编码保留稳定选择，并检查同档候选地址；某档不可用时不自动降画质。插件来源、修改说明及许可证见 APK 的 `assets/ytdlp/NOTICE.txt`。平台支持不代表全部链接、地区或账号状态均可下载；Vimeo、TikTok 本轮样本受站点客户端限制，未列入已验证平台。直播、整个合集和受保护内容不在本轮支持范围。

抖音优先选取同画质的无平台水印播放源；仅确认的格式标记“无水印”，不会为此自动降低画质。B站已接入可选 TV 无水印源检测，但当前真实接口返回 `-400`，仅确认网页源下载正常，无水印源尚未实测通过。此功能不消除作者已嵌入画面的文字或 Logo。

文件保存到设置中的下载目录，同名文件自动另存；“打开文件”进入应用内部的文件所在目录。下载任务及选择保存在本机，进程重启后由用户继续未完成任务；不自动重启网络下载。下载出错时可用“更新引擎”更新 yt-dlp，内置版本、来源和校验摘要见 APK 的 `assets/ytdlp/NOTICE.txt`。

### 更新应用

在应用设置中选择 **检查更新**。发现新版后，可在应用内下载，或点击 **前往 GitHub 下载** 打开对应版本页面；尚未获取版本信息时，该按钮打开最新发布页。

应用也会定期检查新版。下载完成后仍需在 Android 系统安装界面确认安装。如果检查或下载失败，可以直接访问上方的下载链接。更新时请使用同一发布渠道的安装包，通常无需卸载已有应用。

## 问题反馈

请在 [Issues](https://github.com/suiyunzou/CleanARecoveryApp/issues) 中描述问题，并尽量提供：

- 应用版本、手机型号和 Android 版本。
- 出现问题的操作步骤，以及预期与实际表现。
- 有助于定位的截图或录屏；提交前请遮挡账号、令牌等个人信息。

## 从源码构建

开发环境需要 **JDK 17** 和 **Android SDK 35**。克隆仓库后，在本地 `local.properties` 中配置 SDK 路径，例如：

```properties
sdk.dir=D:/Android/SDK
```

构建 Release APK：

```powershell
# Windows PowerShell
.\gradlew.bat :app:assembleRelease --console=plain
```

```sh
# Linux / macOS
sh gradlew :app:assembleRelease --console=plain
```

输出文件为 `app/build/outputs/apk/release/app-release.apk`。

当前构建沿用 Android Debug 证书签署 Release 包，以保持现有安装的更新兼容性。Release 是构建类型，并不代表使用了正式发布证书。本地默认读取 `~/.android/debug.keystore`，也可以通过 `RELEASE_KEYSTORE_PATH` 指定兼容密钥；构建前需确保密钥存在。自行构建时，不同签名的 APK 无法直接覆盖官方发布包。

本地默认版本为 `0.1.2 (3)`。需要覆盖已安装的云端版本时，使用 `-PreleaseVersionCode=<更高编号>` 指定版本编号，并保持应用 ID 和签名一致。

### 测试

运行与持续集成相同的单元测试：

```powershell
.\gradlew.bat :app:testReleaseUnitTest -PexternalServiceTests=false --console=plain
```

将 `externalServiceTests` 设为 `true` 可额外运行依赖第三方服务的联网检查。这些检查可能受登录状态、地区限制或网络延迟影响。

### 代码结构

| 路径 | 内容 |
| --- | --- |
| `app/src/main/` | 应用代码与资源 |
| `app/src/test/` | 本地单元测试 |
| `app/src/androidTest/` | Android 设备测试 |
| `app/build.gradle` | 应用构建配置 |
| `.github/workflows/android.yml` | 持续集成与发布流程 |

## 参与开发与发布

项目级协作规则保存在 [AGENTS.md](AGENTS.md)，新会话开始时应先读取。本机开发脚本（如 `scripts/android/emulator.ps1`）保持 Git 忽略，不随克隆分发；其调用与缺失处理遵循 AGENTS.md，不能仅因共享规则引用了脚本就将它上传。用户要求提交并推送时，提交说明需按实际涉及的“修改、优化、增加、删除”写明具体功能、实现效果和验证结果；提交后核对远端 SHA，不能将本地提交成功等同于推送或发布成功。

日常开发使用 `codex/*` 分支。推送后自动构建和测试，成功后创建（或复用）Pull Request、合入 `main`，并自动启动新版发布，无需逐次点击 Compare、Create PR 和 Merge。

如果 main 有新提交，流程会先同步开发分支并重新测试。构建失败、合并冲突、草稿 PR、要求修改的审查或未解决的审查讨论会停止自动合并，需处理问题后重新推送或重跑任务。旧提交的构建不会合并较新的未验证代码。其他分支及外部贡献者的 PR 仍按手动流程处理。

- **开发分支**：推送后自动构建 Release APK 并运行测试，构建产物保留 14 天。
- **Pull Request**：构建用于验证，不读取发布签名密钥，产物不作为用户更新包。
- **main**：构建和测试成功后发布 GitHub Release，包含四种架构 APK、通用备用 APK、SHA-256 校验文件及应用更新清单。

### 选择安装包

| 文件名 | 适用设备 |
| --- | --- |
| `CleanARecovery-<版本号>-arm64-v8a.apk` | 64 位 ARM 手机 |
| `CleanARecovery-<版本号>-armeabi-v7a.apk` | 32 位 ARM 设备 |
| `CleanARecovery-<版本号>-x86_64.apk` | 64 位 x86 模拟器或设备 |
| `CleanARecovery-<版本号>-x86.apk` | 32 位 x86 模拟器或设备 |
| `CleanARecovery-<版本号>.apk` | 通用备用包；不确定架构或使用旧版更新客户端时选择 |

各架构包只省去其他 CPU 架构的二进制文件，保留本架构的 yt-dlp、Python、FFmpeg 和原有资源。Mihomo 内核沿用现有的 ARM64/x86_64 支持范围，32 位包不含该内核。新版应用内更新按设备支持的架构优先选择安装包，缺失时回退通用包；旧版客户端仍能通过原文件名升级。首次从旧版应用内更新时仍下载通用包，升级后才自动选择小包。

本地 `assembleRelease` 同时生成 `app-<架构>-release.apk` 和 `app-release.apk`（通用包），各包沿用相同的版本号与签名。CI 对五个 APK 分别检查签名、版本和架构，并确认分架构包内容与通用包的对应部分一致；全部上传且哈希校验通过后才公开 Release。

给用户安装的版本以 [Releases](https://github.com/suiyunzou/CleanARecoveryApp/releases) 为准。

<details>
<summary>维护者配置：签名、版本编号与自动推送</summary>

首次启用自动 PR 需要在仓库 Settings → Actions → General 勾选 **Allow GitHub Actions to create and approve pull requests**。默认工作流权限仍可保持只读；仅自动集成任务声明写入代码、PR 和触发 Actions 所需的权限。脚本不会自动批准审查，也不会绕过仓库的合并规则。

自动合并使用 GitHub 内置令牌，并显式触发 `main` 的 `workflow_dispatch` 发布任务，因此不需要额外存储个人访问令牌。关闭自动集成可移除 `.github/workflows/android.yml` 中的 `integrate` 任务。

发布签名保存在仓库 Actions Secret `ANDROID_KEYSTORE_BASE64` 中，不应提交到源码。工作流会校验签名，缺少密钥或证书不匹配时不会发布。

云端版本编号为 `100000 + GITHUB_RUN_NUMBER`，版本名为 `0.1.2.<运行序号>`。迁移工作流时，必须保证新版本编号高于所有已发布版本；同一次运行重试不会覆盖已公开的安装包。

仓库提供可选的提交后自动推送钩子。新电脑克隆后，确认已具备 GitHub 推送权限，再在仓库目录执行：

```sh
git config --local core.hooksPath .githooks
git config --local shu.autoPush true
```

Windows 使用 Git for Windows 即可。启用后，提交会自动推送当前分支到 `origin`；失败时保留本地提交，解决网络、权限或分支问题后可手动执行 `git push`。钩子不会强推，变基过程中及 detached HEAD 状态下不自动推送。

使用 `git config --local shu.autoPush false` 可关闭自动推送。无论是否启用钩子，手动推送都会触发 GitHub 上的构建流程。

</details>
