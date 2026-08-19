# 20. 投稿者の全保存ツイート件数表示

## 目的

各ツイートカードで、投稿者display nameの右隣に、その投稿者から現在ローカル保存しているツイート総数を `35件` の形式で表示する。

## 集計仕様

- 母集団はDBに現在保存されている全ツイート。
- 未分類/分類済み、現在filter、MediaGrid previewに依存せず同じ投稿者なら同じ件数。
- 01で `isDeleted` を廃止した後の全保存投稿を数える。
- display nameだけでgroup化せず、既存 `authorKey` と同じauthor identity規則を使う。
- 別階層ではなく、投稿者名と同じ1行の直右に表示する。
- 日本語の桁区切りを使い、`1,100件` のように表示できる。

## 実装

- 既存 `MainUiState.authorOptions` / `buildAuthorOptions(clips)` が全clipsから持つ件数を再利用する。
- cardごとに全clipsをgroupByし直さない。上位でauthor identity→count mapを1回作り、通常カードとMediaGrid previewへ渡す。
- tweet削除、Undo、同期で全保存件数が変わればFlow更新で自動追従する。

## テスト

- 同一投稿者が全体5件、filter結果2件でも表示は `5件`。
- 未分類、分類済み、MediaGrid previewで同値。
- 同名display name・異なるauthor identityを混同しない。
- 1,100件の桁区切り。
- tweet削除で1減り、Undoで元へ戻る。
