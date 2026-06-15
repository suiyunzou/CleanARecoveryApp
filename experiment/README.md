# Plan 008 Experiment Infrastructure

This directory holds benchmark outputs for parallel 008 experiments.

## Layout

```text
results/<experiment-id>/
  environment.json
  corpus-manifest.csv
  candidates.csv
  metrics.json
  failures/
  decision.md
```

## Run simulation (no device required)

```powershell
$env:ANDROID_HOME = 'C:\path\to\Android\sdk'
.\gradlew.bat :app:testDebugUnitTest --tests com.example.cleanrecovery.experiment.Simulated008BenchmarkTest
```

This writes fake-corpus benchmark artifacts into `results/`. See `results/README.md` for simulated decisions.

## Branches

| ID | Plan | Location |
|---|---|---|
| 008a-mediastore | 008A | `app/.../experiment/mediastore/` |
| 008b-cache-profiles | 008A | `app/.../experiment/cache/` |
| 008c-jpeg-blob-carver | 008A/008C | `app/.../experiment/jpeg/` |
| 008d-result-provenance | 008A/008D | `app/.../experiment/CandidateDeduper.java` |
| 008e-f2fs-metadata-recovery | 008B | `tools/f2fs-recovery-lab/` |
| 008f-jpeg-fragment-validator | 008C | `app/.../experiment/jpeg/JpegFragmentationValidator.java` |
| 008h-log-evidence | 008D | `tools/log-evidence-lab/` |

Production IMAGE scans now call the 008a/008b/008c experiment scanners via Plan 012; device benchmark gates in `008E` are still pending.
