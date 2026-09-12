# LocalSearchLexical

検索用の不変表現、FTS5 literal生成、文字列score、bounded fallbackを担当する。Sudachiの既存index生成仕様は変更しない。

- raw比較だけNFKC・Locale.ROOT lowercase・trimを適用する。Sudachiのnormalized / reading / romanized / compactはそのまま利用する。空表現を除外し、表現列とfallback用tokenは決定論的に重複除去する。通常一致用token列内の重複語はmultiset判定のため維持する。
- FTS5の各phrase／tokenを二重引用符で囲み、内部引用符を二重化する。通常FTSは元順phraseと複数tokenのAND。trigramはcompactまたは空白・Unicode separator・punctuation除去後の3 code points以上。ユーザー由来のFTS演算子は評価しない。
- 基本scoreはraw完全一致1.00、派生表現完全一致0.97、連続substring0.92、token subsequence0.88、token multiset0.82、compact部分一致0.74、一部token0.52 + 0.22 × coverage。coverageは各表現ごとに計算し最大値を使う。
- source weightはtext1.00、summary / author_name / username0.95、ocr0.90。未知sourceは整合性エラー。
- OSAはquery token 3〜64 code points、document tokenは64以下。Unicode code pointsと3行rolling bufferを使い、best similarity 0.66以上のquery tokenが半数以上の場合だけ採用する。document tokenはbounded bipartite matchingで1回だけ割り当てる。上限0.62。通常一致があれば距離計算を省略する。
- anagramは4〜32 code pointsの比較正規化後multiset。同一tokenは対象外。複数tokenはsignature別の非同一token対応条件により重複しない割当を確認する。同じ長さのcompact全体も許可する。上限0.42。
- compact query 1〜2文字では、いずれかの比較表現がsubstringを含むdocumentだけ短query候補にする。

人工code point列を使った `LocalSearchLexicalTest` でliteral escape、重複token、距離・signature境界、固定scoreの処理分岐を確認する。自然言語の期待順位は評価しない。
