# `ImageEmbeddingBlobCodec.kt`

Japanese CLIP image embeddingの派生DB境界codecです。256個のfinite Float32だけを受け付け、little-endian 1024-byte BLOBへ変換します。読み出し時も長さとfinite値を厳密に検証し、不正BLOBを正常データとして扱いません。既存の768次元semantic codecは変更しません。
