# `MediaGridRgb565Coil.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/MediaGridRgb565Coil.kt`

## 第14実装

`MediaGridRgb565Slot`専用のCoil KeyerとFetcherを提供します。Fetcherはencoded sourceやDecoderを使わず、pack payloadから256×256 `RGB_565` Bitmapを作って`DrawableResult`を返します。

raw fetchは共有Semaphoreで最大4件です。permit待ち以外のDelayやtickは持ちません。request cancel・例外時は生成途中Bitmapをrecycleし、正常返却後はCoilのmemory cacheへ所有権を渡します。disk cacheはrequest側で無効にします。

cache keyはformat version、asset ID、pack/slot、generation、source kind・length・lastModifiedを含み、bank更新後は旧memory entryと衝突しません。

