# `UndoPayloadCodec.kt`

`app/src/main/java/com/lyco256/llm/data/UndoPayloadCodec.kt`

共通Undo slotへ保存するpayloadの型とJSON codecを定義します。現行 `schemaVersion` は1で、action typeは単一/一括タグ差分、タグ/グループの作成・field edit・削除、概要、OCR、投稿削除です。

payloadは逆操作に必要な値だけを持ちます。relationは元の `createdAt`、field editは変更したfieldの前値だけを保持し、無関係な後続更新を巻き戻しません。投稿削除payloadはclip/assets/relationsと、画像stagingの相対path・size・SHA-256 metadataを持ち、bytes自体はJSONへ入れません。

decodeはJSON後続文字、型違い、必須/nullの違い、整数範囲、未知schema version/action typeを区別して拒否し、不正payloadを逆操作しません。

