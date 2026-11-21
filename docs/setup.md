# PhotoHub 導入手順

この順に進めれば、本物の写真に触れる前に、必ず確認（dry-run）を通るようになっています。

**家族で使う前提**です。共有スペース（`/volume1/photo`）の写真は家族全員が、各自の個人スペース（`/volume1/homes/<名前>/Photos`）の写真は本人だけが、アプリで見られます。Googleフォトは一人ずつ別のアカウント、Amazonフォトは家族で1アカウントです。
**手順4の確認が終わるまで `PHOTOHUB_APPLY=false` のまま**にしてください。

---

## 1. NASの準備（DSMの画面で行う）

1. **タイムゾーン**：コントロールパネル →「地域のオプション」→ タイムゾーンを Tokyo に変更（推奨。PhotoHubの記録はUTCなので動作には影響しません）
2. **共有フォルダ**：コントロールパネル →「共有フォルダ」で `photohub` を作成（`photo` はSynology Photosの共有スペースとして既にある想定）
3. **ユーザー**：「ユーザーとグループ」で `photohub` を作成（管理者にしない）
   - `photo` と `photohub` に読み書き
   - `homes` に読み書き（家族の個人スペースの写真を索引し、本人のTakeoutを本人の個人スペースへ置くため）
   - アプリケーションの権限で Synology Photos を許可
   - 家族それぞれのDSMユーザー名（例：reo、chie）を控えておく。PhotoHubのユーザー名を同じにすると、個人スペースが自動で決まります
4. **Container Manager**：パッケージセンターでインストール
5. **SSH**（ID確認用、一時的でよい）：コントロールパネル →「端末とSNMP」→ SSHを有効化し、`id photohub` で uid / gid を控える

## 2. OneDrive と Cloud Sync（一人ずつ）

Googleのアカウントが人ごとに別なので、Cloud Syncのタスクも**一人に1つ**作ります。

1. パッケージセンターで **Cloud Sync** を入れる
2. 「作成」→ その人の OneDrive → ログイン
3. ローカルのパス：`/photohub/inbox/takeout/<その人の名前>`（例：`/photohub/inbox/takeout/chie`）
4. リモートのパス：Takeoutの書き出し先フォルダ（初回の書き出しが届いた後に、OneDrive上の名前を確認して設定し直す）
5. 同期方向：**双方向**（D3。PhotoHubが処理済みのZIPを `processed/` へ移すと、OneDrive側からも消えて容量が空く。NASの `processed/` には残る）
6. 初回は、まずサンプル程度の小さなZIPで、移動がOneDrive側で削除として扱われるかを確かめる

## 3. PhotoHub をNASで動かす

1. このリポジトリをNASの `/volume1/docker/photohub` に置く（File Stationでアップロード、または `git clone`）
2. `.env.example` を `.env` にコピーし、次を入れる
   - `PUID` / `PGID`：手順1-5で控えた値
   - `NTFY_TOPIC`：通知を受けるトピック名（ntfyアプリで同じ名前を購読）
   - `PHOTOHUB_APPLY=false` のまま
3. Container Manager →「プロジェクト」→「作成」→ パス `/volume1/docker/photohub`、`docker-compose.yml` を選んで作成・起動
4. ブラウザで `http://NASのIP:8765/api/health` を開き、`{"ok":true,...,"apply":false}` が出ればよい
5. **家族を登録**（コンテナの「端末」で）：

   ```sh
   photohub user add reo --admin      # 管理者（共有スペースの整理ができる）
   photohub user add chie             # 家族（閲覧と、自分の分の整理）
   photohub user list
   ```

   - 表示される **トークンはその人のアプリに入れるもの**で、一度しか表示されません（なくしたら `photohub user token chie` で再発行）
   - 名前はDSMのユーザー名と同じにします。個人スペースは `/data/homes/<名前>/Photos` になります（違う場所なら `--personal-root`）
   - その人のTakeoutを共有スペースに入れたい場合は `--takeout-to shared`

## 4. 初回の確認（dry-run）— 本物の写真で必ず行う

Container Manager → コンテナ `photohub` →「端末」（または `docker exec -it photohub sh`）で：

