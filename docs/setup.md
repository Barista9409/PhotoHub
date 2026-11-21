# 写真ハブ（PhotoHub）導入ガイド

**パソコンなしで、スマホのブラウザ（DSMの画面）とアプリだけで導入できます。**
順番どおりに進めれば、本物の写真に触れる前に、必ず「確認だけ（何も動かさない）」の実行を通ります。

| 手順 | 誰が | だいたいの時間 |
|---|---|---|
| 1. NASの準備 | 管理者 | 20分 |
| 2. PhotoHubを起動 | 管理者 | 10分 |
| 3. アプリを入れてログイン | 家族それぞれ | 5分 |
| 4. Googleフォトの取り込みの準備 | 家族それぞれ | 10分 |
| 5. 確認してから本番にする | 管理者 | 確認しだい |
| 6. 外出先から見る（Tailscale） | 家族それぞれ | 5分 |

DSMの画面は、スマホのブラウザで `http://NASのIP:5000` か QuickConnect のアドレスで開けます（DSMの操作はQuickConnectで大丈夫です）。

---

## 写真がどこに保存されるか

| もの | 場所 | 見える人 |
|---|---|---|
| Googleから取り込んだ写真 | 各自が設定で選ぶ：**自分だけ** → `/volume1/homes/<名前>/Photos/PhotoHub/年/年-月/`、**家族で共有** → `/volume1/photo/年/年-月/` | 自分だけ／家族全員 |
| 家族の共有の写真 | `/volume1/photo/`（Synology Photosの共有スペース） | 家族全員 |
| 各自の写真 | `/volume1/homes/<名前>/Photos/`（Synology Photosの個人スペース。スマホの自動バックアップもここ） | 本人だけ |
| 写真ハブ自身のデータ | `/volume1/photohub/`（届いたZIP、処理済みZIP、ゴミ箱、サムネイル、データベース） | 写真ハブだけ |

写真ハブは写真を**自動で削除しません**。整理で「残さない」と決めた写真も `/volume1/photohub/trash/` へ移すだけです。

---

## 1. NASの準備（管理者・DSMの画面で）

1. **タイムゾーン**：コントロールパネル →「地域のオプション」→ Tokyo（今は Seoul になっています）
2. **ユーザーホーム**：コントロールパネル →「ユーザーとグループ」→「詳細設定」→「ユーザーホームサービスを有効にする」にチェック（家族それぞれの個人スペースに必要）
3. **共有フォルダ**：コントロールパネル →「共有フォルダ」→「作成」→ 名前 `photohub`（`photo` はSynology Photosがすでに作っています）
4. **写真ハブ用のユーザー**：「ユーザーとグループ」→「作成」→ 名前 `photohub`、パスワードは長い適当なもの（ログインには使いません）、管理者グループには入れない
   - 権限：`photo`・`photohub`・`homes` を **読み取り/書き込み**、ほかは「アクセスなし」
   - **uidとgidを調べる**（次の手順で使います）：コントロールパネル →「タスクスケジューラ」→「作成」→「予定タスク」→「ユーザー指定のスクリプト」
     - 全般：タスク名 `id`、ユーザー `root`
     - タスク設定のスクリプト：`id photohub > /volume1/photohub/id.txt`
     - 作成したタスクを選んで「実行」→ File Station で `photohub/id.txt` を開く。`uid=1027(photohub) gid=100(users)` のように書いてあります。終わったらタスクは削除してかまいません
5. **Container Manager**：パッケージセンターで「Container Manager」をインストール
6. **Cloud Sync**：パッケージセンターで「Cloud Sync」をインストール（手順4で使います）
7. **Tailscale**：パッケージセンターで「Tailscale」をインストールしてログイン（手順6で使います。外出先で使わないなら後回しでよい）
8. **ファイアウォール**を使っている場合：コントロールパネル →「セキュリティ」→「ファイアウォール」で、自宅のネットワーク（例 192.168.1.0/24）とTailscale（100.64.0.0/10）からの **TCP 8765** を許可。ルーターのポート開放は**しない**

## 2. PhotoHubを起動（管理者）

1. File Station で `docker` 共有フォルダの中に `photohub` フォルダを作る（`/volume1/docker/photohub`）
2. Container Manager →「プロジェクト」→「作成」
   - プロジェクト名：`photohub`
   - パス：`/docker/photohub`
   - ソース：「docker-compose.yml を作成」
