Decision: REWORK_ONCE

Simulated: true

Measured gain:
- exactRecall: 0.3333333333333333
- derivativeRecall: 0.0
- precision: 0.5
- falsePositiveRate: 0.5
- duplicateRate: 0.0
- candidateCount: 2

Measured cost:
- runtimeMs: 120
- bytesRead: 12

Known failure modes:
- Synthetic fixtures only; no real OEM trash/cache samples.

License:
- In-house experiment code.

Security/privacy:
- No user data used.

Reason:
Simulated: structured parser found 2 JPEG(s), baseline found 2. One exact ground-truth match on embedded JPEG #2. Needs real Xiaomi/cache samples before MERGE_TO_APP.