```sh
# 4-1 サンプルで一通り試す（本物の写真には触れない）
photohub make-sample /tmp/sample/takeout-sample-001.zip -n 100
PHOTOHUB_ROOT=/tmp/try PHOTO_LIBRARY_ROOT=/tmp/try-photo photohub init
cp /tmp/sample/*.zip /tmp/try/inbox/takeout/
PHOTOHUB_ROOT=/tmp/try PHOTO_LIBRARY_ROOT=/tmp/try-photo photohub import-takeout --no-wait --apply
PHOTOHUB_ROOT=/tmp/try PHOTO_LIBRARY_ROOT=/tmp/try-photo photohub status

# 4-2 既存の正本を索引する（読むだけ。確認のみ）
photohub scan
# 4-3 届いているTakeoutを確認のみで処理
photohub import-takeout --no-wait
```

- 出力の `new / duplicate / merged / error` の件数と、`reports/duplicates-latest.tsv`（重複の一覧）を見て、想定どおりか確認する
- Takeoutは `inbox/takeout/<名前>/` に置かれたものだけを取り込みます。`inbox/takeout/` の直下に置いたZIPは、誰のものか分からないので取り込みません
- **結果を確認してもらってから**、次に進む

## 5. 本実行に切り替える

1. 索引だけ先に本実行：`photohub scan --apply`（写真は動かさない。DBに登録するだけ）
2. Takeoutの取り込み：`photohub import-takeout --no-wait --apply`
3. 問題がなければ `.env` の `PHOTOHUB_APPLY=true` にしてプロジェクトを再起動。以後は自動で：
   - 10分ごと（とZIPの到着時）に取り込み
   - 毎晩 2:30 に正本の走査、5:10 に保存先の確認・Amazon用フォルダ・削除候補の更新、4:00 にDBのバックアップ
   - 1時間ごとに空き容量・RAID・Takeoutの遅れ・Amazonの同期を確認して通知

## 6. Takeout の定期書き出し（一人ずつ、それぞれのGoogleアカウントで）

1. 本人のGoogleアカウントで https://takeout.google.com →「選択をすべて解除」→「Googleフォト」だけ選ぶ
2. 配信方法：OneDrive、頻度：「2か月ごとに1年間」（表示名は画面に従う）、形式 ZIP、サイズ 50GB
3. 「差分（前回以降の分だけ）」の表示が出るか確認（出なければ未提供の可能性）
4. 予約した日を登録する：`photohub user set chie --takeout-schedule-start 2026-12-01`（1年で終わる1か月前に、その人の分の通知が来る）
5. 初回の全件がOneDriveの空きより大きい場合は、期間を区切って何回かに分ける

## 7. Amazonフォト（写真の控え）

1. 常時起動できるPCに Amazon Photos デスクトップアプリを入れ、ログイン
2. NASの `photohub` 共有フォルダをネットワークドライブとして割り当て、`amazon-outbox` をバックアップ対象に追加
   - ネットワークドライブを選べない場合は、PCのローカルフォルダに同期する手段（Synology Drive Client など）で `amazon-outbox` をPCへ写し、そのフォルダを対象にする
3. `tools/amazon-heartbeat.ps1` をPCに置き、NASのアドレスと**管理者のトークン**を書き換えて、タスクスケジューラで1時間ごとに実行
   - `amazon-outbox/shared/`（共有スペース）と `amazon-outbox/<名前>/`（各自の個人スペース）の両方が、家族の1つのAmazonアカウントへ送られます
   - これで「Amazonアプリが動いた時刻」がNASに届き、写真が「送信済みの見込み」になる
4. `amazon-outbox` はハードリンクなので、NASの容量は増えません

## 8. Androidアプリ

1. `android/` をビルド（`./gradlew assembleRelease`。署名鍵は `~/.gradle/gradle.properties` か環境変数で渡す）するか、配布されたAPKを入れる
2. **家族それぞれのスマホで**起動 → 設定で次を入れて「接続テスト」（「◯◯さんとして接続できました」と出る）
   - 自宅のアドレス：`http://NASのIP:8765`
   - 外出先のアドレス：Tailscale の NAS のアドレス `http://100.x.y.z:8765`
   - **その人のトークン**（手順3-5で発行したもの）、自宅のWi-Fi名（「今のWi-Fi」で取得）
   - 1台のスマホを別の人が使うときは、トークンを入れ替えると、前の人の写真の索引とサムネイルは端末から消えます
