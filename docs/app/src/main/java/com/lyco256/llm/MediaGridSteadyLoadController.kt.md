# `MediaGridSteadyLoadController.kt`

## 第14実装

初回Progressのraw/JPEG requestは既存上限4、通常controllerは既存上限2です。rawもReady memory entryとして列数変更・画面復帰・background frame refresh時に保持します。raw/JPEG失敗はasset単位のrepair callbackへ渡して次候補へ進みます。2.5秒Progress条件、50ms UI反映loop、scroll/session保持は変更しません。

分類済みメディアグリッドのsessionに所有され、画面Composableより長く存続するcontrollerです。画面離脱ではdisposeせず、pause/resumeだけを行います。

初期表示では `PreparingFrame`、`PreparingInitialWindow`、`WarmingInitialWindow`、`Ready` の順で進みます。現在viewport、下方向1画面、下方向2画面を優先し、必要な場合だけ上方向1行を含め、最大128件かつ256×256 ARGB換算32MiBまでを対象にします。prepared metadataとstartup requestは最大4件並列、全件terminalまたは2.5秒でグリッドを公開し、未完了要求は通常loopへ引き継ぎます。

Ready後は50ms周期の単一loopだけが最新viewport anchorを参照します。1tickの上限はmetadata 2件、要求開始1件、completion反映4件で、進行中要求は最大2件です。viewport監視はconflatedな最新値の上書きだけを行い、速度・方向・drag状態は扱いません。

active bitmap windowは表示中と前後1行です。範囲外の未完了要求はcancelし、UI用load stateも破棄します。戻り表示ではframe内metadataからPendingを再作成し、memory cacheが残っていればReady、missなら固定loopで再読み込みします。セルはPending/Loading中にPlaceholderを描画し、controllerがmemory cacheへの格納を確認したReady候補だけを同一request data/cache keyで表示します。controllerはBitmap、Drawable、Imageを保持しません。

通常表示後は50ms周期・同時request最大2件を維持します。列数変更やsource refreshではcontrollerを再生成せずframeを更新し、asset単位のload stateを引き継ぎます。persistent previewがmemory cacheでReadyの場合はReadyを維持し、それ以外だけPendingへ戻します。preview通知では該当assetだけをPendingへ戻します。永続JPEGの失敗は既存recovery gateを通じてRepositoryへ通知します。
## 2026-07-24 第16実装: decoupled load and UI publication pipelines

## 2026-07-24 viewport hot path改善

- `updateViewport()`はrender keyとanchor内容の比較、最新anchorのCAS上書き、epoch増加、conflated signal通知だけを行う。lock、queue走査、active window生成、request開始、event/UI公開は行わない。
- 専用anchor consumerが最新epochだけを読み、lock外で`MediaGridActiveWindowSnapshot`をepochごとに一度生成する。snapshotはvisible順序、visible＋前後1行のactive順序、O(1) membership、frame generation、render keyを保持する。
- urgent判定・visible task登録・UI publicationは同じsnapshotを参照し、viewportやframe全体を再走査しない。anchor更新だけではCompose stateを更新せず、snapshot公開後にworkerをwakeする。
- assetごとのqueue recordがmetadata/Bitmapの状態、token、generation、source identity、candidate indexを保持する。重複登録はrecordで抑止し、古いqueue entryはtoken不一致でO(1) skipする。urgent昇格はactive snapshotのassetだけを確認し、background queueを走査・削除しない。
- RGB_565 pack候補は`width * height * 2`、その他候補は`width * height * 4`をLongで見積もる。worker並列上限、background選択方式、候補順、画面外処理、UI batch、Progress、session保持は変更しない。
- `MediaGridSteadyLoadControllerTest`でsnapshot順序・membership・容量見積もりを確認し、既存のCompose/隔離実機テストで高速viewport、画面外完了、cache再表示、fallback、Progress、列数変更、画面復帰、scroll保持を回帰確認する。

- `MediaGridSteadyLoadController`は、metadata queue/worker、Bitmap queue/worker、UI publication consumerを別consumerとして所有する。event producerはCompose stateを直接変更しない。
- throughput用の50ms tick、固定delay、sleep、通常loadの周期pollingは使用しない。queueが空のworkerだけがChannel receiveでsuspendし、permitが空くと次taskを開始する。
- metadataとBitmapは総数4、background最大2、urgent予約最大2。urgent不在時だけbackgroundが予約枠を借りられ、開始済みtaskはanchor変更でcancelしない。
- viewportは最新anchorとpriority epochを更新し、表示/前後1行の未開始taskをurgentとして選ぶ。backgroundではRGB565/JPEG/localのみを開始し、URL候補は表示対象へ入った時に進める。
- frame全体のmetadata queueを空きworkerで継続し、画面非表示中はmetadata最大2、Bitmap background最大1、UI publicationはpauseする。session coordinatorのframe・controller・scroll保持は変更しない。
- Coil memory cacheの75%をbackground preload high watermark、65%未満を再開signalとする。Bitmap本体をcontrollerへ保持せず、cache missはPreparedへ戻す。
- 初回Progressの2.5秒表示上限、RGB565 pack、JPEG fallback、DB、元画像、列数変更、Macrobenchmarkは変更しない。
