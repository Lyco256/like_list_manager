# 25. ツイートカード単一画像の縦幅上限

## 目的

極端な縦長画像がツイートカードの大部分を占有しないよう、表示上だけX/Twitter相当の縦長上限を設ける。保存画像そのものは一切加工しない。

## 表示仕様

- 対象は未分類カード、分類済み通常カード、MediaGridから開くツイートpreviewカード。
- 1枚画像の表示枠は幅:高さが最小 `3:4` になるよう制限する。
- 元画像が3:4より縦長なら、
  - カード上の枠を3:4にする。
  - `ContentScale.Crop` 相当で中央cropして表示する。
- 元画像が3:4以上の比率なら、既存の比率表示を維持する。
- 2〜4枚の既存正方形gridは変更しない。
- 全画面画像viewerでは従来どおり元画像全体を見られる。viewer用画像、保存WebP、DB width/heightを変更しない。

## 実装

- `AssetEntity.displayAspectRatio()` を単純に全用途で変えるのではなく、単一カード表示用のratio/contentScale判定を明確にする。
- width/height欠損時の既存fallbackを壊さない。
- MediaGrid本体のthumbnail cellの比率・morph・column layoutへこの上限を流用しない。

## テスト

- 9:16等の縦長1枚はカード枠3:4 + Crop。
- 3:4はそのまま。
- 1:1、16:9等は既存表示を維持。
- 2枚以上は正方形gridのまま。
- 未分類・分類済み・previewが同じsingle-image規則。
- viewer側に3:4制限が入っていない。