3. 「管理」→「今すぐ同期」で初回同期（自宅のWi-Fiで）。以後は自宅のWi-Fiで充電中に自動
4. 既定のギャラリーにする：Androidの設定 → アプリ → 既定のアプリ → 写真を開くアプリ で「写真ハブ」

## 8b. アプリの自動更新

新しい版は**NASから**家族のスマホへ配ります（外部には公開しません）。

**初回だけ**
1. 各スマホに `photohub-0.3.0.apk` を入れる。0.2.0以前（テスト用の鍵で署名）を入れていた場合は、**一度アンインストールしてから**入れる（署名の鍵が変わったため。以後は不要）
2. アプリの「管理」→「アプリの更新」で「許可の設定を開く」→「この提供元のアプリを許可」をオン
3. 通知の許可を求められたら許可する

**新しい版を配るとき（管理者）**
1. `android/app/build.gradle.kts` の `versionCode` を1つ上げ、`versionName` を変え、`android/app/release-notes.txt` に変更点を書く
2. リリース用の鍵を指定して `./gradlew publishUpdate` を実行（鍵は下記）。`dist/photohub-<版>.apk` と `.json` ができる
3. その2つを File Station で `/volume1/photohub/app-updates/` に置く（`photohub app list` で確認できる）

**スマホ側の動き**
- 12時間ごと（Wi-Fiのとき）に確認し、新しい版があればダウンロードして、そのままインストール
- Android 12以降は、2回目からは確認なしで更新されます。それ以外は通知が出るので、タップして「更新」を押す
- アプリを開いたときにも確認し、上のバーに更新のアイコンが出ます。「管理」→「アプリの更新」から手動でも更新できます
- 設定の「新しい版をWi-Fiで自動的に取り込んで更新する」をオフにすると、通知だけになります
- 入れる前に、ファイルのハッシュ・アプリ名・新しい版であること・**署名の鍵がインストール済みのアプリと同じこと**を確かめます。どれかが違えば入れません

**リリース用の鍵（とても大事）**
- `photohub-release.jks`（鍵）と `photohub-release-key.txt`（パスワード）は、**なくすと二度と更新を配れません**（全員がアンインストールして入れ直しになります）
- リポジトリには入れず、パスワード管理アプリやUSBメモリなど2か所に保管する
- ビルド時は環境変数で渡す：`PHOTOHUB_KEYSTORE=/path/photohub-release.jks PHOTOHUB_KEYSTORE_PASSWORD=... PHOTOHUB_KEY_PASSWORD=... ./gradlew publishUpdate`

## 9. Tailscale（外出先からの接続）

1. NASのパッケージセンターで Tailscale を入れてログイン
2. 家族それぞれのスマホにも Tailscale を入れ、NASと同じTailnetに参加させる（Tailscaleの管理画面から家族を招待するか、共有アカウントでログイン）
3. Tailscale管理画面でNASのアドレス（100.x.y.z）を確認し、アプリの「外出先のアドレス」に入れる
4. ルーターのポート開放はしない（APIをインターネットに出さない）

## 10. Synology Photos のアルバム登録（任意・D1）

1. `.env` に `DSM_URL` `DSM_USER` `DSM_PASSWORD` を入れる（`SYNOLOGY_API_ENABLED=false` のまま）
2. `photohub synology probe` を実行し、APIの一覧とフォルダが出るか確認
3. 出力を共有してもらえれば、引数を実機に合わせて調整します。確認後に `SYNOLOGY_API_ENABLED=true`
4. 使えない場合も、アルバムはPhotoHubのDBと各写真のXMPに残ります
5. Synology Photosのアルバムは人ごとの持ち物で、PhotoHubは `photohub` ユーザーとしてしか作れません。そのため登録するのは**共有スペースの写真のアルバムだけ**です。各自のGoogleアルバムは、アプリの「アルバム」で本人にだけ表示されます

## 困ったとき

- `photohub status`：件数と直近の処理
- アプリの「管理」→「取り込み履歴」：失敗したファイルと理由
- `trash/` の写真は30日たったら人が削除（PhotoHubは消しません）
- DBが壊れた場合：`db/backup/` から戻すか、`photohub scan --apply` で写真とXMPから作り直せます
