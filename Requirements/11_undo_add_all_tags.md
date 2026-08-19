# 11. 「別タグへ一括追加」をUndo対応

## 目的

あるタグの全ツイートへ別タグを追加する操作を、実際に追加されたrelationだけまとめてUndoできるようにする。

## 実装

- source tagに属するclip集合を取得する。
- target tagが既に付いているclipを除外し、今回新規追加されるclip-tag relationだけを確定する。
- 追加するrelationと、その `createdAt` を1つのUndo payloadとして保存する。
- 本操作はtarget tagを追加するだけでsource tagを残す既存仕様を維持する。
- Undoでは今回追加したtarget relationだけを削除する。
- 操作前からtarget tagが付いていたclipのrelationは絶対に削除しない。
- 対象0件の場合は不要なUndo slotを作らない。
- 大量件数でもclipごとの不要なreadを増やさない。

## テスト

- source 10件のうちtarget既存3件なら7件だけ追加・Undoで7件だけ削除。
- source relationは全件残る。
- target既存relationのcreatedAtも状態も不変。
- 0件差分ではUndoなし。
- 大きめfixtureで件数とrelation一意性が保たれる。
