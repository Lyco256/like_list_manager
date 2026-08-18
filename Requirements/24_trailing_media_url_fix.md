# 24. 本文なしメディアツイートの末尾t.co除去バグ修正

## 目的

本文がなく、表示text全体がメディア用 `https://t.co/...` だけのツイートでURLが残る回帰を修正する。

## 原因

現在の `withoutTrailingMediaUrl` は末尾t.coの前に空白を必須としているため、文字列先頭からURLが始まるURL-only textへ一致しない。

## 実装

- assetが存在する場合だけ末尾media t.coを省略する既存条件を維持する。
- URL-only textでも末尾t.coを除去できるよう、先頭または空白境界を扱う。
- 本文 + 末尾t.co、末尾に複数t.coが並ぶケースも維持する。
- 本文中のURL、末尾ではないt.co、assetがない投稿のURLは勝手に消さない。
- DBのtext自体は変更しない。表示変換だけを修正する。

## テスト

`withoutTrailingMediaUrl` の回帰テストへ最低限以下を追加する。

- assetあり + `https://t.co/abc` のみ → 空文字。
- assetあり + `本文 https://t.co/abc` → `本文`。
- assetあり + `本文 https://t.co/a https://t.co/b` → `本文`。
- assetなし + URL-only → 元文字列。
- 本文中URL → 保持。
- URLに似ているがtoken境界のない文字列 → 誤除去しない。
