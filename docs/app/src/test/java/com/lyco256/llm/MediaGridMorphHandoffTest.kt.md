# `MediaGridMorphHandoffTest.kt`

## 2026-08-01 Phase 2 coverage

- Coordinator tests cover target ordinal/row/header/cell geometry verification, one-pixel correction, rollback, stale data, viewport mismatch, and checkpoint suppression.
- Controller tests cover target identity during `RevealTarget` and exactly-once reveal acknowledgement.

Identity mismatch assertions require the controller to remain in explicit `Failed` with `IdentityMismatch`, rather than silently returning to `Idle`; the stale Morph plan and lock are cleared so recovery remains possible.

## 2026-07-31 production handoff correction coverage

- Unit coverage keeps target settle/request/callback exactly-once behavior, identity mismatch diagnostics, target visibility correction, and rollback semantics explicit.

実LazyGrid handoffのCompose非依存契約を検証するUnit Test。

- bounded plan内のtarget anchor優先順位、focal位置、targetなし
- source frame再通知とexpected target identityの許可
- source revision、data key、viewport、想定外columnの個別cancel
- column変更、complete、rollback、最終checkpointのexactly-once
- target Asset消失時のordinal fallbackと末尾clamp
- visible化、最大3回のY補正、geometry不一致rollback
- stale displayではrollback／checkpointを行わないこと
- active requestを別generationが上書きしないこと

時間待ち、Compose layout、画像、resident storeを使わず、coordinatorの入力イベントとcommandだけを検証する。
