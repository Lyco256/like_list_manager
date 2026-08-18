# 21. ツイート自身のいいね数へ♡を付ける

## 目的

20の投稿者保存件数のさらに右へ、ツイート自身のXいいね数を `♡1,100` の形式で表示し、2種類の件数を見分けられるようにする。

## 表示仕様

author行は最終的に次の並びにする。

`投稿者名  35件  ♡1,100`

- `likeCount != null` の時だけ `♡` と数値を表示する。
- `likeCount == null` ならheart自体もplaceholderも出さない。
- 既存 `formatLikeCount` の `1,100` / `1万` 等のformatを維持し、その直前へ `♡` を付ける。
- 現行のlike詳細popup、一時値warning、取得日時/エラー表示を維持する。
- `♡数値` 領域をtapすれば従来どおりpopupを開ける。
- 未分類、分類済み、MediaGrid previewで同じ表示にする。

## テスト

- `likeCount=1100` → `♡1,100`。
- `likeCount=10000` → 既存formatを使ったheart付き表記。
- null → `♡` も数値も表示なし。
- 20の投稿者件数との順序が `name → 件数 → like`。
- like popupが引き続き開き、一時値warningも維持。
- 3種類のカードで同じ。
