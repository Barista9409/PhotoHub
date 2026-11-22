# PhotoHub導入ガイド

**パソコンなしで、スマホのブラウザ（DSMの画面）とアプリだけで導入できます。**
順番どおりに進めれば、本物の写真に触れる前に、必ず「確認だけ（何も動かさない）」の実行を通ります。

> このガイドは、アプリの右下の「設定」→「アプリ」→「使い方（導入ガイド）」からも、いつでも最新版を開けます（ログイン前の画面にも「導入ガイドを見る」があります）。

| 手順 | 誰が | だいたいの時間 |
|---|---|---|
| 1. NASの準備 | 管理者 | 20分 |
| 2. PhotoHubを起動 | 管理者 | 10分 |
| 3. アプリを入れてログイン | 家族それぞれ | 5分 |
| 4. バックアップとGoogleドライブ、Googleフォトの移行 | 管理者・家族それぞれ | 20分＋移行の待ち時間 |
| 5. 確認してから本番にする | 管理者 | 確認しだい |
| 6. 外出先から見る（Tailscale） | 家族それぞれ | 5分 |

DSMの画面は、スマホのブラウザで `http://NASのIP:5000` か QuickConnect のアドレスで開けます（DSMの操作はQuickConnectで大丈夫です）。

---

## 写真がどこに保存されるか

| もの | 場所 | 見える人 |
|---|---|---|
| スマホからのバックアップ | Synology Photosのバックアップと同じ `/volume1/homes/<名前>/Photos/MobileBackup/<機種名>/...` | 本人だけ |
| Googleフォト・ドライブから取り込んだ写真 | 各自がアプリで選ぶ：**自分だけ** → `/volume1/homes/<名前>/Photos/PhotoHub/年/年-月/`、**家族で共有** → `/volume1/photo/年/年-月/` | 自分だけ／家族全員 |
| 家族の共有の写真 | `/volume1/photo/`（Synology Photosの共有スペース） | 家族全員 |
| 各自の写真 | `/volume1/homes/<名前>/Photos/`（Synology Photosの個人スペース。スマホの自動バックアップもここ） | 本人だけ |
| PhotoHub自身のデータ | `/volume1/photohub/`（届いたZIP、処理済みZIP、ゴミ箱、サムネイル、データベース） | PhotoHubだけ |

PhotoHubは写真を**自動で削除しません**。整理で「残さない」と決めた写真も `/volume1/photohub/trash/` へ移すだけです。

---

## 1. NASの準備（管理者・DSMの画面で）

1. **タイムゾーン**：コントロールパネル →「地域のオプション」→ Tokyo（今は Seoul になっています）
2. **ユーザーホーム**：コントロールパネル →「ユーザーとグループ」→「詳細設定」→「ユーザーホームサービスを有効にする」にチェック（家族それぞれの個人スペースに必要）
3. **共有フォルダ**：コントロールパネル →「共有フォルダ」→「作成」→ 名前 `photohub`（`photo` はSynology Photosがすでに作っています）
4. **PhotoHub用のユーザー**：「ユーザーとグループ」→「作成」→ 名前 `photohub`、パスワードは長い適当なもの（ログインには使いません）、管理者グループには入れない
   - 権限：`photo`・`photohub`・`homes` を **読み取り/書き込み**、ほかは「アクセスなし」
   - **アプリケーションの権限：すべて「拒否」のままでよい**（このユーザーはファイルの持ち主として使うだけで、DSMやSynology Photosにはログインしません。あとで `SYNOLOGY_API_ENABLED` を使うときだけ「Synology Photos」を許可）
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

### サーバー（Synology側）を新しい版にする

PhotoHubの新しい版は、GitHubに入ると自動で作られます（`ghcr.io/barista9409/photohub:latest`）。写真・設定・データベースはすべてNASのフォルダ（`photohub`・`photo`・`homes`）にあり、入れ替えるのは「プログラム」だけなので、下の手順で消えるものはありません。

1. Container Manager →「プロジェクト」→ `photohub` を選んで「停止」
2. 同じ画面で「操作」→「クリーン」（古いコンテナを片付けます。データは消えません）
3. 左の「イメージ」→ `ghcr.io/barista9409/photohub`（タグ `latest`）を選んで「削除」
4. 「プロジェクト」→ `photohub` →「操作」→「ビルド」。最新の版をダウンロードして起動します（数分）
5. 確かめ方（どちらか）
   - アプリ：右下の「設定」→「アプリ」→「更新・家族に送る」→「Synology側の版」に日付（例 `2026-11-22-xxxxxxx`）が出る
   - スマホのブラウザで `http://NASのIP:8765/api/health` を開き、`"build"` の日付を見る

