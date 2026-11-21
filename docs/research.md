# P0 調査結果

調査日：2026-11-21 ／ 対象：[spec.md](spec.md) v0.1

各項目は「仕様書どおり」「仕様書と違う／追記が必要」「実機で確認が必要」のどれかを明記する。
**実機で確認が必要**な項目は、NASに接続できるようになった時点で確かめて、この文書を更新する。

---

## 0. まとめ（先に決めてほしいこと）

| # | 内容 | 影響 | 提案 |
|---|---|---|---|
| D1 | **Synology PhotosのWeb APIは非公開（文書なし）**。第7章の原則4「非公式APIは使わない」とぶつかる | F3のアルバム登録・再索引 | 自分のNAS上のSynology自身のAPIに限り、**アルバム作成・登録と再索引だけ**使ってよいことにする。使えなくても動くよう、DB＋XMPを正とする（→ §3） |
| D2 | **Dockerから置いたファイルは、Synology Photosに自動で索引されない可能性が高い** | F3の「正本に置けばSynology Photosに出る」前提 | 取り込み後に `SYNO.FotoTeam.Index reindex` を呼ぶ。D1が不可なら、DSMのタスクスケジューラで定期再索引（→ §3.3） |
| D3 | **OneDriveの容量**。Takeoutは最大50GBずつのZIPで届くが、初回の全件（最大2TB想定）がOneDriveに一度に入るとは限らない | F1の初回取り込み | Cloud Syncを双方向にし、PhotoHubが処理済みZIPを同期フォルダ外の `processed/` へ移すことでOneDrive側を空ける。初回は数回に分けて書き出す（→ §1.3） |
| D4 | **節約画質（圧縮）でGoogleにある写真・動画は、NASの原本とハッシュが一致しない** | F4の削除候補に永久に入らない | 類似判定（pHash＋撮影日時）で「対応あり」とみなす区分を足し、候補画面では別枠で示す（→ §5.2） |
| D5 | **Takeoutの「ゴミ箱」「アーカイブ」「ロックされたフォルダ」**の扱いが仕様にない | F1 | ゴミ箱は取り込まず `processed/` のZIPに残すだけ。アーカイブは通常どおり取り込む。ロックは取り込むが、アルバム「Locked」として別扱い（→ §2.4） |

---

## 1. Google（仕様書§2の確認）

### 1.1 Google Photos Library API — 仕様書どおり

