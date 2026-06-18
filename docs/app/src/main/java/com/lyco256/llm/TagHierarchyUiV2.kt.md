# `TagHierarchyUiV2.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt`

## 役割

未分類、分類、タグ管理のCompose UIを分離した画面実装です。`MainActivity.kt`から受け取った状態とRepository操作を使い、タグ階層の選択、絞り込み、タグリストのドラッグ&ドロップを描画します。

## 主要な定義、設定、処理

- `EnhancedClipListScreen`: 未分類投稿のカード一覧と、分類確定までの一時タグ選択、カード右下の分類ボタンを扱います。
- `EnhancedClassifiedScreen`: 検索欄、絞り込みサマリー、全画面Dialogの絞り込みパネル、分類済み投稿の再割り当てを扱います。
- `EnhancedTagListScreen`: タグ/グループの追加、名称変更、移動、削除、別タグへの一括追加、ドラッグ&ドロップ移動を扱います。
- `EnhancedTweetCard`: 投稿本文、画像、概要、タグ選択をまとめます。
- `TagHierarchySelector` / `TagSelectionDialog`: 投稿カード内のタグ選択を、コンパクトな最上位チップと半画面Dialogの単一階層ナビゲーションで扱います。
- `TagFilterSummaryRow` / `TagFilterDialog`: 分類画面の絞り込み条件を、横スクロール要素と全画面Dialogで扱います。
- `TagManagementRow`: タグリストの行表示、グループの展開、操作メニュー、ドラッグ開始を扱います。
- `TagListItem` / `DragState`: ドラッグ中の表示リストを通常行とplaceholderへ分け、掴んだnodeと表示中子孫をLazyColumn本体から除外します。placeholderのindexは「drag中nodeを除外した移動先兄弟リスト上の挿入位置」です。
- `TagManagementRow` のdrag placeholder表示 / `TagDragPreview`: 挿入候補位置に同じ高さのplaceholderを表示し、overlayは縦方向だけ指に追従します。overlayの横位置と横幅はドラッグ開始時の行位置に固定します。
- ドラッグgestureは個別行ではなくタグリストを包む親Boxの `pointerInput` で受けます。長押し開始時にroot座標で行をhit-testし、LazyColumnのplaceholder移動や再composeでgesture coroutineが破棄されないようにします。
- placeholder移動は前回drag中心と今回drag中心が隣接行の中心線を跨いだ場合だけ行い、範囲外では現在親の先頭または末尾slotを候補として維持します。
- グループ内dropと前後slot判定も指位置ではなくdrag中オブジェクトの中心Yを基準にします。これにより、overlayの見た目と保存されるdrop先のズレを抑えます。
- placeholderは背景色や角丸を持たない完全な余白として描画します。グループ内drop候補中も直前の並び替え候補slotに余白を残し、対象グループ行のハイライトで保存先がグループ内になることを示します。
- `DragState` は保存先の `targetParentId` / `placeholderIndex` と、表示用余白の `visualParentId` / `visualPlaceholderIndex` を分けて持ちます。グループ内dropへ入っても表示用余白を消さず、前後の中心線を越えたときだけ表示用余白を移動します。
- drop直後はDB Flowの反映まで最後のplaceholder配置を短時間維持し、変更前順序へ一瞬戻って見える揺れを抑えます。
- auto-scroll loopからの静止中心線判定は、実際にスクロール量が消費された場合だけ許可します。上端・下端へ到達済みの状態で、中心線判定が連続して進みすぎないようにします。
- auto-scroll量の計算では、LazyColumnが該当方向へスクロール可能かを見ます。
- LazyColumn itemには `Modifier.animateItem()` を付け、placeholder移動時にドラッグ中でない行が急に瞬間移動して見えないようにします。
- `EnhancedMediaGrid`: 投稿内画像のグリッド表示を再利用します。
- `TagNodeRef.saveableKey`: `LazyRow`、`LazyColumn`、`LazyVerticalGrid`のkeyをBundle保存可能な文字列へ変換し、実機でのCompose保存状態エラーを防ぎます。
- ドラッグ中はリスト範囲外へ出ても状態を維持し、auto-scroll loopで端方向へ継続スクロールします。スクロール中もplaceholder位置を更新します。
- グループ中央領域へ乗っている場合だけグループ内drop候補にし、グループ自身や子孫へのdropは保存しません。ドラッグ中にグループを自動展開しません。

## 関連ファイル

- `MainActivity.kt.md`: 画面の状態とRepository呼び出しを渡す入口です。
- `data/ClipRepository.kt.md`: タグ移動、並び替え、タグ再割り当ての業務処理を実行します。
- `data/Entities.kt.md`: タグ階層やフィルター状態のモデルを定義します。

## 変更時の確認事項

タグ階層UIを変えるときは、投稿カードの一時状態、分類画面の即時保存、タグリストの移動制約、Repositoryのslot移動APIと合わせて確認します。ドラッグ変更では範囲外drag、auto-scroll、placeholder、グループ内drop、drop後の順序維持を確認します。
