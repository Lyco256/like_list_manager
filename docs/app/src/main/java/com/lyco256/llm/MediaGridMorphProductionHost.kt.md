# MediaGridMorphProductionHost.kt

## 2026-08-02 Morph handoff unlock optimization

- The production effect observes `LazyGridState.layoutInfo` and passes one immutable layout sample to the coordinator. `ScrollToItem`/`ScrollBy` handlers only issue the scroll operation; they do not wait for a frame or call the layout observer directly.
- Target geometry includes `finalCorrection`, target row ordinals/header/cell size/row top, and focal position. An aligned layout enters reveal immediately; completion waits for the unified surface's generation/mode actual-draw ACK.
- ACK handling completes target or rollback-current reveal, unlocks input, releases protected assets, and leaves a separate one-shot checkpoint effect after unlock. `Completed` is terminal and is not converted to stale cancellation when the controller clears its request.

## 2026-08-01 Phase 2 production integration

- `MediaGridMorphProductionHandoffEffects` is the production handoff coordinator for the existing `LazyVerticalGrid`; it has no visual layer and drives scroll, verification, reveal, rollback, checkpoint suppression, and retained-image ownership.
- The old production visual host/API was removed from the production path. `MediaGridMorphTestHandoffHost` is a `BuildConfig.TEST_HARNESS`-only compatibility host; production uses the unified resident/Morph surface plus the event-driven effects.

## 2026-07-31 UI・handoff correction

- Production handoff effects observe the latest LazyGrid layout after every target scroll command and complete only after target row/geometry verification and the reveal acknowledgement. The TEST_HARNESS compatibility host is not part of production rendering.
- Active plan assets are protected through the handoff; stale commands and lifecycle cancellation remain generation-scoped.

`ClassifiedMediaGridContent`からだけ明示的に作成されるproduction Morph host。

- `MediaGridMorphProductionHostState`はLazyGridState・session単位でinteraction controller、handoff coordinator、request/command channel、Morph owner tokenを一つずつ保持する。
- `rememberMediaGridMorphProductionHostState`は非選択・初期Progress非表示時だけhostを生成し、無効化またはdispose時にcontrollerとretained protectionを解放する。
- `MediaGridMorphProductionHost`は既存の一枚のLazyVerticalGridの上にProductionVisible Canvasだけを重ねる。target frameとvisible geometryをイベント駆動で監視し、geometry一致→frame境界→次frameの順でCompleteする。
- active Morphではgrid scroll、cell input、toolbar/filter/sort/display操作を抑止し、like-count/video metadata overlayを隠す。完了・cancel・rollback後に同じUIを復帰させる。
- command runnerはChange/Rollback、ScrollToItem、最大3回のScrollBy、Complete/Cancelをgeneration付きで処理する。stale commandは破棄する。
- Morph中はuser scrollとcheckpointを抑止し、complete・rollback・current側settle・lifecycle cancel後に確定位置を一回だけcheckpointする。
- active planのstart/end assetだけを独立owner protectionへboundedに登録する。LRU、resident上限、decode、scheduler、publication経路は変更しない。
- `ON_STOP`、source/frame identityの変更、Composable disposeではactive Morphをcancelまたはrollbackし、stale Canvasを残さない。