3. 出てきた入力欄に、リポジトリの [`docker-compose.yml`](../docker-compose.yml) の中身を**全部貼り付け**、`<-- CHANGE` の行だけ直す
   - `user: "1026:100"` → 手順1-4で調べた `uid:gid`（例 `"1027:100"`）
   - `PHOTOHUB_ADMINS` → 管理者のDSMユーザー名（例 `"reo"`。複数なら `"reo,chie"`）
   - `PHOTOHUB_AMAZON_BACKUP` → Amazonフォトの控えを作れるPCがなければ `"false"`（そのまま）
   - `NTFY_TOPIC` → 通知を受けたい場合、他人に推測されにくい名前（スマホの ntfy アプリで同じ名前を購読）
4. 「次へ」→「完了」。イメージのダウンロードが終わると起動します
5. 動作確認：スマホのブラウザで `http://NASのIP:8765/api/health` を開き、`"ok":true` と `"apply":false` が出ればよい

> 写真ハブの新しい版は、GitHubに入ると自動で作られます（`ghcr.io/barista9409/photohub:latest`）。NASに入れるには、Container Manager →「プロジェクト」→ photohub →「操作」→「ビルド」（または「イメージ」で最新を取得してから再起動）。

## 3. アプリを入れてログイン（家族それぞれ）

1. スマホのブラウザで https://github.com/Barista9409/PhotoHub/releases/latest を開き、`photohub-<版>.apk` をダウンロードして開く
   - 「この提供元のアプリを許可」を聞かれたら許可（アプリの自動更新にも必要です）
   - 以前に 0.2.0 以前を入れていた場合は、先にアンインストール
2. **自宅のWi-Fiにつないだまま**アプリを開く → NASが自動で見つかる
3. **ふだんSynology Photosで使っているユーザー名とパスワード**を入れて「ログイン」
   - 2段階認証を使っている人は、6桁のコード欄が出ます
   - パスワードは写真ハブには保存されません
   - 初めてログインした人は、写真ハブに自動で登録されます
4. 写真へのアクセスを許可（スマホの写真も一緒に表示するため）、通知を許可
5. 設定 →「**Googleから取り込んだ写真の置き場所**」で「自分だけ」か「家族で共有」を選ぶ（あとで変えられます。変えると次の取り込みから反映）
6. 初回の同期は「管理」→「今すぐ同期」。以後は**自宅のWi-Fiで充電中に自動**
7. 既定のギャラリーにする場合：Androidの設定 → アプリ → 既定のアプリ → 写真を開くアプリで「写真ハブ」

**うまくいかないとき**：「NASが見つかりません」→ 自宅のWi-Fiか、手順2-5が動いているか確認して「もう一度探す」。それでもだめなら「アドレスを手で入力する」（`http://NASのIP:8765`）。

## 4. Googleフォトの取り込みの準備（家族それぞれ）

Googleフォトの写真は、Google Takeout → OneDrive → NAS の順に届きます。**一人ずつ**行います。

**4-1. Cloud Sync（管理者が、その人のOneDriveで）**
1. Cloud Sync →「＋」→ OneDrive → その人のMicrosoftアカウントでログイン
2. ローカルのパス：`photohub/inbox/takeout/<その人のDSMユーザー名>`（フォルダがなければ作る）
3. リモートのパス：OneDriveの、Takeoutの書き出し先フォルダ（初回の書き出しが届いてから名前を確認して設定し直す）
4. 同期方向：**双方向**（写真ハブが取り込み終わったZIPを片付けると、OneDriveの容量も空きます。NASには残ります）

**4-2. Takeoutの予約（本人のGoogleアカウントで）**
1. https://takeout.google.com →「選択をすべて解除」→「Googleフォト」だけ選ぶ
2. 配信方法：OneDrive、頻度：「2か月ごとに1年間」、形式 ZIP、サイズ 50GB
3. 「差分（前回以降の分だけ）」の表示が出るか確認（出なければ知らせてください）
4. 予約した日を管理者に伝える → 管理者がコンテナの端末で `photohub user set <名前> --takeout-schedule-start YYYY-MM-DD`（1年で終わる1か月前に通知されます）
5. OneDriveの空きが少ない場合は、期間を区切って何回かに分ける

## 5. 確認してから本番にする（管理者）

最初は `PHOTOHUB_APPLY: "false"` なので、写真ハブは**何も動かさず**、何をするかの記録だけを残します。

1. Takeoutが届いて1〜2時間たったら、アプリの「管理」→「取り込み履歴」を見る
   - 「確認のみ（dry-run）」の行に、新規・重複・失敗の件数が出ます
   - 夜中に、NASにある写真の索引（確認のみ）も1回行われます
