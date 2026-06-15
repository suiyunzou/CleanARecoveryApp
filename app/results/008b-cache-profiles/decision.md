Decision: REWORK_ONCE

Simulated: true

Measured gain:
- exactRecall: 0.0
- derivativeRecall: 0.0
- precision: 0.0
- falsePositiveRate: 1.0
- duplicateRate: 0.5
- candidateCount: 2

Measured cost:
- runtimeMs: 680
- bytesRead: 102400

Known failure modes:
- Synthetic fixtures only; no real OEM trash/cache samples.

License:
- In-house experiment code.

Security/privacy:
- No user data used.

Reason:
Simulated: cache profiles found 1 derivative thumbnail match and blob extractions. Exact recall below 5% incremental threshold; profile evidence is promising but needs real device validation.