> うまくいかないとき：「プロジェクト」→ `photohub` の「ログ」を開くと、起動時のエラーが見られます。

### 再起動するとき（YAMLを変えたあとなど）

自動更新を入れていると、Container Manager の「停止」「再起動」ボタンが失敗することがあります（自動更新がコンテナを作り直すため）。再起動は、次のタスクで行ってください。

1. タスクスケジューラ →「作成」→「予約タスク」→「ユーザー指定のスクリプト」
2. タスク名 `PhotoHub再起動`、ユーザー `root`、「有効」のチェックは外す
3. 「タスク設定」に下を貼り付けて「OK」。使うときは一覧で選んで「実行」

```
cd /volume1/docker/photohub || exit 1
{ date; docker rm -f photohub; docker compose up -d || docker-compose up -d; } >> /volume1/docker/photohub/update.txt 2>&1
```

### 新しい版を自動で入れる（おすすめ・最初に1回だけ設定）

DSMの「タスクスケジューラ」に、新しい版を確かめるスクリプトを登録します。DSMのタスクは1時間ごとにしか起動できないので、スクリプトは起動したら55分間、**1分ごと**に確かめ続けます。確かめるときはGitHubに「最新版の番号」を聞くだけで、ダウンロードはしないので軽く済みます。新しい版が出ていれば、1分ほどで入れ替わります。

1. コントロールパネル →「タスクスケジューラ」→「作成」→「予約タスク」→「ユーザー指定のスクリプト」（前に作った `PhotoHub自動更新` があれば、それを選んで「編集」）
2. 「全般」タブ：タスク名 `PhotoHub自動更新`、ユーザー **`root`**、「有効」にチェック
3. 「スケジュール」タブ：「実行日数」で「毎日」、開始時刻 `00:00`、「同日内に実行し続ける」にチェック、繰り返し **毎時**、最終実行時刻 **`23:00`**（ここが開始時刻と同じだと、1日1回しか動きません）
4. 「タスク設定」タブ：「ユーザー指定のスクリプト」の中身を全部消して、下のスクリプトを**全部**貼り付け →「OK」（[`tools/synology-update.sh`](../tools/synology-update.sh) と同じものです）

