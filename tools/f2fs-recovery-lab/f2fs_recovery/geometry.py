from dataclasses import dataclass


@dataclass(frozen=True)
class F2fsGeometry:
    block_size: int
    segment_count: int
    blocks_per_segment: int
    checkpoint_version: int

    @property
    def total_blocks(self) -> int:
        return self.segment_count * self.blocks_per_segment