- 2025年3月31日から、Library APIで一覧・取得できるのは**そのアプリが作成した写真とアルバムだけ**。`photoslibrary.readonly` などのスコープは廃止され、呼ぶと `403 PERMISSION_DENIED` になる。
- 残るのはアップロードとアルバム作成（`photoslibrary.appendonly`）と、利用者が1枚ずつ選ぶ Picker API。
- → 仕様書どおり、APIでの読み出しはしない。F6で「Googleの中身は表示しない」のも正しい。
- 出典：[Updates to the Google Photos APIs（公式）](https://developers.google.com/photos/support/updates)、[Google Developers Blog](https://developers.googleblog.com/en/google-photos-picker-api-launch-and-library-api-updates/)

### 1.2 Takeoutの差分書き出し — おおむね仕様書どおり。ただし公式の文書は未確認

複数の報道で一致している点：

- 2026年6月に「Incremental Takeout for Photos」として発表。**初回は全件、2回目以降は前回成功時から「追加・バックアップ・作成・編集」された分だけ**
- 頻度は**2か月ごと、1年間（計6回）**が上限
- **Googleフォトだけを選んだ書き出し**でのみ使える
- 段階的に提供中で、アカウントによってはまだ出ていない

**仕様書と違う／追記が必要な点**

- 差分には「**編集された写真**」も入る。編集版は画素が変わるので、F2では新しいmediaになり、pHashで元の写真の類似候補になる。この流れを仕様に明記するのがよい。
- **Google側で消した写真は、差分に「削除」として出てこない**（報道からはそう読める）。F4の `location(store=google)` を `removed` にする手段はない。「最後に見えた日時」（`seen_at`）だけを持つ。
- 1年で予約が切れるので、F5の「終了1か月前の通知」は必要（仕様書どおり）。
- **Google公式のヘルプページは見つからなかった。** 予約するときに、Takeoutの画面で表示を確認してもらう（→ §7 の手順）。

出典：[9to5Google](https://9to5google.com/2026/06/01/google-photos-schedule-export/)、[Android Authority](https://www.androidauthority.com/google-photos-incremental-takeout-3673297/)、[Android Police](https://www.androidpolice.com/google-photos-exports-finally-stop-wasting-bandwidth/)、[gHacks](https://www.ghacks.net/2026/06/02/google-photos-adds-incremental-exports-to-takeout-to-avoid-re-downloading-entire-libraries/)、[Digital Trends](https://www.digitaltrends.com/computing/google-photos-is-finally-making-backups-and-exports-a-tad-less-vexing/)、[winbuzzer](https://winbuzzer.com/2026/06/07/google-photos-makes-takeout-exports-recurring-backups-xcxwbn/)

### 1.3 届け先のOneDriveと容量 — 追記が必要（D3）

- Takeoutは1ファイル最大50GBに分割してOneDriveへ置く。OneDriveの空きが足りないと書き出しに失敗する。無料プランは5GB、Microsoft 365 Personalは1TB。
- Cloud Syncの同期方向で、OneDrive側の扱いが変わる：
  - **「リモートの変更のみダウンロード」**：NAS側で移動・削除してもOneDriveには残る。OneDriveが埋まっていく
  - **双方向**：PhotoHubがZIPを同期フォルダの外（`processed/`）へ移すと、OneDrive側からも消える。NASの `processed/` には残るので、写真は失われない
- **提案**：双方向にし、PhotoHubは「ハッシュで確認してから `processed/` へ移す」（原則2）。OneDriveの容量を自動で空けられる。初回の全件は、OneDriveの空きに収まるよう、年ごとなど数回に分けて書き出す。
- 実機で確認：Cloud Syncで双方向にしたときに、移動がOneDrive上で削除として扱われるか。サンプルのZIPで試す。

---

## 2. GPTH（Google Photos Takeout Helper）

### 2.1 どの版を使うか — 仕様書の追記

- 元の `TheLastGimbus/GooglePhotosTakeoutHelper` は v3.4.3 で止まっている。
- 開発が続いているのはフォーク版の **`Xentraxx/GooglePhotosTakeoutHelper_Neo`**。調査時点の最新は **v6.4.1（2026-11-13）**。ライセンスはApache 2.0。
- Dartで書かれた単一の実行ファイルで、Linux x64版が配られている。DS720+（x86_64）のコンテナで動く。
- 依存：ExifTool（必須。ない場合はJPEGだけ書き込める）、7-Zip（任意。ないと大きなZIPの解凍が遅い）。
- → コンテナに **GPTH Neo の固定版＋ExifTool＋7-Zip** を入れる。版はDockerfileで固定し、上げるときはサンプルで回帰テストする。

出典：[GooglePhotosTakeoutHelper_Neo](https://github.com/Xentraxx/GooglePhotosTakeoutHelper_Neo)、[元のリポジトリ](https://github.com/TheLastGimbus/GooglePhotosTakeoutHelper/releases)

### 2.2 PhotoHubでの使い方（P1で採用する起動方法）

```bash
gpth --input  /work/<job>/extracted \
     --output /work/<job>/gpth-out \
     --albums json \
     --keep-input \
     --keep-duplicates \
     --write-exif \
     --transform-pixel-mp still \
     --save-log
```

| オプション | 理由 |
|---|---|
| `--albums json` | `ALL_PHOTOS/` に1枚ずつ置き、アルバムの所属は `albums-info.json` に出る。PhotoHubのDB・XMPに取り込みやすい。リンクや複製を作らない |
| `--keep-input` | **GPTHは既定で入力を移動する**。入力に手を付けない（原則2・3） |
| `--keep-duplicates` | GPTHは既定で重複を消す。消さずに `_Duplicates/` に残し、判定はPhotoHubのF2に一本化する（原則1） |
| `--write-exif` | JSONの撮影日時・位置・説明（v6.4.0から）をEXIFに書く。既定で有効だが明示する |
| `--transform-pixel-mp still` | 要検討。Pixelのモーションフォト（.MP）の扱い。Live Photoのペア処理（第7章）と合わせてP1で決める |

**使ってはいけないオプション**：`--albums ignore`。アルバムにしかない写真を**ディスクから消す**と文書にある。

- 再開：GPTHは出力先の `progress.json` で途中から再開する。PhotoHubの「再実行で続きから」（原則5）と合う。
- 時刻：GPTHはJSONの時刻（UTC）を扱う。`--local-timezone` は使わず、PhotoHubのDBにUTCとオフセットを持つ（→ §5.3）。

### 2.3 確認が必要な点

- 出力先のEXIFがGPTHで書き換わるので、`file_hash` は取り込み後のファイルで計算する。`content_hash`（画素）は変わらないことを、P1のテストで確かめる。

### 2.4 特別なフォルダ（D5）

GPTHは `Archive` `Trash` `Locked Folder` を、アルバムの方式にかかわらず `Special Folders/<名前>/` に分ける。仕様書にこの扱いがない。提案は §0 D5 のとおり。

---

## 3. Synology Photos のWeb API（D1・D2）

### 3.1 公式の文書はない

- Synologyが公開しているのは File Station など一部のAPIガイドだけ。Synology Photos（`SYNO.Foto*`）のAPIは、公式の開発者文書がない。
- ただし、NAS上の定義ファイル（`/volume1/@appstore/SynologyPhotos/usr/webapi/SYNO.Foto.lib`、`SYNO.FotoTeam.lib`）にAPI名とメソッド名が載っている。`SYNO.Foto.*` は個人スペース、`SYNO.FotoTeam.*` は共有スペース。
- 有志のまとめ（[zeichensatz/SynologyPhotosAPI](https://github.com/zeichensatz/SynologyPhotosAPI)）の定義ファイルで確認したメソッド：

| API | メソッド | PhotoHubでの用途 |
|---|---|---|
| `SYNO.API.Auth` | `login` / `logout` | ログイン（専用のDSMユーザー） |
| `SYNO.Foto.Browse.NormalAlbum` | `create`, `add_item`, `delete_item`, `list`, `get` | F3 アルバムの作成と写真の登録 |
| `SYNO.Foto.Browse.Album` | `list`, `get`, `set_name`, `delete` | アルバムの一覧・突き合わせ |
| `SYNO.Foto.Index` / `SYNO.FotoTeam.Index` | `get`, `reindex` | D2 取り込み後の再索引 |
| `SYNO.Foto.Browse.Item` / `SYNO.FotoTeam.Browse.Item` | `list`, `get`, `get_exif` | 正本のファイルとSynology側IDの対応付け |
| `SYNO.Foto.Thumbnail` / `SYNO.FotoTeam.Thumbnail` | `get` | §4 のサムネイル流用の確認 |

- 引数は文書がない。**Synology PhotosのWeb画面で操作したときの通信を、ブラウザの開発者ツールで記録して確かめる**（実機で確認が必要）。
- 共有スペース側には `NormalAlbum` がない。アルバムは個人スペースの機能で、共有スペースの写真を入れられるかは実機で確認する。

### 3.2 方針の提案（D1）

- 原則4の「非公式API」は、Google・Amazonの非公開APIやCookieの流用を防ぐためのものと読める。**自分のNASで、Synology自身のWeb画面が使っているAPIを、専用ユーザーで呼ぶ**のは性格が違う。
- 提案：**アルバム作成・登録と再索引に限って使う。** 写真の移動・削除にはAPIを使わない（ファイル操作はPhotoHubが原則2の手順で行う）。
- Synologyの更新でAPIが変わったら、アルバムの登録は止めて、DBとXMPに残すだけにする（仕様書F3の代わりの手段どおり）。DSMを更新したら、まずアルバム登録をテストする。

### 3.3 索引（D2）— 実機で確認が必要

- Synology Photosは、File StationやSMBで置いたファイルは自動で索引するが、**DockerやSSHから直接書いたファイルは自動で拾われない**ことがある、と広く報告されている。
- 対策（優先順）：
  1. 取り込みジョブの最後に `SYNO.FotoTeam.Index reindex` を呼ぶ（対象フォルダを指定できるかを確認）
  2. D1が不可なら、DSMのタスクスケジューラで `synofoto` の再索引を夜間に実行
- P1で、サンプル写真をコンテナから置いて、Synology Photosに表示されるまでを確かめる。

---

## 4. サムネイルの流用（F7）— 実機で確認が必要

- Synology Photosは、写真ごとに `@eaDir/<ファイル名>/SYNOPHOTO_THUMB_{SM,M,XL}.jpg` を作る（一般に知られた構成。DSM 7.xの実機で確認）。
- 流用する場合の利点：HEIC・RAWのデコードをPhotoHubでしなくて済み、初回の処理が速い。
- 問題点：JPEGでWebPではない。大きさがSynologyの都合で決まる。生成のタイミングをPhotoHubから制御できない。
- **提案**：サムネイルとプレビューはPhotoHubで作る（仕様書どおり）。ただし、Synologyの `XL` があれば**それを元画像にしてWebPに変換**し、HEIC・RAWのデコードを省く。なければ原本から作る。P1で両方の速度を測って決める。

---

## 5. 仕様書の中で詰めておくべき点

### 5.1 画素ハッシュ（content_hash）の安定性

- HEIC・RAWは、デコードするライブラリや版で画素がわずかに変わることがある。そうなると同じ写真が別物に見える。
- 提案：
  - `media` に **`hash_version`** 列を足し、計算方法を版で管理する。方法を変えたら再計算できるようにする
  - JPEG・PNG・WebP・HEIC：デコードして、8bitのRGB(A)に揃えた画素の並び＋幅・高さでSHA-256
  - **RAW**：デコード結果が安定しないため、画素ハッシュは使わず、`file_hash` と「メタデータを除いたファイル本体」のハッシュで判定する
  - pillow-heif などの版は固定し、上げるときは回帰テストで確かめる

### 5.2 節約画質の写真（D4）

- Googleに「節約画質」で入っている写真・動画は、圧縮されていてNASの原本と画素が一致しない。
- 仕様書F4の条件「ハッシュが一致したもの」では、削除候補に入らない。
- 提案：`location` に `match`（`exact`／`similar`）を持たせ、pHashと撮影日時（±数秒）で一致したものを `similar` とする。削除候補の画面では「完全一致」と「節約画質（似ている）」を分けて示し、後者は人が数枚見てから確認済みにする。

### 5.3 時刻（taken_at）

- `taken_at` は **UTC** で保存し、撮影地の時差を `tz_offset`（分）として別の列に持つ。表示は端末のタイムゾーンで行う。
- TakeoutのJSONの時刻はUTC。EXIFの `DateTimeOriginal` は時差のない現地時刻なので、`OffsetTimeOriginal` があれば使い、なければ位置から推定、それもなければ +09:00 とする。
- NASのタイムゾーン（現在Seoul）はPhotoHubの記録に影響しないが、ログを読みやすくするためTokyoへの変更は勧める（仕様書どおり）。

### 5.4 Live Photo・モーションフォト

- TakeoutではiPhoneのLive Photoは `IMG_xxxx.HEIC` と `IMG_xxxx.MOV` の別ファイル、Pixelは `.MP`（JPEGの中に動画）になる。
- `media` に `pair_id` 列を足し、写真と動画を結び付ける。削除候補や類似の整理では、ペアをまとめて扱う。

---

## 6. Amazonフォト

- **プライム特典**：写真は無制限（フル解像度）、**動画などは5GBまで**。Amazon自身の2026年の案内でも同じ。→ 仕様書の「動画はAmazonに送らない」方針は正しい。
- **公開APIはない**（仕様書どおり）。SynologyのCloud Sync・Hyper BackupからAmazonへの接続は終了している（仕様書どおり）。
- **PC（Windows・Mac）のデスクトップアプリ**：フォルダを指定してバックアップする機能がある。2026年に終了するという公式の告知は見つからなかった（2026年6月に終わったのは「Kindle for PC」で、別の製品）。
- 実機で確認：デスクトップアプリから、NASの `amazon-outbox`（SMBで割り当てたネットワークドライブ）をバックアップ対象にできるか。ローカルのフォルダしか選べない場合は、PCにコピーする別の手順が要る。

出典：[About Amazon — Amazon Photos storage](https://www.aboutamazon.com/news/amazon-prime/how-to-use-amazon-photos-storage)、[Amazon Drive（Wikipedia）](https://en.wikipedia.org/wiki/Amazon_Drive)、[SynologyのAmazon接続終了](https://kb.synology.com/en-ca/DSM/tutorial/Discontinued_access_to_Amazon_Drive)

---

## 7. 人の手作業が必要なもの（手順）

### 7.1 Takeoutの定期書き出しを予約する

1. https://takeout.google.com を開き、「選択をすべて解除」→「Googleフォト」だけにチェック
2. 「次のステップ」で、配信方法をOneDrive、頻度を「2か月ごとに1年間」にする（表示名は画面に従う）
3. 「差分（前回以降に追加された分だけ）」の表示が出るか確認する。**出なければ、まだ提供されていないので知らせてほしい**
4. ファイル形式は ZIP、サイズは 50GB
5. 予約した日を知らせてほしい（F5の「終了1か月前の通知」に使う）

### 7.2 Cloud Sync（OneDrive → NAS）

1. DSMのパッケージセンターで Cloud Sync を入れる
2. 「作成」→ OneDrive → ログイン
3. ローカルのパス：`/photohub/inbox/takeout`、リモートのパス：Takeoutが書き出し先として作るフォルダ（初回の書き出し後にOneDriveで名前を確認する）
4. 同期方向：**§1.3の決定に従う**（提案は双方向）

### 7.3 PhotoHub用のDSMユーザー

1. コントロールパネル →「ユーザーとグループ」で `photohub` ユーザーを作る（管理者にしない）
2. 共有フォルダ `photo`（正本）と `photohub` に読み書き、それ以外はアクセスなし
3. Synology Photosの利用を許可する。2段階認証はこのユーザーだけ外すか、アプリ用の方法をP1で決める
4. パスワードはリポジトリに入れない。NAS上の `.env` にだけ書く

### 7.4 未決事項（仕様書§9）の情報

- Googleフォトの総容量と写真・動画の割合（Googleアカウント →「ストレージ」で確認できる）
- NASの空き容量とRAIDの構成（DSM →「ストレージ マネージャ」）
- Amazonアプリを動かすPCを常時起動できるか
- Googleに残す期間（既定12か月）
- 共有スペースか個人スペースか

---

## 8. P0 受け入れ基準の結果

| 項目 | 結果 |
|---|---|
| Synology Photos APIでできること（アルバム作成・登録、再索引） | 定義ファイル上は可能（`NormalAlbum.create/add_item`、`Index.reindex`）。引数と、共有スペースの写真を入れられるかは実機で確認が必要。方針はD1で決めてもらう |
| GPTHの最新版の使い方 | Neo v6.4.1。P1での起動方法を §2.2 に決めた |
| 調査結果を docs/research.md にまとめる | 本書 |

**P1へ進む前に必要なこと**：D1〜D5の決定。実機の確認（§1.3、§3.1、§3.3、§4、§6）はP1の中で、サンプルのTakeoutを使って行う。
