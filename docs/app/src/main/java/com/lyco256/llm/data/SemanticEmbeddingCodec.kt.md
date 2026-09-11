# `SemanticEmbeddingCodec.kt`

semantic embedding BLOBの唯一のcodecです。

- dimensionはEmbeddingGemmaの768に固定し、保存サイズは`768 * 4 = 3072` bytesです。
- Float32をlittle-endianで連続配置します。
- encode時はdimensionとfinite値、decode時はBLOBの完全な長さとfinite値を検証します。

`SemanticEmbeddingCodecTest`はraw Float bit patternのroundtrip、次元不一致、NaN／Infinity、短長BLOBを確認します。semanticの精度・cosine・順位はテストしません。
