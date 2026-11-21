# 写真ハブ（PhotoHub）仕様書 v0.3

2026-11-21 · REO（v0.1）／ v0.2 は実装時の決定を第10章に追記

> 原本：Google ドキュメント「写真ハブ（PhotoHub）仕様書 v0.1」。このファイルはその写しで、変更したらここを更新する（第9章の指示1）。
> P0 調査で分かった仕様との差異は [research.md](research.md) にまとめている。

## 1. 概要

PhotoHubは、Synology NAS上で動く写真の取り込み・整理サービスと、外出先でも速く写真を見られるAndroidアプリです。Synology Photosを写真の正本の置き場にして、Googleフォトからの取り込み、重複の除去、控えの作成を自動で行います。

**解決する課題**

- Googleフォトの容量が増え、費用がかさんでいる
- スマホ・Googleフォト・NASに同じ写真が重複している
- 写真が3か所に分散し、どこに何があるか分からない

**基本方針**

- 閲覧は自前のアプリで行う。Synology Photosのアプリは外出先（Wi-Fi外）では遅いため、サムネイルを端末に持ち、通信せずに一覧できるようにする
- PhotoHubは、取り込み・重複除去・移行の管理と、その閲覧アプリを担う
- 公式に許された経路だけを使う。非公式APIや画面の自動操作は使わない
- 写真の削除は自動で行わない。削除候補を出し、人が確認してから行う

## 2. 前提と外部の制約

Google・Amazonとも、アプリから既存のライブラリを自由に読み書きできません。そのため、設計は公式に使える経路を前提にします。

| サービス | 制約 | この設計での扱い |
| --- | --- | --- |
| Googleフォト | 2025年3月31日以降、Library APIではアプリが作成した写真しか一覧・取得できない | APIでの読み出しはしない |
| Google Takeout | 2026年6月から、初回は全件、以後は差分だけを2か月ごとに1年間書き出せる。届け先にDropbox・OneDrive・Boxを選べる | Googleからの唯一の取り込み経路にする |
| Amazonフォト | 公開APIがない。2020年11月からSynologyのCloud SyncとHyper Backupでは接続できない。プライム特典は写真無制限、動画5GBまでと紹介されている | 写真の控え先に限る。送信はPCの公式アプリに任せ、PhotoHubは状態の確認だけ行う |
| Synology Photos | 複数アルバムへの登録、似た写真のまとめ、スマホへのキャッシュ、自動バックアップに対応 | 正本・閲覧の場にする |
| Synology Cloud Sync | DropboxやOneDriveなどとNASを同期できる | Takeoutの書き出しをNASへ運ぶ |

仕様は変わりやすいので、実装前に各社の最新の公式情報で確認する。特にAmazonの動画の上限は古い情報なので、確認が必要。

