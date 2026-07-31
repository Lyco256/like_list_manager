# MediaGridMorphProductionHost.kt

`ClassifiedMediaGridContent`からだけ明示的に作成されるproduction Morph host。

- `MediaGridMorphProductionHostState`はLazyGridState・session単位でinteraction controller、handoff coordinator、request/command channel、Morph owner tokenを一つずつ保持する。
- `rememberMediaGridMorphProductionHostState`は非選択・初期Progress非表示時だけhostを生成し、無効化またはdispose時にcontrollerとretained protectionを解放する。
- `MediaGridMorphProductionHost`は既存の一枚のLazyVerticalGridの上にProductionVisible Canvasだけを重ねる。target frameとvisible geometryをイベント駆動で監視し、geometry一致→frame境界→次frameの順でCompleteする。
- active Morphではgrid scroll、cell input、toolbar/filter/sort/display操作を抑止し、like-count/video metadata overlayを隠す。完了・cancel・rollback後に同じUIを復帰させる。
- command runnerはChange/Rollback、ScrollToItem、最大3回のScrollBy、Complete/Cancelをgeneration付きで処理する。stale commandは破棄する。
- Morph中はuser scrollとcheckpointを抑止し、complete・rollback・current側settle・lifecycle cancel後に確定位置を一回だけcheckpointする。
- active planのstart/end assetだけを独立owner protectionへboundedに登録する。LRU、resident上限、decode、scheduler、publication経路は変更しない。
- `ON_STOP`、source/frame identityの変更、Composable disposeではactive Morphをcancelまたはrollbackし、stale Canvasを残さない。