```
#!/bin/bash
# PhotoHub auto-update for Synology (DSM Task Scheduler, user root, hourly 00:00-23:00).
# DSM cannot start a task more often than hourly, so each run keeps checking once a minute
# for 55 minutes: a new version is in within about a minute of being published.
#
# Lessons built in:
# - Asks GHCR for the digest of :latest (a tiny request) instead of pulling every time,
#   so it can run every minute and a new version is in within about a minute of being published.
# - Does not depend on Container Manager's API (build_stream failed with code 2104 on DSM).
# - Never runs twice at the same time (lock), and never gets stuck: a stale lock is cleared.
# - Checks the new version actually answers /api/health; if not, goes back to the previous
#   version and does not try that broken version again.
# - Writes the result in plain words to update-last.txt (.txt so File Station opens it).
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH
IMG=ghcr.io/barista9409/photohub
DIR=/volume1/docker/photohub
LOG=$DIR/update.txt
LAST=$DIR/update-last.txt
BAD=$DIR/update-bad.txt
LOCK=/run/photohub-update.lock   # memory, not disk
[ -w /run ] || LOCK=/tmp/photohub-update.lock
cd "$DIR" || exit 1

now() { date '+%Y-%m-%d %H:%M'; }
# write only when the message changes (no disk write every minute)
say() { [ "$(cut -d' ' -f3- "$LAST" 2>/dev/null)" = "$1" ] || echo "$(now) $1" > "$LAST"; }
log() { echo "$(now) $1" >> "$LOG"; }

# one at a time; the running one touches the lock every minute, so a lock untouched for
# 10 minutes is left over from a crash
if ! mkdir "$LOCK" 2>/dev/null; then
  [ -n "$(find "$LOCK" -maxdepth 0 -mmin +10 2>/dev/null)" ] && rm -rf "$LOCK" && mkdir "$LOCK" 2>/dev/null || exit 0
fi
trap 'rm -rf "$LOCK"' EXIT

check() {

# 1) what GHCR has now (digest only, no download)
tok=$(curl -s --max-time 20 "https://ghcr.io/token?scope=repository:${IMG#ghcr.io/}:pull" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
remote=$(curl -sI --max-time 20 -H "Authorization: Bearer $tok" \
  -H "Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json" \
  "https://ghcr.io/v2/${IMG#ghcr.io/}/manifests/latest" | tr -d '\r' | sed -n 's/^[Dd]ocker-[Cc]ontent-[Dd]igest: *//p')
if [ -z "$remote" ]; then
  say "確認できませんでした（インターネットかGitHubにつながりません。次の回にもう一度確かめます）"
  return
fi

# 2) what is running (stopped on purpose, e.g. to edit the YAML: leave it alone)
if [ "$(docker inspect -f '{{.State.Running}}' photohub 2>/dev/null)" != "true" ]; then
  say "PhotoHubが止まっているので、更新は休んでいます（起動すると、また確かめます）"
  return
fi
cur_img=$(docker inspect -f '{{.Image}}' photohub 2>/dev/null)
cur_digests=$(docker image inspect -f '{{range .RepoDigests}}{{println .}}{{end}}' "$cur_img" 2>/dev/null)
if echo "$cur_digests" | grep -q "$remote"; then
  say "確認：最新です（入れ替えなし）"
  return
fi
if grep -qx "$remote" "$BAD" 2>/dev/null; then
  say "新しい版は起動しなかったため、前の版のままです（次に新しい版が出たら入れます）"
  return
fi

# 3) download and swap
log "新しい版を見つけました: $remote"
if ! docker pull -q "$IMG:latest" >> "$LOG" 2>&1; then
  say "ダウンロードに失敗しました（次の回にもう一度やります）"; log "pull failed"; return
fi
[ -n "$cur_img" ] && docker tag "$cur_img" photohub-previous:latest 2>/dev/null
compose() { docker compose up -d "$@" >> "$LOG" 2>&1 || docker-compose up -d "$@" >> "$LOG" 2>&1; }
docker rm -f photohub >> "$LOG" 2>&1
compose

# 4) does it answer?
ok=""
for i in $(seq 1 36); do   # up to 3 minutes
  sleep 5
  curl -s --max-time 4 http://127.0.0.1:8765/api/health | grep -q '"ok": *true' && ok=1 && break
done
if [ -n "$ok" ]; then
  build=$(curl -s --max-time 4 http://127.0.0.1:8765/api/health | sed -n 's/.*"build": *"\([^"]*\)".*/\1/p')
  say "新しい版を入れました（$build）"; log "ok $build"
  docker image prune -f >> "$LOG" 2>&1
else
  log "新しい版が起動しません。前の版に戻します"
  echo "$remote" >> "$BAD"; tail -n 20 "$BAD" > "$BAD.tmp" && mv "$BAD.tmp" "$BAD"
  if docker image inspect photohub-previous:latest >/dev/null 2>&1; then
    docker tag photohub-previous:latest "$IMG:latest"
    docker rm -f photohub >> "$LOG" 2>&1
    compose
  fi
  say "新しい版が起動しなかったので、前の版に戻しました（update.txt を見てください）"
fi

}

# check once a minute for 55 minutes (the next hourly run takes over)
end=$(( $(date +%s) + ${RUN_MINUTES:-55} * 60 ))
while :; do
  touch "$LOCK"
  check
  [ "$(date +%s)" -ge "$end" ] && break
  sleep 60
done

# keep the record short
tail -n 400 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
```

> このスクリプトがすること
> - 新しい版があるときだけダウンロードして入れ替えます。Container Manager の機能には頼りません（以前の方法は、このSynologyではエラー 2104 で動きませんでした）
> - 入れ替えたあと、PhotoHubがちゃんと動き始めたかを確かめます。動かなければ自動で前の版に戻し、その版はもう入れません
> - PhotoHubを止めているあいだ（YAMLを直すときなど）は、何もしません
> - 同時に2つ動かないようにしてあります
> - 結果は `docker/photohub/update-last.txt` に1行で、詳しい記録は `update.txt` に残ります（File Station でそのまま開けます）
>
> 入れ替えたあとは、Container Manager の「停止」ボタンが失敗することがあります。止めたいとき・再起動したいときは、上の `PhotoHub再起動` タスクか、Container Manager の「プロジェクト」→ photohub →「操作」→「ビルド」を使ってください。

