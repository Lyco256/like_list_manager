# `LexicalDocumentBuilder.kt`

正本`ClipEntity`から派生Lexical documentとclip単位fingerprintを作る純粋な境界です。

- source typeは`text`、`summary`、`ocr`、`author_name`、`username`のenumで一元管理します。
- blank fieldはdocument化せず、非blank fieldだけを`LexicalTextAnalyzer`へ1回渡します。
- 各sourceは`sourceOrdinal = 0`の1 documentで、document IDは`clip:<clipId>:<sourceType>:0`の決定論的形式です。
- Sudachi解析結果の`normalizedText`、`readingText`、`romanizedText`、`compactText`を再加工せず`LexicalDocument`へ移します。
- fingerprintは固定revisionと5 fieldの名前・UTF-8 byte length・byte列だけをSHA-256へ順番に渡して作ります。like count、日時、タグ、画像は含めません。

`LexicalDocumentBuilderTest`はsource対応、blank除外、決定論的ID、解析4出力の格納、検索対象／対象外fieldによるfingerprint差分を確認します。検索精度や順位は扱いません。
