# Plan 008 模拟执行汇总（假数据）

> `simulated: true` — 以下结论**不能**作为产品上线依据，仅用于在缺少真机/数据集时跑通 benchmark 流程。

## 假数据集

- 3 条 ground truth：2 张“已删原图”（SHA 精确匹配）+ 1 条缩略图派生（perceptual hash）
- 合成 JPEG blob、损坏 JPEG、MediaStore/缓存候选列表
- 清单见 `corpus-manifest.csv`

## 各分支模拟结论

| 实验 | 决策 | 模拟观察 |
|---|---|---|
| 008a-mediastore | **KEEP_AS_INDEX_ONLY** | 1 条 trash 精确命中，但 40% 重复；pending/stale 仅元数据 |
| 008b-cache-profiles | **REWORK_ONCE** | 命中 thumbnail 派生 + blob 提取，精确增量不足 |
| 008c-jpeg-blob-carver | **REWORK_ONCE** | 结构化解析在合成 blob 中找到 2 个 JPEG，1 条精确匹配 |
| 008d-result-provenance | **MERGE_TO_APP** | 去重逻辑有效，保留 2 条唯一精确匹配 |
| 008f-jpeg-fragment-validator | **REWORK_ONCE** | 损坏样本检出 partial，完整 JPEG 解析通过 |
| 008e-f2fs-metadata-recovery | **KEEP_AS_OFFLINE_TOOL** | 仅脚手架，无真实镜像 |
| 008h-log-evidence | **KEEP_AS_OFFLINE_TOOL** | 仅脚手架，无真实日志语料 |

## 当前可合并项（模拟）

仅 **008d 结果来源与去重** 在模拟中达到可接入 `RecoveryCoordinator` 的前置条件。  
扫描引擎（MediaStore / 缓存 / carving）均需真机复测后再决定。

## 重新运行

```powershell
$env:ANDROID_HOME = 'C:\Users\86198\Desktop\CleanARecoveryApp\_android_sdk'
.\gradlew.bat :app:testDebugUnitTest --tests com.example.cleanrecovery.experiment.Simulated008BenchmarkTest
```

输出目录：`results/<experiment-id>/`
