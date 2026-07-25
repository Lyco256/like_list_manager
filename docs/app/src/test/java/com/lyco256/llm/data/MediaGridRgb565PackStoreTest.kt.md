# `MediaGridRgb565PackStoreTest.kt`

The deterministic source contract asserts that `MEDIA_GRID_RGB565_MAX_FETCHES` remains 4.

固定address・pack定数、二重bank、途中失敗時の旧bank保持、片bank破損fallback、generation、source stale、metadata checksum、path境界、4pack mapping LRU、pack write直列化、候補順、buffer copy/dither/Delay禁止をローカルUnit Testで固定します。

