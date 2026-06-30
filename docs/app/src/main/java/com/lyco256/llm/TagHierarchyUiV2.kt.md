# `TagHierarchyUiV2.kt`

投稿一覧のLazyColumnには、未構築項目へ実機UIテストから安全にスクロールできる `clip_list` test tagがあります。

絞り込みbutton、Dialog、タグのみswitch、検索欄、適用buttonには安定したtest tagを付け、複合条件と破棄動作をスクリーンショットなしで検証できます。

## 対応ソース

`app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt`

## 役割

未分類、分類済み、タグ管理のCompose UIを分離した画面実装です。`MainActivity.kt`から受け取った状態とRepository操作を使い、タグ階層の選択、絞り込み、タグリストのドラッグ&ドロップを描画します。

## 主要な定義、設定、処理

- `EnhancedClipListScreen`: 未分類投稿のカード一覧と、分類確定までの一時タグ選択、カード右下の分類ボタンを扱います。
- `EnhancedClassifiedScreen`: 一致件数と条件文、右側固定の絞り込み/クリア操作、全画面Dialogの検索/絞り込みパネル、投稿の再割り当てを扱います。タグのみOFFでは未分類投稿も表示対象に含めます。
- `EnhancedTagListScreen`: タグ/グループの追加、名称変更、移動、削除、別タグへの一括追加、ドラッグ&ドロップ移動を扱います。
- `EnhancedTweetCard`: 投稿本文、画像、概要、タグ選択をまとめます。
- `SearchFilterDialog`: タグのみ、文字列検索、期間、ユーザー、タグ条件を区分し、確定条件と分離した下書きとリアルタイム一致件数を扱います。期間DatePickerは未指定時に今日を初期選択し、日付クリア操作は開始・終了指定の次行へ固定します。画面下部には適用/キャンセル、変更破棄・全条件クリアの確認Dialogを持ちます。
- `AuthorFilterDialog`: 保存済み投稿者から生成したユーザー一覧を検索し、複数ユーザーOR条件を選択して「決定」で閉じます。選択数は入口ボタンの外に表示し、入口の文言は常に「ユーザーを選択」です。
- `withoutTrailingMediaUrl`: UI表示時だけ、メディア付き投稿の本文末尾に付く `https://t.co/...` を取り除きます。DB保存値、検索対象、本文途中のURLは変更しません。
- `PreserveScrollAnchor` / `LazyListScrollbar` / `ScrollToTopButton`: 未分類、分類済み、タグ管理のスクロール位置維持、常に薄い表示専用スクロールバー、白丸黒矢印の一番上へ移動ボタンを扱います。
- 各画面内ではTopAppBarと重複する画面名見出しを表示しません。
- `TagHierarchySelector` / `TagSelectionDialog`: 投稿カード内のタグ選択を、コンパクトな最上位チップと半画面Dialogの単一階層ナビゲーションで扱います。
- `TagFilterSummaryRow` / `filterConditionSummary`: 分類済み画面の一致件数と現在条件を、小さい文字と灰色背景の省スペースな横スクロール領域に表示します。右側には余白を抑えたフィルターアイコンとクリアボタンを固定します。
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
- `EnhancedMediaGrid`: 投稿内画像の表示を扱います。1枚画像はDB上のwidth/heightからアスペクト比を維持し、複数画像はX風グリッドとして切り取り表示を許容します。保存済みの `photo` だけをタップ可能にし、動画/GIFサムネイルや未保存画像は全画面表示対象にしません。
- `FullScreenImageViewer`: 保存済みPhotoを黒背景の全画面Dialogで表示します。上部固定行に閉じるボタンと現在位置だけを表示し、左右スワイプで同一投稿内のPhotoを切り替え、上下ドラッグまたは戻る操作で閉じます。
- `TagNodeRef.saveableKey`: `LazyRow`、`LazyColumn`、`LazyVerticalGrid`のkeyをBundle保存可能な文字列へ変換し、実機でのCompose保存状態エラーを防ぎます。
- ドラッグ中はリスト範囲外へ出ても状態を維持し、auto-scroll loopで端方向へ継続スクロールします。スクロール中もplaceholder位置を更新します。
- グループ中央領域へ乗っている場合だけグループ内drop候補にし、グループ自身や子孫へのdropは保存しません。ドラッグ中にグループを自動展開しません。

## 関連ファイル

- `MainActivity.kt.md`: 画面の状態とRepository呼び出しを渡す入口です。
- `data/ClipRepository.kt.md`: タグ移動、並び替え、タグ再割り当ての業務処理を実行します。
- `data/Entities.kt.md`: タグ階層やフィルター状態のモデルを定義します。

## 変更時の確認事項

タグ階層UIを変えるときは、投稿カードの一時状態、分類済み画面の即時保存、検索条件の一致件数、タグリストの移動制約、Repositoryのslot移動APIと合わせて確認します。画像表示を変えるときは、保存済みPhotoだけが全画面表示対象になること、動画/GIFサムネイルや未保存画像がタップ不可であること、左右スワイプと上下ドラッグ終了が競合しないことを確認します。ドラッグ変更では範囲外drag、auto-scroll、placeholder、グループ内drop、drop後の順序維持を確認します。スクロールバーは表示専用で、タグ管理のdrag gestureと競合せず、スクロール中も強調表示されないことを確認します。

## 件数表示改善（2026-06-20）

- 投稿カードは取得済みいいね数を表示し、1万以上を切り捨ての万表記にします。投稿後7日以内の取得値には警告を付け、タップで正確な件数・取得日時・警告または失敗理由を表示します。
- 投稿者選択は現行順／全件件数順をDialog内で切り替え、表示専用スクロールバーを備えます。
- 絞り込みではタグの投稿登録件数、グループの直下要素数を表示します。タグ管理の件数は背景Badgeを使わない薄い数字表示です。

## UI自動テスト

投稿カード、投稿者クリック領域、いいね数表示、分類確定、タグchip、タグ管理row、操作menu、名称変更menu、移動menu、削除menu、一括追加menu、group展開、root追加button、タグ/グループ削除DialogにはIDを含む安定した `testTag` を付けています。Compose E2Eは表示テキストだけに依存せず、操作後のRoom状態もassertします。タグ管理では、operation menuからのタグ名称変更、グループ名称変更、名称変更Dialogキャンセル、タグの別グループ移動、タグ/グループ移動Dialogキャンセル、別タグへの一括追加Dialogキャンセル、タグ/グループ削除DialogのキャンセルをRoom状態で確認します。
検索/絞り込みDialogには、日付条件、投稿者条件、タグ条件、キャンセル、変更破棄、Dialog内全クリア確認を実機E2Eから安定して操作するため、`filter_options_list`、`filter_start_date`、`filter_end_date`、`filter_date_clear`、`filter_date_picker_apply`、`filter_date_picker_clear`、`filter_author_open`、`filter_author_option_<authorId>_<username>`、`filter_author_confirm`、`filter_tag_condition_<type>_<id>`、`filter_tag_clear`、`filter_cancel`、`filter_discard_*`、`filter_clear_all_*` を付けています。
メディアグリッドと全画面画像viewerには、保存済みPhotoのタップと閉じる操作をスクリーンショットなしで検証するため、`media_asset_<assetId>`、`image_viewer`、`image_viewer_close`、`image_viewer_position`、`image_viewer_photo_<index>` を付けています。
