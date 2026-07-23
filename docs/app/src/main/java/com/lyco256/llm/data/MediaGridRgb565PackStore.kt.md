# `MediaGridRgb565PackStore.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/MediaGridRgb565PackStore.kt`

## 第14実装

256×256 `RGB_565` previewを、asset IDだけで決まる128slot固定packへ保存します。`assetId - 1`からpack indexとslot indexを計算し、DBへpath・slot・version・生成状態を追加しません。

packは4KiB checksum付きheaderと128slotで構成し、各slotは固定metadata＋131,072 byte payloadのbank A/Bを持ちます。publishは非complete metadata、payload、payload force、generation付きcomplete metadata、metadata forceの順で行い、旧有効bankを先に変更しません。readerはchecksum・asset ID・source signatureを検証し、有効bankの最大generationを選びます。

read-only mappingは同一filesDir内で共有するaccess-order LRUに最大4packだけ保持します。同一packのread/write lockを共有し、write後は対象mappingだけを無効化します。payload CRC32はpublish、repair、backfill検証で使い、通常のhot readでは再計算しません。

中央crop生成はfilterとditherを有効にした`RGB_565` Bitmapへ1回描画し、`copyPixelsToBuffer()`で固定長payloadを取得します。readはslot payloadだけにposition/limitを固定し、`copyPixelsFromBuffer()`で`RGB_565` Bitmapを直接生成します。

