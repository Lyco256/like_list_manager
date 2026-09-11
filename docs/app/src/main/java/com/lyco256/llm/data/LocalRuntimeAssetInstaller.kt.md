# `LocalRuntimeAssetInstaller.kt`

EmbeddingGemmaとJapanese CLIPが共有する内部asset installerです。generated `metadata.json`のrepository・revision・file set・SHA-256・byte sizeを検証し、専用directory内の`.partial`へstreaming copy、`fd.sync()`、検証後のatomic replace、破損assetの部分復旧を共通化します。runtimeごとのsource・asset spec・出力wrapperは各embedder側に残し、既存EmbeddingGemmaのAPIと挙動は変更しません。
