# PhotoHub（写真ハブ）

Synology NAS上で動く写真の取り込み・整理サービスと、外出先でも速く写真を見られるAndroidアプリ。

- 仕様書：[docs/spec.md](docs/spec.md)（v0.2：実装時の決定は第10章）
- 調査結果：[docs/research.md](docs/research.md)
- **導入手順：[docs/setup.md](docs/setup.md)**

## 構成

| パス | 内容 |
|---|---|
| `server/` | NAS側サービス（Python 3.12、FastAPI、SQLite）。Takeout取り込み、重複検出、正本への配置、走査、保存先管理、通知、差分配信API |
| `docker-compose.yml` | Container Manager 用 |
| `android/` | Androidアプリ（Kotlin、Jetpack Compose、Room、WorkManager、Coil、Media3、osmdroid） |
| `tools/amazon-heartbeat.ps1` | PCのAmazon Photosアプリが動いていることをNASへ知らせるスクリプト |

## 安全のしくみ

- コマンドは `--apply` を付けない限り **確認のみ（dry-run）**。常駐サービスは `PHOTOHUB_APPLY=true` のときだけファイルを動かす
- 写真を自動で削除しない。残さない写真は `trash/` へ移すだけ。Googleからの削除は人が行う
- 移動・配置は「コピー → ハッシュで確認 → 元を移す」。正本の既存ファイルは上書きしない
- 処理は何度実行しても同じ結果になり、途中で止まっても続きから進む

## 開発

```sh
# NAS側
python3 -m venv .venv && . .venv/bin/activate
pip install -e "server[test]"
cd server && pytest            # GPTH・ExifTool・ffmpeg があれば全テストが動く

# Android
cd android && ./gradlew testDebugUnitTest assembleDebug
```

アプリの新しい版を配る：`versionCode`/`versionName` を上げて `v<版>` タグをプッシュ → GitHub Actions がReleaseを作り、スマホが自動で更新（docs/setup.md §8b）

サンプルのTakeout（本物の写真を使わない）：`photohub make-sample /tmp/takeout-sample-001.zip -n 100`
