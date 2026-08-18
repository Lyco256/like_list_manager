# 26. MediaGridツイートpreviewの外枠UI変更

## 目的

MediaGridから開くツイートpreviewを、カード本体に余計なheaderを持たない軽いoverlayへ変更する。

## UI仕様

- 現在カード内上部にあるタイトル `ツイート` を削除する。
- 閉じるボタンはカード内headerから外し、カード外の右上へ配置する。
- 閉じるボタンは十分なtouch targetを持ち、カードとは別layerとして見える。
- scrim/画面外をタップするとpreviewを閉じる。
- カード本体をタップしても外側dismissへ伝播しない。
- Android Backでも従来どおり閉じる。
- Loading / NotFound / Loadedの各状態で同じ外側close規則を使う。
- 22/23で追加したタグdraft/Applyを含むカード内容はそのまま動く。

## 実装上の注意

- `DialogProperties(dismissOnClickOutside=false)` の現状を改める。
- 全画面Box + card + 外側close buttonのように、閉じるボタンをカードbounds外へ安全に置けるlayoutにする。
- close buttonをnegative offsetだけで見切れさせない。
- 外側tap検出とcard内gesture、画像viewerへのtapを競合させない。

## テスト

- `ツイート` titleが存在しない。
- close buttonで閉じる。
- card外tapで閉じる。
- card内tapでは閉じない。
- Backで閉じる。
- close buttonがcard外の右上にあることをboundsで確認する。
- 画像viewerやタグchip操作が外側dismissへ誤伝播しない。
