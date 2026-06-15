from __future__ import annotations

import re
from dataclasses import dataclass

from .rules import LogEvidenceRule


@dataclass(frozen=True)
class EvidenceHit:
    rule_id: str
    field: str
    value: str
    line_number: int


class LogEvidenceMatcher:
    def __init__(self, rules: list[LogEvidenceRule]) -> None:
        self._rules = rules

    def match_file(self, path: str) -> list[EvidenceHit]:
        hits: list[EvidenceHit] = []
        with open(path, encoding="utf-8", errors="ignore") as handle:
            for line_number, line in enumerate(handle, start=1):
                for index, rule in enumerate(self._rules):
                    if rule.tag_pattern and rule.tag_pattern not in line:
                        continue
                    for field in rule.evidence_fields:
                        pattern = rule.message_pattern.replace("{field}", rf"(?P<{field}>[^\s,;]+)")
                        match = re.search(pattern, line)
                        if match and field in match.groupdict():
                            hits.append(
                                EvidenceHit(
                                    rule_id=f"{rule.package_name}:{index}",
                                    field=field,
                                    value=match.group(field),
                                    line_number=line_number,
                                )
                            )
        return hits