5. すぐ試すには、一覧で `PhotoHub自動更新` を選んで「実行」（そのまま55分間、1分ごとに確かめ続けます）。1〜3分待って `update-last.txt` を開き、「最新です」か「新しい版を入れました」と出ていれば成功です。アプリの「アプリ」→「更新・家族に送る」→「Synology側の版」にも「最新です」と出ます


> **外出先でつながらないとき**：Synology用のTailscaleは、PhotoHubから自分のTailscaleのアドレスが見えないことがあります。アプリの右下の「設定」→「接続と保存場所」→「外出先からの接続（Tailscale）」に、スマホのTailscaleアプリのデバイス一覧に出るSynologyのアドレス（`100.` で始まる）を入れてください。

## 3. アプリを入れてログイン（家族それぞれ）

1. スマホのブラウザで **https://github.com/Barista9409/PhotoHub/releases/latest/download/PhotoHub.apk** を開いてダウンロードし、開く（いつも最新版が落ちてくるリンクです。すでに使っている人は、右下の「設定」→「アプリ」→「更新・家族に送る」→「アプリを家族に送る」でLINEなどに送れます）
   - 「この提供元のアプリを許可」を聞かれたら許可（アプリの自動更新にも必要です）
   - 以前に 0.2.0 以前を入れていた場合は、先にアンインストール
2. **自宅のWi-Fiにつないだまま**アプリを開く → NASが自動で見つかる
3. **ふだんSynology Photosで使っているユーザー名とパスワード**を入れて「ログイン」（メールアドレスでログインしている人は、メールアドレスでもかまいません。PhotoHubがSynologyのユーザー名を確かめて、その人の写真フォルダ `homes/<ユーザー名>/Photos` を使います）
   - 2段階認証を使っている人は、6桁のコード欄が出ます
   - パスワードはPhotoHubには保存されません
   - 初めてログインした人は、PhotoHubに自動で登録されます
4. 写真へのアクセスを許可（スマホの写真も一緒に表示するため）、通知を許可
5. 右下の「設定」→「接続と保存場所」→「Synologyの保存場所」の「**Googleから移した写真の置き場所**」で「自分だけ」か「家族で共有」を選ぶ（あとで変えられます）
6. NASの写真をアプリに出す：右下の「設定」→「写真と容量」→「このスマホの容量」→「今すぐ一覧を更新」。以後は**自宅のWi-Fiで充電中に自動**
7. 既定のギャラリーにする場合：Androidの設定 → アプリ → 既定のアプリ → 写真を開くアプリで「PhotoHub」

**うまくいかないとき**：「NASが見つかりません」→ 自宅のWi-Fiか、手順2-5が動いているか確認して「もう一度探す」。それでもだめなら「アドレスを手で入力する」（`http://NASのIP:8765`）。

## 4. バックアップとGoogleドライブ（最近の写真のクラウド置き場）

**いつ動くか（押さなくても自動）**

- 写真や動画を撮ると、**数分以内に**Synology（とドライブ）へ送ります。ほかに約1時間ごとにも確かめます。Wi-Fiのときだけ（「モバイル回線でも送る・受け取る」をオンにすると、モバイル回線でも）
- Synologyの写真の一覧は、アプリを開いたとき・フォト画面を下に引っぱったとき・送り終わったときに受け取ります。自宅のWi-Fiで充電中は、6時間ごとにまとめて受け取ります
- 設定の「バックアップ」に「**電池の最適化から外す（おすすめ）**」が出ていたら押して「許可」を選んでください。外さないと、Androidが自動の処理を何時間も後回しにすることがあります

スマホの写真は **PhotoHubのアプリがバックアップ** します。Synology Photos のバックアップと**同じフォルダ・同じ並べ方**に置くので、**両方オンのままでも重複しません**（片方だけでも大丈夫です）。後から同じ写真が別の名前で届いた場合も、夜の走査でPhotoHubのコピーをゴミ箱フォルダへ移し、1枚にそろえます。

