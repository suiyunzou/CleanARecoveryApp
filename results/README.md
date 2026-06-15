# Simulated Plan 008 Results

These artifacts were generated with **fake fixtures** because real devices and deletion corpora are unavailable.

## Run simulation

```powershell
$env:ANDROID_HOME = 'C:\Users\86198\Desktop\CleanARecoveryApp\_android_sdk'
.\gradlew.bat :app:testDebugUnitTest --tests com.example.cleanrecovery.experiment.Simulated008BenchmarkTest
```

Output lands in `results/`.

## Simulated decisions (fake corpus v1)

| Experiment | Decision | Notes |
|---|---|---|
| 008a-mediastore | KEEP_AS_INDEX_ONLY | High duplicate rate vs file scan |
| 008b-cache-profiles | REWORK_ONCE | Derivative hit only; needs real OEM samples |
| 008c-jpeg-blob-carver | REWORK_ONCE | Structured parser beats baseline on synthetic blob |
| 008d-result-provenance | MERGE_TO_APP | Dedup/provenance logic ready for coordinator |
| 008f-jpeg-fragment-validator | REWORK_ONCE | Partial detection works on corrupted fixture |
| 008e-f2fs-metadata-recovery | KEEP_AS_OFFLINE_TOOL | Scaffold only |
| 008h-log-evidence | KEEP_AS_OFFLINE_TOOL | Scaffold only |

Do **not** treat simulated metrics as product validation. Re-run on real devices before merging scan engines.
