from dataclasses import dataclass


@dataclass(frozen=True)
class LogEvidenceRule:
    package_name: str
    tag_pattern: str
    message_pattern: str
    evidence_fields: tuple[str, ...]
    source_type: str
