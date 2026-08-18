# `UndoPayloadCodecTest.kt`

`app/src/test/java/com/lyco256/llm/data/UndoPayloadCodecTest.kt`

全payload typeの値を保ったround trip、nullable/空文字、未知version/action、不正JSON・不正構造の拒否、field-level payloadが復元対象以外を持たないことを検証します。

