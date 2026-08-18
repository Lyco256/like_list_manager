# 16. 共通Undo通知UIと5秒ライフサイクル

## 目的

共通Undo slotを、画面下部のタッチ可能通知としてユーザーへ提示し、5秒以内の「キャンセル」で元へ戻せるようにする。

## 表示

- `LikeListManagerUi` の画面全体に共通する下部overlayとして1つだけ表示する。タブ切替で消えない。
- DB操作が成功しUndo slotが作られたら、そのslotのmessageを表示する。
- 通知内に `キャンセル` ボタンを置く。このボタンは操作の取り消し=Undoを実行する。
- 通知は5秒間表示する。
- 横方向スワイプ、または下方向スワイプで即dismissできる。上方向スワイプはdismiss条件にしない。
- 新しいUndo対象操作が成功すると、表示中の旧通知を待たず新slotの通知へ即置換する。
- move/reorderによるinvalidateでは旧通知を即消し、新通知は出さない。
- Snackbar等を利用してもよいが、指定した横/下Swipeと永続slot連動を満たす共通hostとして実装する。

## 5秒の意味

- 5秒は、そのslotの通知がforegroundで実際に提示されている時間として扱う。
- timeout、手動Swipe dismissのどちらでも、現在slotをfinalizeして永続Undo rowを削除する。
- Activity/プロセス終了だけではslotを削除しない。
- 次回アプリ起動時、永続slotが残っていれば同じ通知を改めて表示し、新しい5秒の復元機会を与える。
- Activity再作成でもslotが残っている限り復元機会を失わない。

## Undoボタン

- 押下時に現在slotの逆操作を実行する。
- 成功したら通知・slotを消す。
- 失敗したら復元情報を破棄せず、ユーザーへエラーを示し、Undo可能状態を保持する。
- ボタン連打で二重Undoしない。実行中は同じslotの二重処理を防ぐ。

## 競合

slot identityを確認してtimeout/dismissする。旧通知のtimerが遅れて発火して、新しいslotを消してはいけない。

## テスト

Compose/Repository統合で最低限次を固定する。

- 操作成功後に下部通知と `キャンセル` が出る。
- キャンセルでUndoが1回だけ実行される。
- 5秒timeoutでslotが消える。
- 横Swipeと下Swipeで消え、slotも消える。
- 新操作で表示内容が即置換され、旧timerが新slotを消さない。
- move/reorder invalidateで通知が消える。
- Activity再作成/DB reopen後のpending slotが再表示される。
- Undo失敗ではslotを保持する。
