# 真机扫描自测协议

用于 USB 连接设备、通过 `adb` 自动化触发 `scanAll` 并分析 `CleanRecovery` 日志。

## 前置条件

- `adb` 可用，设备已授权 USB 调试
- 已安装 **debug** 包（含 `ScanSelfTestReceiver`）
- 应用已授予「所有文件访问」或 READ 存储权限（Android 11+ 需 `MANAGE_EXTERNAL_STORAGE`）

```powershell
$adb = "D:\ProgramFile\AndroidSDK\platform-tools\adb.exe"
& $adb devices
```

## 1. 何时 STOP（停止判据）

满足 **任一** 条件即停止等待并保存日志：

| 判据 | 日志/信号 | 动作 |
|------|-----------|------|
| **全量扫描超时** | 自 `scanStart` 起超过 **10 分钟** 无 `scanComplete` | STOP，标记 `TIMEOUT` |
| **ANR / 崩溃** | logcat 含 `FATAL EXCEPTION`、`ANR in com.example.cleanrecovery` | STOP，标记 `CRASH` |
| **权限阻断** | `permissionDenied` 或 prepare 阶段 `totalEntries=0` 且存储不可读 | STOP，标记 `PERMISSION` |
| **进度卡死** | **连续 2 分钟** `scanned` 计数无增长（对比 `phaseProgress` / `scanComplete` 前最后一条） | STOP，标记 `STUCK` |

正常结束：`scanComplete` 出现即 STOP，标记 `OK`。

## 2. 错误分类与修复手册

| 错误类型 | 日志特征 | 修复方向 |
|----------|----------|----------|
| **权限错误** | `permissionDenied`、`SecurityException`、`totalEntries=0` | `StorageAccessController`：检查 `hasStorageAccess`、引导 `MANAGE_EXTERNAL_STORAGE`；自测前手动授权 |
| **MediaStore 失败** | `mediastoreError`、`MediaStore` 相关异常 | **跳过该 phase**，打 `warning`，继续 CACHE / 下一类型；不 abort 全扫描 |
| **OOM / JPEG 解码** | `OutOfMemoryError`、`decodeError` | 降低批大小（`ScanLimits.ITEM_BATCH_SIZE`）、JPEG 探测加大小上限或 catch 后 skip |
| **进度虚报 / 卡死** | UI 百分比走但 `scanned` 不变 >2min | `ScanProgressTracker`：核对 `onFileScanProgress` 与 coordinator 回调是否同步 |
| **scanAll 部分失败** | 某一 `RecoveryType` 的 `phaseEnd` 带 error 但后续 type 仍开始 | **继续下一类型**（当前设计）；仅当 prepare 完全失败或 crash 才 abort |

## 3. 优化度量指标

每次自测从 `scanComplete` 及中间 `phaseEnd` 日志提取：

| 指标 | 来源日志字段 | 用途 |
|------|--------------|------|
| **各 phase 耗时** | `phaseEnd … durationMs=` | prepare / file / mediastore / cache 瓶颈 |
| **各来源 found 数** | `scanComplete sourceVisible=` / `sourceMediastore=` / `sourceCache=` | 评估 VISIBLE_SHARED_FILE vs MEDIASTORE vs CACHE 贡献 |
| **去重跳过率** | `duplicatesSkipped / (found + duplicatesSkipped)` | 多源重复是否过多 |
| **误标「已删除」率** | 抽样或统计 `suspectedDeleted=true` 占比 | 校准 `isSuspectedDeletedPath` 规则 |

## 4. 自动化执行步骤

```powershell
$adb = "D:\ProgramFile\AndroidSDK\platform-tools\adb.exe"
$repo = "C:\Users\86198\Desktop\CleanARecoveryApp"

# 1. 构建并安装 debug
cd $repo
.\gradlew.bat assembleDebug
& $adb install -r app\build\outputs\apk\debug\app-debug.apk

# 2. 清空 logcat
& $adb logcat -c

# 3. 唤醒并广播触发自测扫描（仅 debug 包）
& $adb shell am broadcast -a com.example.cleanrecovery.SELF_TEST_SCAN

# 4. 实时过滤（另开终端）或等待后 dump
& $adb logcat -s CleanRecovery

# 5. 等待至 STOP 判据满足（建议脚本轮询，上限 600s）
& $adb logcat -d -s CleanRecovery > feedback-logcat-selftest.txt
```

### 轮询脚本逻辑（伪代码）

```
deadline = now + 10min
lastScanned = 0
stuckSince = now
loop until deadline:
  if log contains scanComplete: break OK
  if log contains FATAL or ANR: break CRASH
  if log contains permissionDenied: break PERMISSION
  parsed = latest scanned count from CleanRecovery
  if parsed > lastScanned: lastScanned = parsed; stuckSince = now
  if now - stuckSince > 2min: break STUCK
  sleep 5s
dump logcat to feedback-logcat-selftest.txt
```

## 5. 日志 TAG

统一使用 **`CleanRecovery`**：

```powershell
& $adb logcat -s CleanRecovery
```

关键事件：`scanStart`、`prepareComplete`、`phaseStart`、`phaseEnd`、`phaseProgress`、`scanComplete`、`algorithmStart`、`algorithmEnd`、`algorithmSkipped`、`algorithmError`、`error`、`permissionDenied`。

## 7. 按算法提取指标

实验扫描或全量扫描后，从 `algorithmEnd` 行提取每个算法的独立指标：

| 指标 | 来源日志字段 | 用途 |
|------|--------------|------|
| **算法耗时** | `algorithmEnd id=… durationMs=` | 比较 file_tree / mediastore / cache / signature / lost_dir 成本 |
| **算法 found 数** | `algorithmEnd id=… found=` | 评估各算法独立贡献（注意后续 dedup 会合并） |
| **算法去重跳过** | `algorithmEnd id=… duplicatesSkipped=` | 单算法重复率 |
| **跳过原因** | `algorithmSkipped id=… reason=` | 区分 disabled / requiresApi / evidenceOnly |

示例过滤：

```powershell
Select-String -Path feedback-logcat-selftest.txt -Pattern "algorithm(Start|End|Skipped|Error)"
```

通过标准补充：IMAGE 实验扫描应出现 `algorithmStart` + `algorithmEnd` 覆盖 `file_tree_visible`、`mediastore_index_trash`（API 29+）、`oem_cache_profiles`、`accessible_signature_sniffer`、`lost_dir_orphan_sniffer`；`offline_*` 与 `log_evidence_import` 仅应出现 `algorithmSkipped`，不应出现 `algorithmError`。

## 6. 通过标准（冒烟）

- 无 crash / ANR
- 出现 `scanComplete` 或合理的 `TIMEOUT` 记录
- `totalEntries > 0`（有存储权限时）
- 各 phase 均有 `phaseStart` + `phaseEnd`（IMAGE 类型含 mediastore/cache 时）
