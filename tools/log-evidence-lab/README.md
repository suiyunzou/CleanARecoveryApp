# Log Evidence Lab (Plan 008D)

Offline log evidence research tool. Outputs path/URI/timestamp clues only. Does **not** enter APK recovery counts.

## Run

```powershell
cd tools\log-evidence-lab
python cli\log_evidence.py --log C:\path\to\logcat.txt --output ..\..\results\008h-log-evidence
```

## Output

- `evidence.json`
- `decision.md`
