Decision: MERGE_TO_APP

Simulated: true

Measured gain:
- exactRecall: 0.6666666666666666
- derivativeRecall: 0.0
- precision: 1.0
- falsePositiveRate: 0.0
- duplicateRate: 0.5
- candidateCount: 2

Measured cost:
- runtimeMs: 35
- bytesRead: 400

Known failure modes:
- Synthetic fixtures only; no real OEM trash/cache samples.

License:
- In-house experiment code.

Security/privacy:
- No user data used.

Reason:
Simulated: deduper collapsed 2 duplicates while preserving 2 unique exact matches. Provenance fields sufficient for coordinator integration after A/C pass real benchmarks.
