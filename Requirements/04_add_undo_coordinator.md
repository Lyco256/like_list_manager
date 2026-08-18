# 04. 共通Undoコーディネータ

## 目的

永続Undo slotとpayload codecを使い、「直前の明示的ユーザー編集1回だけ」を共通ルールで管理する制御層を作る。この作業では個別の編集操作へまだ配線しない。

## 共通ルール

- Undo対象操作はDBへ即反映する。
- 変更前の必要情報だけを永続slotへ保存する。
- slotは常に1件。
- 新しい明示的ユーザー編集を開始した時点で旧Undoをinvalidateする。新操作が失敗しても旧Undoは復活させない。
- X同期、likeCount更新、API使用量/同期状態、設定読込、media preview生成等の内部/background更新はslotをinvalidateしない。
- Undoは対象field/relationだけを逆操作し、Entity丸ごとの古いsnapshotで現在値を上書きしない。
- Activity/プロセス終了だけではslotを削除しない。

## 実装

専用coordinatorを作り、最低限以下を1か所に集約する。

- pending UndoのFlow公開
- 現在slot取得
- 新slot保存/置換
- 明示的ユーザー編集開始時のinvalidate
- Undo実行dispatch
- dismiss/finalize
- Undo成功後のslot削除
- Undo失敗時のslot保持
- file cleanup等を必要とするactionのfinalize hook

DBだけで完結するUndo対象では、「Undo row保存」と「本操作」を同じRoom transactionに入れられるhelperを用意する。途中失敗で本操作だけ、またはUndo rowだけが確定する状態を作らない。

前slotをinvalidateしてから新操作が失敗した場合、前slotを再作成しない。

file cleanupのfinalizeはidempotentに呼べる契約にし、記録していないfileへ作用させない。

## エラー

- payload decode不能では勝手にslotを捨てずエラーを返す。
- 逆操作失敗ではslotを保持する。
- finalizeのcleanup失敗が無関係data破壊へ波及しない。

## テスト

- slot save→observe→undo→clear。
- 新slotで旧slotが1件だけに置換。
- invalidateでslot消滅。
- 内部更新経路ではslot不変。
- 新編集失敗後も旧slotは復活しない。
- unknown/malformed payloadで誤復元せずslot保持。
- Undo失敗でslot保持。
- Room transaction失敗で「操作だけ」「slotだけ」が残らない。
