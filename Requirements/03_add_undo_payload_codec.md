# 03. Undo payload modelとcodec

## 目的

後続のUndo操作が、変更前情報を型安全かつversion付きで永続slotへ保存・再読込できる共通payload形式を作る。この作業ではUndoの実行制御やUIはまだ作らない。

## 実装

- `undo_entries.payload` に保存するversioned envelopeとcodecを専用sourceへ切り出す。
- envelopeには少なくともpayload schema versionとaction typeを持たせる。
- 後続作業で次の情報を表現できるpayload modelを定義する。
  - clip-tag relationの追加分・削除分
  - 作成したtag/groupのID
  - 変更前のtag/group
  - 削除前tagとclip_tag群
  - 削除前group
  - 変更前summary
  - 変更前OCR text / `ocrUpdatedAt`
  - 削除前clip / assets / clip_tags / 復元用file metadata
- Entity全体を保存する必要がある削除系では、復元に必要なfieldを欠かさない。
- field変更系では、その操作が変更しないfieldまでsnapshotして後で上書きする設計にしない。
- JSONを使い、画像バイナリをpayloadへ直接埋め込まない。
- 既存dependencyで十分なら新規serialization dependencyを増やさない。追加が必要な場合でもpayload codecのためだけに最小限とする。
- 不明action type、不明schema version、壊れたpayloadを「空payload」として成功扱いしない。decode errorとして検出できるようにする。
- 同じpayloadをencode→decodeして意味とID/timestampが変わらない。

## テスト

- 各payload typeのround-trip。
- nullを含むfield、空文字、Unicode、複数relation、Long IDを保持。
- unknown version/typeを拒否。
- malformed JSONを拒否。
- field-level payloadに無関係なClipEntity fieldが含まれていないことを型/テストで確認。
