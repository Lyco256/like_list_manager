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
- `TagManagementRow`: タグリストの行表示、グループの展開、行間挿入線、グループへのドロップ強調、ドラッグ中プレビューを扱います。
- `EnhancedMediaGrid`: 投稿内画像のグリッド表示を再利用します。
- `TagNodeRef.saveableKey`: `LazyRow`、`LazyColumn`、`LazyVerticalGrid`のkeyをBundle保存可能な文字列へ変換し、実機でのCompose保存状態エラーを防ぎます。
- ドラッグ中はリスト端へ近づけると自動スクロールします。ルート専用ドロップ領域は表示せず、ルートへの移動はルート階層の行間ドロップで扱います。
- ドラッグプレビューは親Box座標へ変換して表示し、タグ一覧領域外へ出た場合はドラッグ状態を解除します。グループ中央へのドロップ判定は上下の行間判定より広めに取り、行間への挿入線は同じ親の次行Beforeへ寄せて二重表示を避けます。

## 関連ファイル

- `MainActivity.kt.md`: 画面の状態とRepository呼び出しを渡す入口です。
- `data/ClipRepository.kt.md`: タグ移動、並び替え、タグ再割り当ての業務処理を実行します。
- `data/Entities.kt.md`: タグ階層やフィルター状態のモデルを定義します。

## 変更時の確認事項

タグ階層UIを変えるときは、投稿カードの一時状態、分類画面の即時保存、タグリストの移動制約、Repositoryの移動APIと合わせて確認します。