| 送り先 | いつ | 何のため |
|---|---|---|
| NAS | 自宅のWi-Fi（外出先はTailscale経由、モバイル回線は設定しだい） | 正本。全部の写真 |
| Googleドライブ（任意） | いつでも（モバイル回線は設定しだい） | 最近の写真のクラウドの控え。**範囲を外れると、NASに入ったのを確かめてから自動で消す** ので、溜まり続けない |

**4-1. Googleドライブを使えるようにする（管理者が1回だけ・スマホのブラウザで約10分）**
1. https://console.cloud.google.com/ を開き、上の「プロジェクトを選択」→「新しいプロジェクト」→ 名前 `PhotoHub` で作成
2. 左上のメニュー →「APIとサービス」→「ライブラリ」→「Google Drive API」を検索 →「有効にする」
3. メニュー →「Google Auth Platform」（または「OAuth同意画面」）
   - 「ブランディング」：アプリ名 `PhotoHub`、ユーザーサポートメールとデベロッパーの連絡先に自分のメール
   - 「対象」：ユーザーの種類「外部」→ 公開ステータスを「**本番環境に公開**」（使う権限は「アプリが作ったファイルだけ」なので、Googleの審査は不要です。テスト中のままだと7日ごとに接続し直しになります）
   - 「データアクセス」→「スコープを追加」→ `https://www.googleapis.com/auth/drive.file` にチェック →「更新」→「保存」
4. 「クライアント」→「クライアントを作成」→ アプリケーションの種類「**Android**」
   - パッケージ名：`com.photohub.app`
   - SHA-1 証明書フィンガープリント：`59:ED:96:B1:BF:A1:D2:91:B3:69:66:15:15:E1:57:FE:13:E1:B5:32`
   - 「作成」（表示されるクライアントIDは、どこにも入力しなくて大丈夫です）

**4-2. 各自のスマホで**
1. 右下の「設定」→「バックアップ」
   - 「NASへのバックアップ」：オン（最初からオン）
   - 「Googleドライブへのバックアップ」：開いてオン → Googleアカウントを選んで許可（見られるのはこのアプリが置いた写真だけです）
   - 置いておく範囲：「期間」「容量」「両方」から選び、目盛りで調整（例：直近3か月、10GBまで）
   - 「モバイル回線でもバックアップする」：通信量が気にならなければオン
2. それぞれの画面の「今すぐバックアップ」で最初の1回（写真が多いと時間がかかります。自宅のWi-Fiで充電しながらがおすすめ）
3. Googleフォトアプリのバックアップは**オフ**にする（Googleフォトに溜まり続けるのを止めるため）

**4-3. ドライブからNASへの自動の受け取り（一人ずつ）**
外出先でとった写真も、ドライブ経由ですぐNASに入るようにします。

> **置き場所のルール：Googleドライブから届くものは、すべて本人のホームフォルダに置きます。** ドライブの「PhotoHub」は `homes/<名前>/PhotoHub`、Takeout は `homes/<名前>/Takeout`（手順4-5）。本人と管理者しか見られません。

1. Cloud Sync →「＋」→ Google Drive → その人のGoogleアカウントでログイン（2回目からは、作ってある接続に「タスクを作成」）
2. ローカルのパス：`/home/PhotoHub`（本人のホームに `PhotoHub` フォルダを作って選ぶ。管理者が作るときは `/homes/<名前>/PhotoHub`）
3. リモートのパス：`/PhotoHub`（アプリが作るフォルダ。初回のバックアップの後に現れます）
4. 同期方向：「**リモートの変更のみダウンロード**」

> その人のドライブ**全体**をすでにCloud Syncで同期している場合（「作成」が押せない場合）は、追加の設定は不要です。PhotoHubが `homes/<名前>/<そのフォルダ>/PhotoHub` と `…/Takeout` を自動で見つけます。ホームの深い場所に同期している人や、見つからないときは、本人がアプリの「Synologyの保存場所」で「ドライブに保存した写真（PhotoHub）が届くフォルダ」と「Takeoutが届くフォルダ」を、それぞれフォルダをたどって選べます（何階層下でも可）。
>
> 以前の手順で `photohub/inbox/drive/<名前>` に同期している場合：Cloud Syncでそのタスクを削除し（ファイルは消えません）、上の手順で作り直してください。届いていた写真は重複として1枚にまとまるので、`photohub/inbox/drive/<名前>` はそのあと消してかまいません。

