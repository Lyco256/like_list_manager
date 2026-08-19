# 33. タグ管理Treeの連続縦guide

## 目的

32のcompact rowへ、祖先階層を表す縦線だけのTree guideを追加し、深い入れ子を読みやすくする。

## 表示仕様

- depth 0はguideなし。
- depth 1なら左側に1本、depth 2なら2本のように、必要な祖先depthごとに縦guideを描く。
- 横branch線は一切描かない。
- 同じdepthのguideが上下の隣接rowで続く場合、row間で途切れず1本につながって見える。
- subtreeが終了したdepthの線は、それ以降の無関係なsiblingへ伸ばさない。
- expand/collapse後も正しいguide集合へ再計算する。
- drag中のplaceholderでguideが突然ずれてTreeを誤認させない。

## 実装

- `VisibleTagRow` またはTree flattening結果に、「どのancestor depthの縦線をこのrowで継続するか」を表す情報を持たせる。
- 単純に `0 until depth` 全てへ無条件線を引いて、終わったbranchまで伸ばす実装にしない。
- rowの上下padding/spacingも含む高さへlineを描き、隣接segmentが視覚的につながる。
- Tree計算と描画を分離し、guide継続判定をunit test可能にする。

## テスト

- depth 0/1/2/3のguide本数。
- ancestorに後続siblingがある場合のline継続。
- subtree末尾で不要lineが終了。
- siblingとnested siblingが混在するfixture。
- expand/collapseでguide metadata更新。
- horizontal lineが描画されない。
- drag placeholderを挟んでも階層guideが破綻しない。
