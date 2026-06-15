Decision: REWORK_ONCE

Simulated: true

Measured gain:
- exactRecall: 0.0
- derivativeRecall: 0.0
- precision: 0.0
- falsePositiveRate: 1.0
- duplicateRate: 0.0
- candidateCount: 2

Measured cost:
- runtimeMs: 18
- bytesRead: 14

Known failure modes:
- Synthetic fixtures only; no real OEM trash/cache samples.

License:
- In-house experiment code.

Security/privacy:
- No user data used.

Reason:
Simulated: validator marked corrupted fixture as partial (false). Valid JPEG parsed=true. Promising for false-positive reduction; needs mixed corpus.