**4-4. 写真の整理とゴミ箱**
右下の「設定」→「写真と容量」→「写真の整理とゴミ箱」で、まとめて片付けられます。
- **スマホの空き容量**：Synologyに保存済みで、選んだ期間（1・3・6・12か月）より前の写真をスマホから消します（Androidの確認画面が1回出ます）。消してもPhotoHubのアプリで見られます
- **まったく同じ写真が2か所に**・**似ている写真**・**スクリーンショット**・**大きい動画**・**ぼやけた写真**
- 写真を長押しして選び、右上のゴミ箱を押すと、**Synology・Googleドライブ・スマホのすべてから**消えます。Synologyの写真は**30日間「ゴミ箱」に残り、元に戻せます**（`photohub/trash/bin`）。30日たつと自動で完全に削除されます。家族の共有の写真は誰でも消せて、家族みんなのゴミ箱に出ます
- 選んだ写真は「自分だけ ⇄ 家族で共有」に移すこともできます（家族で共有 → `photo/年/年-月`、自分だけ → `homes/<名前>/Photos/PhotoHub/年/年-月`）
- Synology PhotosやFile Stationで消した写真も、次の確認（3時間ごと）でアプリから消えます

**4-4b. 場所で探す・アルバム**
- 位置情報のある写真には、Synologyの中にある地名の辞書（GeoNames）で「東京都 渋谷区」のような地名が付きます（位置情報を外には送りません）。「検索」で地名を入れるか、「場所から探す」から選べます
- アルバム：写真を長押しして選び、上のアルバムのボタンで追加・新規作成（家族で共有にもできます）。アルバムを開くと名前の変更・削除、選んだ写真を外すこともできます
- ログイン時に「Synology Photosのアルバムも同期する」を選んだ人は、Synology Photosのアルバムが15分ごとにアプリに反映され、アプリでの変更もSynology Photosに送られます（アルバムタブの「Synology Photosのアルバムを読み込む」ですぐ反映）。Synology Photosのこの機能は公開仕様がないため、うまくいかないときは「Synologyの保存場所」→「接続を確かめる」の結果を開発者に送ってください

**4-4c. 家族が始めるときの順番（必ずこの順で）**

1. アプリを**最新版にしてから**ログインする（古い版で送ると、日付のない画像が「届いた日」になります）
2. 「今すぐ同期・保存」で、スマホの写真を Synology に送り終える。「ライブラリの内訳」で **「このスマホにしかない」が 0 枚**になっていることを確かめる
3. Takeout を取り込む（4-5）。「取り込みの状況」で完了を確かめる
4. 最後に「Googleフォトを空にする」。この画面の上が **「確認OK」** になっているときだけ、Googleフォトのアプリで消す
   - **Googleフォトで消すと、スマホの中の写真も一緒に消えます。** Synology にあるので PhotoHub では見られますが、先に 2 が終わっていないと、日付などの手がかりが失われます

**4-5. 今あるGoogleフォトの写真を一度だけ取り込む（移行）**

おすすめは**Googleドライブ経由**です。Synologyがドライブから直接受け取るので、スマホを通さず、いちばん速く終わります（30GBでも、ふつうの光回線なら1時間前後で届きます）。

1. 本人のGoogleアカウントで https://takeout.google.com →「選択をすべて解除」→「Googleフォト」だけ → 配信方法「**ドライブに追加**」、頻度「1回エクスポート」、形式 ZIP、サイズ 50GB。ドライブに「Takeout」フォルダができます（すでにできていればそのままでOK）
2. Synologyに届ける（どちらか）
   - **その人のドライブ全体をCloud Syncで同期している場合**：設定は不要です。ドライブの `Takeout` フォルダが `/volume1/homes/<名前>/<同期フォルダ>/Takeout` に届き、PhotoHubが自動で見つけます
   - **そうでない場合**：Cloud Sync →「＋」→ Google Drive（本人のアカウント）→ タスクを追加
     - ローカルパス：`/home/Takeout`（本人のホームに `Takeout` フォルダを作って選ぶ。管理者が作るときは `/homes/<名前>/Takeout`）
     - リモートパス：`/Takeout`
     - 同期方向：「**双方向**」（Synologyに写し終わったZIPをPhotoHubが消すと、Googleドライブからも消えます。消えたファイルはドライブのゴミ箱に30日残るので、すぐ容量を空けたいときはドライブのゴミ箱も空にしてください）
   - 自動で見つからないとき（ホームの深い場所に同期している場合など）は、アプリの右下の「設定」→「接続と保存場所」→「Synologyの保存場所」→「Takeoutが届くフォルダ」で、Cloud Syncのローカルパスと同じフォルダを選びます（人によって違うため、各自で設定）
