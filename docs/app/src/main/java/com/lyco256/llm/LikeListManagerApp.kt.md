# `LikeListManagerApp.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/LikeListManagerApp.kt`

## 役割

アプリプロセス起動時に `AppContainer` を1つ生成し、ActivityとViewModelからRepositoryへ到達できるようにします。

## 主要定義

- `LikeListManagerApp : Application`
- `container`: アプリ全体で共有する依存関係コンテナ
- `onCreate`: `AppContainer(this)` を初期化し、production variantだけ`LexicalIndexSynchronizer`をApplication所有のIO scopeで非同期開始する。TEST_HARNESSでは暗黙起動しない
- `onTrimMemory`: critical memory pressure時に現在のPaddleOCR engineを解放

## 関連ファイル

- `../../../../AndroidManifest.xml.md`: `android:name` でこのApplicationを指定します。
- `data/AppContainer.kt.md`: 生成する依存関係を定義します。
- `MainActivity.kt.md`: ViewModelがApplication経由でcontainerを取得します。

## 変更時の確認

起動時に辞書展開・辞書ロード・既存clip解析を同期実行しないでください。Lexical同期はApplicationのバックグラウンドscopeで開始し、TEST_HARNESSの専用integration testからだけ明示起動します。
