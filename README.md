# PhotoHub（写真ハブ）

Synology NAS上で動く写真の取り込み・整理サービスと、外出先でも速く写真を見られるAndroidアプリ。

- 仕様書：[docs/spec.md](docs/spec.md)
- P0 調査結果：[docs/research.md](docs/research.md)

## 現在の段階

P0（調査）完了。P1（取り込みCLI）は、research.md §0 の決定事項（D1〜D5）を待っている。

## 秘密情報

DSMのパスワードやAPIトークンはコミットしない。`.env.example` をコピーして `.env` を作り、NAS上にだけ置く。