3. ダウンロードが終わったZIPを、PhotoHubが10分以内に見つけて取り込みます。進み具合は、アプリの右下の「設定」→「Googleフォトからの移行」→「取り込みの状況」
   - 本番モード（手順5）では、ZIPがSynologyに写し終わった時点で、Cloud Syncのフォルダから元のZIPを消します（Cloud Syncが双方向なら、Googleドライブからも消えます）。取り込みがエラーなく終わると、Synology側のコピーも消します
4. PhotoHubが日時・位置を直し、すでにSynologyやスマホのバックアップにある写真は重複として1枚にまとめて取り込みます（手順5の確認を通ります）
5. 取り込みを確認できたら、Googleフォトの写真を本人が消す（**注意：Googleフォトのアプリで消すと、スマホの中の写真も一緒に消えます。** Synologyにあるので PhotoHub では見られます。スマホにも置きたい写真は、PhotoHubで選んで「このスマホに保存」）（アプリの「Googleフォトからの移行」→「Googleフォトを空にする」に、Synologyに保存済みの月が出ます）。ドライブの `Takeout` フォルダが残っていたら消してください（Cloud Syncが「ダウンロードのみ」の場合）。Takeout用にCloud Syncのタスクを作った場合は、そのタスクも削除

## 5. 確認してから本番にする（管理者）

最初は `PHOTOHUB_APPLY: "false"` なので、PhotoHubは**何も動かさず**、何をするかの記録だけを残します。

1. Takeoutが届いて1〜2時間たったら、アプリの右下の「設定」→「Googleフォトからの移行」→「取り込みの状況」を見る
   - 「確認のみ（dry-run）」の行に、新規・重複・失敗の件数が出ます
   - 夜中に、NASにある写真の索引（確認のみ）も1回行われます
2. コンテナの端末でも確認できます（Container Manager →「コンテナ」→ photohub →「詳細」→「端末」→「作成」→「bash」）。下の「端末で使うコマンド」を参照
3. 件数が想定どおりなら、Container Manager →「プロジェクト」→ photohub →「YAML設定」で `PHOTOHUB_APPLY: "true"` に変えて保存・再起動
4. 以後は自動で動きます
   - 10分ごと（とZIPの到着時）にTakeoutを取り込み
   - 起動の2分後と、その後3時間ごとにNASの写真を索引（試運転モードでも索引は保存され、ファイルの移動だけを待ちます）。初めてログインした人の写真フォルダは、ログイン直後に読み込みを始めます。4:00 にデータベースをバックアップ、5:10 に「Googleフォトから消してよい写真」を更新
   - 1時間ごとに空き容量・RAID・Takeoutの遅れを確認して通知

**端末で使うコマンド**（どれも、`--apply` を付けない限り確認だけです）

```sh
photohub status                        # 件数と直近の処理
photohub import-takeout --no-wait      # 届いているTakeoutを、確認だけで処理
photohub scan                          # NASの写真の索引を、確認だけで
```

**Googleフォトから消してよい写真**（アプリの右下の「設定」→「Googleフォトからの移行」→「Googleフォトを空にする」）：撮影から12か月以上たち、NASに保存されてハッシュが一致した写真・動画が、月ごとに出ます。**Googleからの削除は、本人がGoogleフォトのアプリで**行い、終わったら「削除した」を押します。
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
| ログインできない | Synology Photosに同じユーザー名・パスワードでログインできるか確認。10回失敗すると10分待ちます。「このアカウントでは使えません」と出る場合は、その人のアプリケーション権限で「DSM」が拒否になっていないか確認 |
| Takeoutが取り込まれない | ZIPが `photohub/inbox/takeout/<名前>/` に入っているか（直下は取り込みません）。「取り込みの状況」のエラーを見る |
| 写真がSynology Photosに出てこない | Synology Photos の設定 →「索引の再作成」 |
| 古い写真を消したい | `photohub/trash/` の中を30日たったら手で削除（PhotoHubは消しません） |
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
