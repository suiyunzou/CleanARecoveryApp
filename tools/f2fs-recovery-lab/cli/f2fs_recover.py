#!/usr/bin/env python3
"""CLI entry for F2FS recovery lab experiments."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from f2fs_recovery.geometry import F2fsGeometry
from f2fs_recovery.sit import SitState


def main() -> int:
    parser = argparse.ArgumentParser(description="F2FS recovery lab scaffold")
    parser.add_argument("--image", required=True, help="Read-only F2FS image path")
    parser.add_argument("--output", required=True, help="Experiment output directory")
    args = parser.parse_args()

    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    geometry = F2fsGeometry(block_size=4096, segment_count=1024, blocks_per_segment=512, checkpoint_version=1)
    sit = SitState(candidate_free_blocks={10, 11, 12})
    sit.apply_journal()

    payload = {
        "image": str(Path(args.image)),
        "geometry": {
            "block_size": geometry.block_size,
            "segment_count": geometry.segment_count,
            "blocks_per_segment": geometry.blocks_per_segment,
            "checkpoint_version": geometry.checkpoint_version,
        },
        "candidate_free_blocks": sorted(sit.unallocated_candidates()),
        "status": "SCAFFOLD_ONLY",
    }
    (output / "environment.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    (output / "decision.md").write_text(
        "Decision:\n- KEEP_AS_OFFLINE_TOOL\n\nReason:\nScaffold only. Full paper reproduction pending fixtures.\n",
        encoding="utf-8",
    )
    print(json.dumps(payload, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
