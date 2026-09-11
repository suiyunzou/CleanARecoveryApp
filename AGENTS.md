# 项目协作规则

## 环境与执行入口

- 本项目为 Java Android 应用。本地开发使用 Windows、PowerShell、JDK 17 和 Gradle Wrapper；命令默认从仓库根目录执行。
- Android SDK 从 `local.properties`、`ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 解析，不在共享规则中写死电脑路径。
- 修改代码前确认相关模块和已有实现；保留用户未提交的改动，不捎带无关重构。
- 若本地存在 `docs/development-environment.md`，涉及环境或工具启动时先阅读；实际工具参数以对应脚本为准。

## 本地开发脚本（不上传 GitHub）

- `scripts/`、`tools/`、`.task/` 和本地环境文档用于本机开发，继续受 Git 忽略规则保护。不得为了让新会话或新克隆可用而强制添加这些文件、解除忽略或复制到受跟踪目录。
- 根目录 `AGENTS.md` 保存共享操作规则，可以提交；这里引用本地脚本，不表示脚本会随仓库克隆。`.github/` 和 `.githooks/` 中已跟踪的自动化脚本继续正常维护。
- 使用本地脚本前确认文件存在并阅读相关参数。worktree 中缺失时，通过 `git worktree list --porcelain` 查找同仓库已有工作区，确认脚本及依赖后可从其原位置调用，不复制或提交本地工具。
- 所有已知工作区均缺少必要脚本时，说明缺失的文件并向用户询问本地位置；可继续不依赖该脚本的工作，不擅自改用另一套启动或安装流程。

## 模拟器启动与归位

- 用户要求打开模拟器时，使用 `scripts/android/emulator.ps1 start`；只修复现有窗口位置时使用 `fix`。禁止绕过脚本直接运行 `emulator.exe` 或自行拼接 `Start-Process` 启动参数。
- PowerShell 调用示例：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android/emulator.ps1 start`。
- 目标模拟器为 `CleanDev_API35`。报告“已打开”前，确认脚本成功、Android 已就绪且窗口位于屏幕内；只有进程存在或 ADB 在线不算窗口验证。
- SDK 定位复用 `scripts/android/resolve-sdk.ps1`。安装前检查设备列表，明确目标设备，不误装到其他设备。

## Release 构建、安装与验证

- 用户提及打包、构建、导出 APK，或需要安装应用到模拟器时，一律使用包含当前工作区改动的 **Release**；仅用户明确要求其他构建类型时例外。
- 构建命令：`.\gradlew.bat :app:assembleRelease --console=plain`。
- 只有本次 Release 构建成功后，才能安装或交付 `app/build/outputs/apk/release/app-release.apk`；失败时修复或报告具体原因，禁止替换为旧包或 Debug APK。
- 沿用项目签名配置，交付时核实并说明签名类型；Release 构建不等于使用正式发布密钥。
- 按改动运行相关测试。Release 单元测试入口为 `.\gradlew.bat :app:testReleaseUnitTest --console=plain`，需要时用 `--tests` 限定相关测试类。
- 设备测试也必须安装 Release。使用本地 `scripts/android/release-tests.init.gradle` 配置测试构建类型；若存在 `docs/device-test-protocol.md`，先按其设备测试流程执行。配置不兼容时说明问题，不静默降级 Debug。
- 安装失败时停止后续启动步骤；不得为解决版本或签名问题擅自卸载应用、清空用户数据。
- 如实区分构建、单元测试、设备测试和真实网络验证，未执行的检查不得声称通过。

## Git 提交与推送

- 用户要求提交或推送时，检查工作区、当前分支、远端、待提交差异和已有验证结果；只提交本次已授权的修改。
- 提交标题概括主要功能变化；正文按实际涉及的“修改、优化、增加、删除”逐项写明具体功能及实现效果，并列出验证结果和未验证范围。不要用“更新代码”“优化体验”等笼统描述，也不必填写未涉及的分类。
- 使用 UTF-8 说明文件配合 `git commit -F`，保留中文和多行正文；临时说明文件不纳入提交。
- 提交或推送前阅读 [README 的参与开发与发布说明](README.md#参与开发与发布)，检查 `.githooks/post-commit` 及 `core.hooksPath`、`shu.autoPush` 的实际配置。新克隆不会继承本地 Git 配置。
- 用户明确要求仅本地提交或不推送时，在该次提交期间设置 `SHU_SKIP_AUTO_PUSH=1`，结束后恢复原环境变量值，不改变用户的长期配置。
- 提交成功不代表推送成功。用户已授权推送时，核对远端目标分支 SHA 与本地 HEAD；若钩子未推送或失败，处理后正常推送，禁止擅自强推。

## 远端自动化与交付状态

- 自动合并与发布的条件，以 `.github/workflows/android.yml` 和 `.github/scripts/auto-integrate.cjs` 为准；流程说明见 README，不在本文件重复维护工作流实现细节。
- 使用已配置自动集成的 `codex/*` 分支时，推送后由 GitHub Actions 处理，不重复手工创建 PR、合并或发布。
- 分开汇报本地提交、远端推送、CI、合并及 Release 发布状态；只有检查到相应成功结果后才宣称完成该阶段。
- 测试失败、合并冲突或审查阻塞时说明具体原因，不绕过检查，不承诺自动修复所有异常。
