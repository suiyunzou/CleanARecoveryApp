from dataclasses import dataclass, field


@dataclass
class SitState:
    valid_blocks: set[int] = field(default_factory=set)
    candidate_free_blocks: set[int] = field(default_factory=set)
    journal_overrides: dict[int, bool] = field(default_factory=dict)

    def apply_journal(self) -> None:
        for block, is_valid in self.journal_overrides.items():
            if is_valid:
                self.valid_blocks.add(block)
                self.candidate_free_blocks.discard(block)
            else:
                self.valid_blocks.discard(block)
                self.candidate_free_blocks.add(block)

    def unallocated_candidates(self) -> set[int]:
        return set(self.candidate_free_blocks)
