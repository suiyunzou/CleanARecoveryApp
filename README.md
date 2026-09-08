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