出典：[Google Photos APIの変更](https://developers.google.com/photos/support/updates) · [Takeoutの差分書き出し](https://winbuzzer.com/2026/06/07/google-photos-makes-takeout-exports-recurring-backups-xcxwbn/) · [Takeoutの届け先](https://support.google.com/accounts/answer/3024190) · [SynologyのAmazon接続終了](https://kb.synology.com/en-ca/DSM/tutorial/Discontinued_access_to_Amazon_Drive) · [Amazonフォトの特典](https://selectra.jp/streaming/amazon-prime/photos) · [Synology Photos DSM 7.4仕様](https://www.synology.com/en-us/dsm/7.4/software_spec/synology_photos)

## 3. 全体構成

NAS上のSynology Photosを正本にし、Googleフォトは直近の閲覧用、Amazonフォトは写真の控えにします。

スマホの写真はGoogleとNASの両方へ自動で入り、Googleにしかない写真はTakeout経由でPhotoHubがNASへ取り込みます。写真の控えはAmazonへ送ります。動画はGoogleからNASへ移し、NASだけで保持します。

```
スマホ ──自動バックアップ──▶ Googleフォト ──Takeout──▶ OneDrive ──Cloud Sync──▶ NAS inbox
   │                                                                               │
   └──Synology Photos 自動バックアップ──▶ NAS 正本（Synology Photos） ◀──PhotoHub──┘
                                              │
                                              ├──amazon-outbox（写真のみ）──PCのAmazonアプリ──▶ Amazonフォト
                                              └──差分API／サムネイル──▶ PhotoHub Androidアプリ
```

## 4. 機能要件

機能は7つで、F1〜F5とF7がNAS側のサービス、F6がAndroidアプリです。

### F1. Takeoutの取り込み

1. Cloud Syncが同期する受け取りフォルダ（例：`/volume1/photohub/inbox/takeout`）を監視する
2. ZIPが届いたら、サイズが一定時間変わらないことを確認してから処理を始める（同期途中のファイルを開かないため）
3. 作業フォルダに解凍する。分割されたZIPはまとめて扱う
4. GPTHでJSONの情報（撮影日時・位置・説明）を写真に書き込み、アルバム情報を取り出す
5. F2の重複判定を通し、新しい写真だけを正本フォルダへ移す
6. 処理がすべて成功したら、元のZIPを「処理済み」フォルダへ移す（削除はしない）

初回の全件書き出しも、差分の書き出しも、同じ処理で扱う。同じZIPを二度処理しても結果が変わらないようにする。

### F2. 重複の検出

- **完全一致**：画素データのSHA-256で判定する。EXIFを書き換えるとファイル全体のハッシュが変わるため、メタデータを除いた部分で計算する。ファイル全体のハッシュも別に記録する
- **類似**：知覚ハッシュ（pHash）で、編集版や縮小版を見つける。自動ではまとめず、候補としてF6に出す
- **動画**：~~ファイル全体のSHA-256で判定する~~ → v0.2：映像・音声の中身（ストリーム）のSHA-256で判定する。GPTHが日時を書き込むとファイル全体のハッシュが変わるため（第10章）
- 完全一致の場合は、正本にある方を残し、新しい方は取り込まない。ただし、新しい方にしかない情報（位置・アルバム）は正本の記録に足す

### F3. 正本への配置

- 正本フォルダは、Synology Photosが読む共有スペース配下に置く
- フォルダ構成は `YYYY/YYYY-MM/`。ファイル名が衝突したら、末尾にハッシュの先頭8文字を付ける
- アルバムは、可能ならSynology PhotosのAPIで作成・登録する。できない場合は、PhotoHubのDBとXMPに保存する
- スマホからSynology Photosへの自動バックアップで入った写真も、定期的に走査してDBに登録する

### F4. 保存先の状態管理と削除候補

- 各写真について、NAS・Google・Amazon・外部バックアップのどこにあるかを記録する
- Googleにあるかは、Takeoutに含まれていたかどうかで判断する
- Amazonにあるかは直接確認できない。控え用フォルダに置いた日時と、PCのAmazonアプリの同期がいつ動いたかで「送信済みの見込み」として扱う
- 撮影から一定期間（既定12か月）たち、NASに保存されてハッシュが一致したものを、Googleから消してよい候補にする。写真はAmazon用フォルダへの配置も条件にする。動画はNASだけに残ることを、候補の画面に明記する
- 候補の一覧は、日付の範囲と枚数で示す。実際の削除は、人がGoogleフォトのアプリで行う

### F5. 通知

- Takeoutの取り込みの成功・失敗
- 定期書き出しの予約がもうすぐ終わる（1年で終わるため、終了の1か月前）
- 予定の時期を過ぎてもTakeoutが届かない
- NASの空き容量が少ない、NASのディスクやRAIDに異常がある、Amazonの同期が一定期間動いていない

### F6. Androidアプリ（閲覧と管理）

端末のギャラリーアプリの代わりに使える閲覧アプリにする。**一覧・検索・地図は端末内のデータだけで表示し、外出先ではNASと通信しない**のが設計の中心。

**閲覧**

- **タイムライン**：端末内の写真（MediaStore）とNASの写真を、撮影日時順に1つに並べる。同じ写真はハッシュで1枚にまとめ、保存先のバッジ（端末・NAS・Google・Amazon）を付ける
- **アルバム・地図・検索**：日付、場所、アルバムで絞り込む。端末内のDB（Room）だけで動かす
- **Googleフォトの中身は表示しない**（APIの制限のため）。直近の写真は端末にもあるので、それで代わりにする
- **写真を開く**：端末にあれば端末から、なければキャッシュ済みのプレビューから表示する。どちらもなければ、その時だけNASからプレビューを取り寄せる
- **原本**：共有や保存のときだけ取り寄せる。モバイル回線では、サイズを示して確認する
- **動画**：サムネイルだけ事前に持ち、再生はその時にストリーミングする。モバイル回線では確認を出す
- **既定のギャラリーとして動く**：他のアプリから写真を開く操作、共有の操作に対応する

**端末への同期**

- 自宅のWi-Fi（設定したSSID）にいて充電中のとき、WorkManagerでF7の差分を取り込む
- 取り込むもの：全件のメタデータとサムネイル。プレビューは、直近の期間・お気に入り・指定したアルバムの分だけ
- 端末で使う容量の上限を設定できる。目安は、サムネイル1枚20KB前後として10万枚で約2GB
- 接続先は、自宅ではLANのアドレス、外出先ではTailscale経由を自動で切り替える

**管理画面**

- **ホーム**：写真・動画の総数、保存先ごとの件数、最後の取り込み日、最後の同期日、重複の件数
- **取り込み履歴**：各処理の件数（新規・重複・失敗）とエラー内容
- **類似の候補**：グループごとにサムネイルを並べ、残す写真を選ぶ。残さない写真はNAS上の「ゴミ箱」フォルダへ移す
- **Google削除の候補**：期間と枚数、確認済みにする操作

### F7. サムネイルと差分の配信（NAS側）

- 取り込んだ写真・動画ごとに、サムネイル（長辺320px、WebP）とプレビュー（長辺1600px、WebP）を作る。Synology Photosが作るサムネイルを流用できるかは、P0で調べる
- 差分同期API：端末が前回の位置（カーソル）を渡すと、それ以降に追加・変更・削除された写真を返す
- サムネイルは、数百枚ずつまとめたパックで返し、通信回数を減らす
- プレビューと原本は、1枚ずつ取得できるようにする。端末のキャッシュが効くように、内容のハッシュをURLに含める

## 5. データモデル

写真の同一性は画素ハッシュで決め、サービスごとのIDやファイル名には依存しません。DBはSQLiteで、NAS上に置き、毎日バックアップします。

| テーブル | 主な列 | 役割 |
| --- | --- | --- |
| media | id, content_hash（画素）, file_hash, phash, kind（photo/video）, taken_at, lat, lon, width, height, bytes, nas_path, status（active/trashed）, created_at | 写真1枚につき1行。content_hashに一意制約 |
| location | media_id, store（nas/google/amazon/offsite/phone）, state（present/assumed/removed）, source_ref, seen_at | 写真がどこにあるか |
| album | id, name, origin（google/synology/manual）, synology_album_id | アルバム |
| album_item | album_id, media_id | 写真とアルバムの対応（多対多） |
| similar_group | id, status（open/resolved） | 類似写真のグループ |
| similar_member | group_id, media_id, keep（bool） | グループの写真と、残すかどうか |
| import_job | id, source（takeout/phone_scan）, archive_name, started_at, finished_at, new_count, dup_count, error_count, state | 取り込みの履歴 |
| import_item | job_id, original_path, media_id, result（new/duplicate/merged/error）, message | 取り込みの写真ごとの結果 |
| event | id, at, level, kind, message | 通知と監査ログ |

**サイドカー**：アルバムや説明は、写真と同じ名前のXMPファイルにも書き出す。DBが壊れても、写真とXMPから作り直せるようにする。

## 6. 技術構成と配置

NAS側はPythonのサービスをDockerコンテナで動かし、Android側はKotlinで作ります。

| 役割 | 技術 |
| --- | --- |
| 実行環境 | DS720+（Intel x86、メモリ10GB）のContainer Manager（Docker Compose） |
| サービス本体 | Python 3.12、FastAPI（状態取得のREST API） |
| 定期処理・フォルダ監視 | APScheduler、watchdog（ポーリングも併用） |
| DB | SQLite（WALモード）、SQLAlchemy、Alembic |
| Takeoutの情報統合 | GPTH（Google Photos Takeout Helper）、ExifTool |
| ハッシュ | hashlib（SHA-256）、Pillow、pillow-heif、imagehash（pHash） |
| 動画の情報 | ffprobe |
| Synology連携 | Synology PhotosとDSMのWeb API（対応範囲は要調査） |
| 通知 | ntfyなどのプッシュ通知、またはSynologyの通知機能 |
| Androidアプリ | Kotlin、Jetpack Compose、Room（端末内の索引）、WorkManager（同期）、Retrofit、Coil（画像表示）、Media3（動画再生）、地図はOpenStreetMap系 |
| 外からの接続 | TailscaleなどのVPN。APIをインターネットに直接公開しない |

**フォルダ構成（例）**

```
/volume1/photohub/
  inbox/takeout/     Cloud Syncの受け取り先
  work/              解凍・処理中
  processed/         処理済みのZIP
  trash/             残さないと決めた写真（30日後に人が削除）
  db/                SQLiteとその日次バックアップ
/volume1/photo/      Synology Photosの共有スペース（正本）
  YYYY/YYYY-MM/
/volume1/photohub/amazon-outbox/   PCのAmazonアプリが同期する控え用（写真のみ）
```

コンテナには、正本フォルダを書き込み可で、それ以外は必要なフォルダだけをマウントする。

## 7. 非機能要件と安全の原則

最優先は「写真を1枚も失わないこと」です。速さや機能よりも優先します。

**安全の原則**

1. 自動では削除しない。消す候補は `trash/` へ移すだけで、本当の削除は人が行う
2. 移動は「コピー → ハッシュで確認 → 元を移す」の順にする。確認に失敗したら、元には触らない
3. 正本フォルダの既存の写真は、上書きも書き換えもしない。情報の追加はDBとXMPで行う
4. 非公式API、Cookieの利用、画面の自動操作は使わない
5. どの処理も、何度実行しても同じ結果になるようにする。途中で止まっても、再実行で続きから進める
6. すべての処理に `--dry-run` を用意し、何が起きるかを先に確認できるようにする

**その他の要件**

- **規模**：10万枚・2TBを想定する。初回のハッシュ計算は数日かかってもよいが、中断しても続きから進める
- **負荷**：NASの他の処理を妨げないよう、同時処理数と実行時間帯を設定できるようにする
- **認証**：APIはトークンで保護する。秘密情報は環境変数か、リポジトリ外の設定ファイルに置く
- **ログ**：移動と削除候補の操作は、すべて event に記録する
- **ファイル形式**：JPEG、HEIC、PNG、WebP、主なRAW、MP4、MOV。Live Photoやモーションフォトの写真と動画はペアで扱う

## 8. 開発の段階と受け入れ基準

各段階は、前の段階の受け入れ基準を満たしてから始めます。

| 段階 | 内容 | 受け入れ基準 |
| --- | --- | --- |
| P0 調査 | Synology Photos APIでできること（アルバム作成・登録、再索引）を調べる。GPTHの最新版の使い方を確認する | 調査結果を docs/research.md にまとめる |
| P1 取り込み（CLI） | F1・F2・F3をコマンドで実行できるようにする | サンプルのTakeout（100枚程度）で、日時と位置が元と一致する。同じZIPを2回入れても、写真が増えない |
| P2 既存写真の索引 | NASの正本を走査してDBに登録し、重複の報告を出す | 全件の走査が中断・再開できる。報告の件数が手作業の抽出チェックと合う |
| P3 常駐化 | Docker化し、フォルダ監視・定期処理・通知（F5）を動かす | Takeoutを受け取りフォルダに置くと、人の操作なしで取り込まれ、通知が届く |
| P4 保存先の管理 | F4（保存先の記録、Amazon用フォルダへの配置、Google削除の候補） | 候補には、NASに保存されてハッシュが一致した写真・動画しか入らない |
| P5 Androidアプリ | F6 | 機内モードでも、全件のタイムライン・検索・地図が表示できる。モバイル回線で、未取得のプレビューが数秒で開く。管理画面が実機で動く |

**テスト**：日時・位置の統合、重複判定、ファイル名の衝突、途中で止まった場合の再実行について、自動テストを書く。本物の写真を使う前に、必ずサンプルで確認する。

## 9. 未決事項とClaude Codeへの指示

**決定事項**

| 項目 | 決定内容 |
| --- | --- |
| NAS | DS720+、メモリ10GB。Container ManagerでDockerを動かせる |
| Takeoutの届け先 | OneDrive |
| 写真の控え | Amazonフォト（PCのアプリ経由） |
| 動画 | GoogleからNASへ移し、NASだけで保持する。外部の控えは作らない |

NASのタイムゾーンはSeoulになっている。時差は東京と同じだが、記録を分かりやすくするため、Tokyoに変えることを勧める。

**未決事項**

- [ ] Googleフォトの総容量と、写真と動画の割合
- [ ] NASの空き容量とRAIDの構成（動画の唯一の保存先になるため）
- [ ] Amazonアプリを動かすPCを常時起動できるか
- [ ] 「直近」としてGoogleに残す期間（既定12か月）
- [ ] Synology Photosで使うのは共有スペースか、個人スペースか（家族で共有するか）

**Claude Codeへの指示**

1. この仕様書を docs/spec.md としてリポジトリに置き、変更したら更新する
2. P0から順に進め、各段階の終わりに受け入れ基準の結果を報告する
3. 第7章の安全の原則は必ず守る。特に、本物の写真に対しては、初回は必ず `--dry-run` で実行し、結果を確認してもらってから本実行する
4. Google・Amazonの仕様は変わりやすい。実装前に公式情報を確認し、この仕様書と違う点があれば先に報告する
5. 環境設定やデプロイは、できるだけClaude Code側で完結させる。人の手作業が必要なもの（Takeoutの予約、DSMの設定画面での操作など）は、手順を箇条書きで示す
6. 秘密情報（DSMのパスワード、APIトークン）をコミットしない。.env.example だけを置く

## 10. v0.2 での変更（実装時の決定）

P0調査（[research.md](research.md)）の提案D1〜D5をすべて採用し、実装で次のとおり決めた。

| 項目 | 決定 |
| --- | --- |
| D1 Synology PhotosのAPI | 自分のNAS上で、アルバム作成・登録と再索引に限り使う。既定は無効（`SYNOLOGY_API_ENABLED=false`）。実機で `photohub synology probe` を確かめてから有効にする。アルバムは常にDBとXMPが正 |
| D2 索引 | 取り込み後に `SYNO.FotoTeam.Index reindex` を呼ぶ（D1が有効な場合） |
| D3 OneDrive | Cloud Syncは双方向。処理済みZIPを `processed/` へ移すとOneDrive側が空く |
| D4 節約画質 | 撮影時刻±2秒かつpHashが近い（動画は長さ±1秒）かつ小さいファイルは「Googleの圧縮コピー」とみなし、正本には入れず `google-similar/` に保管。location.match=similar。削除候補では別枠 |
| D5 特別なフォルダ | ゴミ箱→ `google-trash/`、ロック→ `google-locked/`（どちらも正本外に保管）。アーカイブ→通常どおり取り込み、アルバム「Google: Archive」 |
| 画素ハッシュ | `hash_version=1`。8bit RGB(A)に正規化した画素＋大きさ。RAWはファイル全体のハッシュ（`f:` 付き） |
| 動画ハッシュ | ffmpegのstreamhash（容器のメタデータを除く）。取れない場合はファイル全体 |
| 時刻 | `taken_at` はUTC、`tz_offset` は分。GPTHはTZ=UTCで実行し、EXIFに `+00:00` で書かせる |
| Live Photo | `pair_key` で写真と動画を結ぶ |
| 端末とNASの対応 | 端末の写真はファイル全体のSHA-256でNASの `file_hash` と照合（AndroidとPillowで画像デコード結果が違うため） |
| Amazonの送信 | 写真を `amazon-outbox/` にハードリンク（容量は増えない）。PCのスクリプトが送る「Amazonアプリが動いた時刻」より後なら送信済みの見込み（queued→assumed） |
| 削除候補の条件 | 12か月以上前、NASのファイルを90日以内にハッシュで再確認済み、Googleにある、写真はAmazon送信済みの見込み |
| 本実行の切り替え | CLIは `--apply` がない限り確認のみ。常駐サービスは `PHOTOHUB_APPLY=true` のときだけファイルを動かす |
| 家族での利用 | 一人ずつユーザー（`photohub user add`）とトークン。共有スペースの写真は全員、個人スペース（`/volume1/homes/<user>/Photos`）の写真は本人だけが見る。同じ写真を自分のGoogleにも持つ人には、相手の個人スペースの写真も見せる（`media_access`） |
| Googleは一人ずつ | Takeoutは `inbox/takeout/<user>/` に届く。取り込み先は既定で本人の個人スペース（`<個人スペース>/PhotoHub/YYYY/YYYY-MM/`）。Googleの保存先・アルバム・削除候補・通知（予約終了、遅れ）は人ごと |
| 整理の権限 | 共有スペースの類似写真の整理、全員の取り込み履歴、イベント、Amazonの確認は管理者だけ。個人スペースの類似写真は本人だけが整理できる（管理者も他人の個人スペースは見えない） |
| Amazon | 家族で1アカウント。`amazon-outbox/shared/` と `amazon-outbox/<user>/` に分けて置く |
| 端末の利用者の交代 | アプリでトークンを変えると、前の人の索引・サムネイル・プレビューを端末から消す |
| アプリのログイン | 自宅のWi-FiでNASを自動で見つけ（mDNS `_photohub._tcp`、だめなら同じネットワークを順に確認）、DSMのユーザー名・パスワード（2段階認証のコード）でログイン。PhotoHubはDSMに1回だけ確かめてすぐログアウトし、パスワードは保存しない。初回ログインでPhotoHubのユーザーを自動作成（admin/guestは不可、`PHOTOHUB_ADMINS` が管理者）。LANとTailscaleのアドレスはNASのネットワーク情報から自動で取得 |
| アプリの初期設定（予備） | 管理者の `photohub user add` / `user invite` がQRコード（`photohub://setup?user&code&lan&remote`）を出す。中身は1回だけ・3日間有効の招待コードで、アプリが `/api/setup/redeem` でその端末専用のトークンに交換する（端末ごとに別のトークン）。失敗が続くと10分止める |
| アプリの自動更新 | GitHubリポジトリ（公開）の最新Releaseから取り込む。Releaseは `v<版>` タグのプッシュでGitHub Actionsが署名付きで作る（APK＋`.json`：versionCode・SHA-256・変更点）。端末はWi-Fiで12時間ごとと起動時に確認し、ハッシュ・パッケージ名・新しさ・署名鍵の一致を確かめてからPackageInstallerで入れる（Android 12以降は2回目から確認なし） |
| Dockerの土台 | Ubuntu 24.04（Python 3.12）＋ExifTool＋ffmpeg＋7-Zip＋GPTH Neo v6.4.1（チェックサム固定） |

## 11. v0.3 での変更（Googleフォトをやめ、ドライブを「最近の写真の置き場」にする）

| 項目 | 決定 |
|---|---|
| スマホのバックアップ | 写真ハブのアプリが行う。NASへは `POST /api/upload`（SHA-256で確認）、Googleドライブへは本人のドライブの `/PhotoHub` フォルダ（権限は `drive.file`＝アプリが作ったファイルだけ）。Synology Photos・Googleフォトのバックアップは不要 |
| ドライブの範囲 | 各自が設定で「期間（か月）」「容量（GB）」「両方（早い方）」を選ぶ。範囲外の写真は、`/api/have` でNASにあると確かめてからアプリが削除 |
| ドライブ → NAS | Cloud Sync（片方向ダウンロード）で `inbox/drive/<user>/` へ。写真ハブが5分ごとに取り込み、重複は画素ハッシュでまとめる |
| スマホの空き容量 | 「空き容量を増やす」を押したときだけ、NASに保存済みで30日以上前の写真をAndroidの確認つきで削除 |
| 表示 | スマホ → 端末内のプレビュー → NAS（届くとき） → ドライブ（VPNなしで届く）の順に取りにいく |
| Googleフォト | Takeoutで一度だけ取り込み（移行）。定期のTakeoutは不要になり、予約していない人には「届かない」通知を出さない |

## 12. Synology Photosのバックアップとの共存

| 項目 | 決定 |
|---|---|
| 目標 | Synology Photosのバックアップと、PhotoHubのバックアップ・Takeout・ドライブのどれが動いても（同時でも）、NASにもSynology Photosの画面にも同じ写真が1枚だけ |
| スマホからの写真の置き場所 | Synology Photosがそのスマホに使う `<個人スペース>/MobileBackup/<機種名>/` に、既存のファイルから割り出した同じ並べ方（年/月 など）・同じファイル名で置く |
| 置く前の確認 | Takeout・スマホ・ドライブのどれも、置く前にその人の個人スペースと共有スペースの索引を最新にする（変わったファイルだけ） |
| 後から重複が出たとき | PhotoHubが置いたファイル（`media.placed`）と同じ写真が別の場所に現れたら、相手（Synology Photosなど）のファイルを正とし、PhotoHubのコピーを `trash/<日付>/duplicates/` へ移す（削除しない） |
| 置き場所の設定 | 「Googleから移した写真の置き場所」（自分だけ／家族で共有）は、Takeoutとドライブから来た写真に適用。スマホから直接送った写真は常にSynology Photosと同じ個人スペース |
