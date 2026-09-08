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

项目最低支持 Android 6.0（实际功能可用性受系统版本和设备权限影响）。安装包名称为 `CleanARecovery-<版本编号>.apk`。

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

请在开发分支提交修改，并通过 Pull Request 合入 `main`。

- **开发分支**：推送后自动构建 Release APK 并运行测试，构建产物保留 14 天。
- **Pull Request**：构建用于验证，不读取发布签名密钥，产物不作为用户更新包。
- **main**：构建和测试成功后发布 GitHub Release，包含 APK、SHA-256 校验文件及应用更新清单。

给用户安装的版本以 [Releases](https://github.com/suiyunzou/CleanARecoveryApp/releases) 为准。

<details>
<summary>维护者配置：签名、版本编号与自动推送</summary>

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
