# 枢（Shu）

Android 多功能应用，包含文件扫描与恢复、文件浏览、音乐播放、视频下载、网页浏览和本地代理等功能。项目采用 Java 与 Gradle，应用模块为 `app`。

## 构建

准备 JDK 17 和 Android SDK。SDK 平台版本以 `app/build.gradle` 的 `compileSdk` 为准，在本地 `local.properties` 中配置 SDK 路径（该文件不提交）：

```properties
sdk.dir=D:/Android/SDK
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
```

Linux / macOS：

```sh
sh gradlew :app:assembleRelease --console=plain
```

APK 位于 `app/build/outputs/apk/release/`。具体文件名及签名方式以所选分支的构建配置为准；Release 构建类型不代表已使用正式发布证书。发布新版本时需保持应用 ID 和签名一致，并增加 `versionCode`。

如本机 JVM 出现 G1 启动或内存错误，可附加以下构建参数：

```text
--no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m -XX:+UseSerialGC -Dfile.encoding=UTF-8"
```

## 源码结构

- `app/src/main/`：应用代码、界面资源、清单与运行时依赖资源。
- `app/src/test/`：本地单元测试。
- `app/src/androidTest/`：设备测试与测试夹具。
- `app/build.gradle`：应用构建配置。
- `gradle/wrapper/`：Gradle Wrapper。

## 使用说明

文件扫描与恢复受系统权限、存储加密及文件是否仍存在影响，无法保证找回已彻底删除的数据。网络、音乐和代理功能需要相应服务可用，部分操作需要用户授予权限。

## 仓库范围

仓库仅维护应用源码、必要资源、测试、构建文件和本 README。开发笔记、参考工程、个人工具配置、测试输出及 APK 保留在本地，不纳入 Git 跟踪。

## 提交、构建与发布

- 开发分支推送后，GitHub Actions 自动构建 **Release APK** 并运行单元测试；构建产物保留 14 天，不发布给普通用户。
- 通过 Pull Request 将改动合入 `main` 后，构建成功才创建公开 GitHub Release，附带 APK、SHA-256 校验文件和 `update.json` 更新清单。
- 应用的“检查更新”从 GitHub 获取新版，用户确认后下载并调用 Android 系统安装界面。系统安装仍需用户确认。
- 云端 `versionCode` 为 `100000 + GITHUB_RUN_NUMBER`，版本名为 `0.1.2.<运行序号>`，无需为每次构建提交版本号变更。保留工作流文件名称及运行序号；迁移工作流时必须保证新编号高于所有已发布版本。
- APK 固定命名为 `CleanARecovery-<versionCode>.apk`。同一次运行重试不会覆盖已公开的安装包。
- 签名材料存储于仓库 Actions Secret `ANDROID_KEYSTORE_BASE64`，不能提交到代码仓库。当前沿用已有 Android Debug 证书以兼容已安装用户，并非新生成的正式证书。缺少签名或证书不匹配时禁止发布。
- Pull Request 构建不读取签名密钥，产物仅用于检查；同仓库分支 push 构建沿用现有签名。给用户安装的版本以 Releases 页面为准。

`.github/workflows/android.yml` 是必要的构建发布配置，纳入源码仓库。

本开发工作区已配置本地 `post-commit` 钩子：成功执行 `git commit` 后推送当前分支到 `origin`。钩子留在 `.git/hooks/`，不上传；新克隆或其他电脑不会自动继承。离线、鉴权失败或远端有新提交时保留本地提交并明确报错，解决问题后执行 `git push`，不会强推。可用 `git config --local shu.autoPush false` 关闭，设为 `true` 恢复。

本地默认版本为 `0.1.2 (3)`。若设备已安装云端较高版本，本地覆盖验证需显式传入更高的 `-PreleaseVersionCode=<编号>`，不能卸载用户应用来绕过版本或签名问题。
