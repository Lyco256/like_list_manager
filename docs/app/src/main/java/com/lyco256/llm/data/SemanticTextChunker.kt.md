# `SemanticTextChunker.kt`

semantic同期で使うsource種別、chunk化、fingerprint、document IDを一箇所に固定します。

- 対象sourceは`ClipEntity.text`、`summary`、`ocrText`だけで、順序はtext → summary → OCRです。
- chunkはUnicode code point基準で最大384、隣接chunkのoverlap 48です。元文字列の連続substringを切り出し、Unicode whitespaceをtrimしてから空chunkを除外します。ordinalは保存時に0から再付番し、surrogate pairを分断しません。
- fingerprintはsource type、UTF-8 source text、EmbeddingGemma revision、document prompt revision、chunk rule revisionを長さ付きSHA-256入力へ含めます。
- document IDは`semantic:clip:<clipId>:<sourceType>:<ordinal>`で、source再計算後も決定論的です。

`SemanticTextChunkerTest`はemojiを含むcode point境界、overlap、末尾到達、blank source、fingerprintとIDの決定性を確認します。
