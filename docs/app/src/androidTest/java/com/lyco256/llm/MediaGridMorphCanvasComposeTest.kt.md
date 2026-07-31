# `MediaGridMorphCanvasComposeTest.kt`

## 2026-07-31 production Morph correction coverage

- Compose coverage verifies one Canvas, hidden normal visuals, fixed-focal pan behavior, explicit Placeholder edge crossfade, and resident-miss rendering.

## 2026-07-30 interactive TEST_HARNESS

- 実multi-touch sequenceで一本指非consume、二本指tracking、dead zoneへの復帰、反対方向切替、固定pointer ID、三本目無視、2次元中心移動、release後Awaitingと一回のhandoffを検証する。
- interactive layerのrender model／画像解決counterがpointer event数では増えずplan切替境界だけに限定されること、Awaiting中もCanvasが残ることを検証する。
- pointer cancelがIdleへ戻りhandoffを生成しないことを検証する。

## 対応ソース

`app/src/androidTest/java/com/lyco256/llm/MediaGridMorphCanvasComposeTest.kt`

## 主な確認

- progressを40回更新してもrender model、Asset解決、unique title計測回数が増えず、prepared pair変更とprepared index version変更では各一回だけ再構築する。
- `Disabled`ではCanvas、model、画像解決、文字計測が存在しない。
- `media_grid_morph_canvas`が一つだけ存在し、pointer inputを持たないoverlay越しの実touchが下層Buttonへ届く。
- RGB_565の赤／青を0、0.25、0.5、0.75、1で描き、視覚的寄与が`1-progress`／`progress`になる。
- 同一Assetはalpha 1、startのみ／endのみは片側fade、resident missは下地を変えない。
- 追加header背景が50%／100%の高さで全幅・不透明になり、300-entry resident indexからbounded slot分だけをrender modelが保持する。

既存のresident crop単体テストとMorph bucket／header alpha単体テストを組み合わせ、横長／縦長square crop、日／週／月／いいね数見出し、追加／削除bandも固定します。
