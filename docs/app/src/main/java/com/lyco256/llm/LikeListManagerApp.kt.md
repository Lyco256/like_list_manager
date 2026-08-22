# `LikeListManagerApp.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/LikeListManagerApp.kt`

## 役割

アプリプロセス起動時に `AppContainer` を1つ生成し、ActivityとViewModelからRepositoryへ到達できるようにします。

## 主要定義

- `LikeListManagerApp : Application`
- `container`: アプリ全体で共有する依存関係コンテナ
- `onCreate`: `AppContainer(this)` を初期化

## 関連ファイル

- `../../../../AndroidManifest.xml.md`: `android:name` でこのApplicationを指定します。
- `data/AppContainer.kt.md`: 生成する依存関係を定義します。
- `MainActivity.kt.md`: ViewModelがApplication経由でcontainerを取得します。

## 変更時の確認

起動時に重い処理を直接追加しないでください。DBや暗号化ストレージのI/OはRepository側のIO dispatcherで行います。

## 2026-08 PaddleOCR lifecycle

PaddleOCRは初回利用時にgateway側で遅延初期化します。Applicationはプロセス全体のrelease用CoroutineScopeを持ち、`TRIM_MEMORY_COMPLETE`でUI threadを塞がずに共有PaddleOCR engineを解放します。
