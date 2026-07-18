# `MediaGridViewportDispatchTest.kt`

2026-07-18 coverage:

- pixel-only movement is suppressed while visible order, columns, and revision changes are accepted;
- consecutive viewport notifications during one generation use the latest viewport after completion;
- stale revision and dispose notifications are rejected;
- holder state reads remain available after intentionally stopping the coordinator;
- the existing one-adjacent-row preparation range remains unchanged.
