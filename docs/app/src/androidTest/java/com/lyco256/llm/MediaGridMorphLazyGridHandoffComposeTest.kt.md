# `MediaGridMorphLazyGridHandoffComposeTest.kt`

## 2026-07-31 production handoff correction coverage

- Handoff cases cover both column directions, header bucket changes, delayed target frames, target draw-before-Canvas-removal, rollback, fallback release, and one-grid scroll suppression.
- Source identity changes assert explicit `Failed + IdentityMismatch` and Canvas removal without invoking the column callback.

TEST_HARNESSの一枚の実`LazyVerticalGrid`でhandoffを検証するCompose Test。

- 2↔3、4↔5、8↔9、11↔12
- headerなし、日↔週、週↔月、8↔9のいいね数bucket粒度変更、11↔12の1000単位bucket維持
- target frame遅延中と列変更直後のCanvas維持
- target Asset消失時のmedia ordinal fallback
- target frame不正時のCanvasを維持したrollback
- viewport先頭、末尾、行の部分表示
- 最大3回のY補正、geometry確認、underlying描画の次frameでのcomplete
- Canvas消失前後の中心pixelとcell境界一致
- column変更、complete、rollbackのexactly-once
- handoff中のuser scroll無効化と完了後の再有効化
- handoff中にMorph render modelと画像解決を再実行しないこと
- production readinessでviewport外overscanとzero-size側Assetを要求しないこと
- 実`LazyVerticalGrid`上で一本指scroll、pure二本指pan、scroll中からpinch、claim時stopScroll一回、三本目追加時のtracked ID固定を検証すること
- pairなしfallbackのclaimとrelease時callback exactly-onceを検証すること

既存gesture、settle、Canvas、header Crossfade回帰は`MediaGridMorphCanvasComposeTest`と`MediaGridMorphTest`が継続して担当する。
