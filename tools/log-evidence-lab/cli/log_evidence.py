#!/usr/bin/env python3
"""Log evidence research CLI (Plan 008D)."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from log_evidence.matcher import LogEvidenceMatcher
from log_evidence.rules import LogEvidenceRule


def main() -> int:
    parser = argparse.ArgumentParser(description="Log evidence matcher scaffold")
    parser.add_argument("--log", required=True, help="logcat or bugreport excerpt")
    parser.add_argument("--output", required=True, help="output directory")
    args = parser.parse_args()

    rules = [
        LogEvidenceRule(
            package_name="com.example.test",
            tag_pattern="MediaProvider",
            message_pattern=r"deleted file path=\{field\}",
            evidence_fields=("field",),
            source_type="file_path",
        )
    ]
    matcher = LogEvidenceMatcher(rules)
    hits = matcher.match_file(args.log)

    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    payload = [
        {
            "rule_id": hit.rule_id,
            "field": hit.field,
            "value": hit.value,
            "line_number": hit.line_number,
        }
        for hit in hits
    ]
    (output / "evidence.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    (output / "decision.md").write_text(
        "Decision:\n- KEEP_AS_OFFLINE_TOOL\n\nReason:\nEvidence-only branch. Does not count toward file recovery metrics.\n",
        encoding="utf-8",
    )
    print(json.dumps(payload, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