2. コンテナの端末でも確認できます（Container Manager →「コンテナ」→ photohub →「詳細」→「端末」→「作成」→「bash」）。下の「端末で使うコマンド」を参照
3. 件数が想定どおりなら、Container Manager →「プロジェクト」→ photohub →「YAML設定」で `PHOTOHUB_APPLY: "true"` に変えて保存・再起動
4. 以後は自動で動きます
   - 10分ごと（とZIPの到着時）にTakeoutを取り込み
   - 毎晩 2:30 にNASの写真を索引、4:00 にデータベースをバックアップ、5:10 にGoogle削除候補を更新
   - 1時間ごとに空き容量・RAID・Takeoutの遅れを確認して通知

**端末で使うコマンド**（どれも、`--apply` を付けない限り確認だけです）

```sh
photohub status                        # 件数と直近の処理
photohub import-takeout --no-wait      # 届いているTakeoutを、確認だけで処理
photohub scan                          # NASの写真の索引を、確認だけで
```

**Google削除の候補**（アプリの「管理」→「Google削除候補」）：撮影から12か月以上たち、NASに保存されてハッシュが一致した写真・動画が、月ごとに出ます。**Googleからの削除は、本人がGoogleフォトのアプリで**行い、終わったら「削除した」を押します。
- `PHOTOHUB_AMAZON_BACKUP: "false"` の場合、削除した写真の控えはNASだけになります。**Hyper Backup でUSBディスクなどへの定期バックアップ**を設定しておくことを強くすすめます（パッケージセンター →「Hyper Backup」→ 対象に `photo`・`homes`・`photohub`）

## 6. 外出先から見る（Tailscale・家族それぞれ）

1. スマホに「Tailscale」アプリを入れ、NASと同じTailnetに参加（管理者がTailscaleの管理画面から家族を招待するか、家族用の共有アカウントでログイン）
2. アプリは、自宅のWi-Fiでないときに自動でTailscale経由になります（アドレスはログイン時にNASから受け取っています）
3. 外出先では、一覧・検索・地図は通信せずに表示します。写真を開いたときだけ、必要ならNASから取り寄せます

---

## アプリの更新

- 新しい版は GitHub のリリースから、Wi-Fiのとき12時間ごと（とアプリを開いたとき）に自動で入ります
- 新しい版を作るとき：Claudeに「ビルドに出して」と頼む（版の番号を上げて main にプッシュ → GitHub Actions が署名付きでリリース）
- **リリース用の鍵**（`photohub-release.jks` と `photohub-release-key.txt`）は、なくすと二度と更新を配れません。2か所に保管してください。GitHubのSecretsにも登録済みです

## 困ったとき

| こんなとき | すること |
|---|---|
| アプリでNASが見つからない | 自宅のWi-Fiか確認 → `http://NASのIP:8765/api/health` が開けるか → ファイアウォールで8765を許可 |
| ログインできない | Synology Photosに同じユーザー名・パスワードでログインできるか確認。10回失敗すると10分待ちます |
| Takeoutが取り込まれない | ZIPが `photohub/inbox/takeout/<名前>/` に入っているか（直下は取り込みません）。「取り込み履歴」のエラーを見る |
| 写真がSynology Photosに出てこない | Synology Photos の設定 →「索引の再作成」 |
| 古い写真を消したい | `photohub/trash/` の中を30日たったら手で削除（写真ハブは消しません） |
| データベースが壊れた | `photohub/db/backup/` から戻すか、端末で `photohub scan --apply`（写真とXMPから作り直し） |

## そのほかの設定（docker-compose.yml の environment に追加）

| 名前 | 既定 | 意味 |
|---|---|---|
| `PHOTOHUB_AUTO_USERS` | `true` | DSMでログインした人を自動で登録。`false` なら `photohub user add <名前>` で登録した人だけ |
| `PHOTOHUB_LAN_URL` / `PHOTOHUB_REMOTE_URL` | 自動 | NASの自宅／Tailscaleのアドレス。自動で取れないときだけ |
| `PHOTOHUB_WORK_HOURS` | なし | 重い処理をする時間帯（例 `1-7`） |
| `PHOTOHUB_WORKERS` | `2` | 同時に処理する数 |
| `PHOTOHUB_SCAN_EXTRA` | なし | ほかに索引するフォルダ（コンテナ内のパス、`:` 区切り） |
| `SYNOLOGY_API_ENABLED` | `false` | Synology Photosにアルバムを作る（`photohub synology probe` で確認してから） |
