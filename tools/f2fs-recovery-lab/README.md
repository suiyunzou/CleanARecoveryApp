# F2FS Recovery Lab (Plan 008B)

Offline F2FS metadata recovery research tool. This code does **not** ship inside the Android APK.

## Scope

- Parse F2FS geometry, SIT, and journal overlays
- Identify candidate unallocated blocks
- Future: node carving, virtual NAT, dentry recovery

## Run scaffold

```powershell
cd tools\f2fs-recovery-lab
python cli\f2fs_recover.py --image C:\path\to\image.img --output ..\..\results\008e-f2fs-metadata-recovery
```

## Decision output

Each run writes `environment.json` and `decision.md` under the output directory.
