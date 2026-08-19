# 35. 横断回帰テスト補完

## 目的

01〜34で各作業に追加した局所テストだけでは抜ける、複数機能を跨ぐ実用上重要な組合せをテストで固定する。ここでは新機能を追加せず、実装済み仕様の横断テストだけを追加・整理する。

## 重点シナリオ

既存テストを確認し、重複しない形で最低限以下を自動化する。

### Undoの境界

- summary保存→内部likeCount更新→Undoでsummaryだけ戻り、likeCountは新値を維持。
- tag Apply→内部同期状態更新→Undo slotが残る。
- Undo対象操作A→Undo対象操作BでAが完全に失効し、BだけUndo可能。
- Undo対象操作→move/reorderでslotが消え、新通知は作られない。
- Activity/DB再openを挟んだpending Undoが復元可能。
- 古い通知timer/dismiss callbackが新slotを削除しない。

### 削除とデータ保護

- tweet削除後、DBでは即消えるが再open後Undoでclip/assets/clip_tags/fileが戻る。
- delete staging失敗では元DB・元画像を維持する。
- delete Undo待ち中に保存先を変更しても、現在保存先へ復元できる。
- tag削除Undoで全relationが戻り、他tag relationを変えない。

### UIの組合せ

- 未分類初回loading中は中央Progressのみで、0件/title workload Progressを重ねない。
- 全体投稿者件数はfilter結果件数に変わらない。
- classified cardのtag Applyでfilter対象外になった時は一覧から消える。
- MediaGrid previewの同操作ではdialogが自動closeしない。
- filter Treeと選択済みRowを往復して状態/巡回規則が崩れない。
- 多数tag条件、一括追加Tree、tag管理深階層でscroll可能。

## テスト配置

- Repository/Room整合性は既存integration test群へ寄せる。
- Compose操作は `MainActivityComposeTest` / `UiStateRenderingTest` / `SearchFilterDatabaseIntegrationTest` 等、現在の責務に最も近いtestへ入れる。
- 既存の実機隔離test applicationId/DBを使い、本番DBや本番画像を直接変更するtestを作らない。
- 同じシナリオをunit/Compose/integrationで無意味に三重化せず、壊れ方を最も確実に検出できる層へ置く。
