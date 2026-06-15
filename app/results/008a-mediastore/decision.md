Decision: KEEP_AS_INDEX_ONLY

Simulated: true

Measured gain:
- exactRecall: 0.3333333333333333
- derivativeRecall: 0.0
- precision: 0.3333333333333333
- falsePositiveRate: 0.3333333333333333
- duplicateRate: 0.4
- candidateCount: 3

Measured cost:
- runtimeMs: 420
- bytesRead: 512000

Known failure modes:
- Synthetic fixtures only; no real OEM trash/cache samples.

License:
- In-house experiment code.

Security/privacy:
- No user data used.

Reason:
Simulated: 1 exact trash match, but 60% duplicates vs file scan baseline. Pending/stale rows are metadata-only. Not enough incremental exact recall to MERGE_TO_APP.
