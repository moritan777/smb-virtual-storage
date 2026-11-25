# アーキテクチャ

[English](architecture.md) | **日本語**

Network Storage は、単一モジュールで構成された root 権限不要の Android アプリケーションです。設定された SMB サブツリーを Room にインデックス化し、完全なローカルファイルを管理します。ファイルシステムをマウントしたり、`DocumentsProvider` として公開したりはしません。

## 境界と責務

Compose と ViewModel が接続編集、ローカルインデックスの閲覧、一般設定、専用 Backup 画面、Copy to SMB のルール編集・Preview・Activity・実行状態を担当します。Room は接続メタデータ、インデックス済みエントリ、キャッシュメタデータ、Copy to SMB のルールと履歴を保存します。

Backup 画面は既存の NAS 接続モデルと Copy to SMB のルール・転送処理を再利用します。NAS 接続と端末フォルダのバックアップルールを扱うための専用導線であり、別の転送エンジンを追加するものではありません。保存先フォルダ選択では、接続済み NAS の share と connection root を基準にフォルダ一覧を取得し、階層移動と現在位置の選択を行います。新規フォルダ作成は `createDirectory` のみを通し、入力名を検証して root-relative path として解決します。

SMB の一覧取得・ファイル読み取りは `SmbClient` の背後に限定されています。`SmbClient` には Backup の NAS フォルダ選択で使う明示的な `createDirectory` 操作もあり、選択中の connection root 配下に限ってフォルダを作成します。任意のファイル書き込みは提供しません。Copy to SMB のファイル転送だけが別の `SmbCopyClient` を使用し、connection root 配下の狭い書き込み操作に限定されます。認証情報は Android Keystore で保護され、Room や Worker input/history には保存しません。connection-root-relative path は各境界で正規化します。

## インデックスとブラウザ

Room は connection と parent をスコープとする PagingSource を公開するため、ブラウズ時には SMB へ直接アクセスせずローカルインデックスを参照します。失敗・キャンセル・オフラインによるスキャンでは以前のインデックスを保持し、正常完了したスキャンだけが未確認エントリとの整合を確定します。

フォルダについては、配下の任意の深さに `CACHED` なファイルが存在する場合、ブラウザ用 projection がフォルダをローカルデータありとして表現します。UI のフォルダマーカーは「子孫にキャッシュがある」ことだけを示し、フォルダ全体がオフライン利用可能であることを意味しません。ファイル自身のキャッシュ状態は従来どおり remote metadata と local cache metadata の鮮度判定で決定します。

## ローカルストレージ

`CacheRepository` は完全なファイルを `.part` に境界付きコピーし、サイズを検証して正式ファイルへ昇格させ、その後キャッシュメタデータを確定します。有効なエントリは再利用でき、キャッシュ整理は LRU で行います。

`MirrorRepository` は NAS メタデータと保持ファイルを比較し、NAS-only / NAS-newer を NAS → Device にコピーします。Mirror はキャッシュ整理の対象外で、local-only / local-newer を自動削除・上書きしません。

## Copy to SMB

Copy to SMB は Device → SMB の一方向コピーで、双方向同期ではありません。`CopyToSmbOrchestrator` と `CopyToSmbTreeExecutor` が実行を担当し、`CopyToSmbScheduler` / WorkManager が manual、daily、retry の work を分離します。ルールごとの実行は直列化します。

### Preview / decision planning

`CopyDryRunPlanner` は source と既存 destination を必要に応じて SHA-256 で読み取り、SMB に書き込まずに実行判断を作ります。判断語彙は次の 5 種類です。

- `NEW`
- `UNCHANGED`
- `KEEP_BOTH`
- `REPLACE`
- `REUSE_EXISTING`

同一 SHA-256 の既存 destination や numbered destination は再利用対象になり、毎回不要な重複を作らない方針です。Preview は dry-run なので create / write / rename / delete を行いません。

### 安全な実行

実際の Copy to SMB は `.part` 作成 → bounded copy → size / SHA-256 検証 → conflict policy 決定 → promotion → 必要な post-verification → history 記録の順です。`KEEP_BOTH` は numbered name を使い、`REPLACE_WITH_BACKUP` は `.network-storage-backup` に既存ファイルを退避してから置換します。バックアップと `.part` は通常ブラウザから除外します。

### 進捗・履歴・キャンセル・再試行

WorkManager progress を UI に反映し、total / completed / copied / skipped / failed / current source path / run attempt を表示します。手動コピーはキャンセルでき、失敗した source path だけを `onlyRelativePaths` として再実行する retry 経路もあります。retry は通常の manual / daily work と別の unique work として扱います。retry 処理はアプリケーション層から呼び出せますが、Copy to SMB のカード UI に失敗ファイル専用の再試行ボタンは現在ありません。

## Backup 設定と毎日の実行

`BackupSettingsScreen` が専用 Backup 画面を提供します。ホスト、ポート、認証情報、任意の domain、SMB share 名を入力して NAS 接続を追加し、接続確認に成功してから登録します。接続を選択すると、その接続に属するバックアップルールの画面へ進みます。ルール編集と転送処理は既存の Copy to SMB 機能を再利用します。Backup 簡易UI（`simpleMode = true`）のルールカードには **Test**、**Run now**、**Edit backup** の各操作があり、**Run now** は既存の手動実行経路（`copyNow` / `CopyToSmbScheduler.enqueueManual`）を再利用して、充電要件、ネットワークポリシー、日次スケジュール時刻などの自動実行条件を待たずに即時実行します。独自の転送エンジンは追加せず、既存の unique work（`ExistingWorkPolicy.KEEP`）や排他制御をそのまま利用します。保存先の NAS folder picker は `SmbClient.list` でフォルダだけを列挙し、親階層への移動と現在フォルダの選択を提供します。新規フォルダ作成では保存済み認証情報を取得して `SmbClient.createDirectory` を呼び出し、処理後にパスワード文字配列をクリアします。

ルールには SAF source tree、SMB connection root からの相対 destination directory、競合ポリシー、サブフォルダの扱い、任意の毎日実行設定を保存します。NAS 接続を削除すると、アプリ内の接続情報、関連するバックアップルール・履歴、ローカルインデックス、保存済み認証情報が削除されます。NAS 上のファイルや端末側 source ファイルは削除しません。

毎日の自動実行は、正確な時刻を保証するアラームではなく、WorkManager の one-time request をつなぐ方式です。

1. Scheduler がルールのローカル時刻から次回実行時刻を計算し、ルール単位の unique work として登録します。
2. scheduled worker の開始時に次回分を登録し、日次の chain を維持します。
3. Worker が実行時点で充電状態とネットワーク条件（接続中の任意ネットワーク、metered でないネットワーク、Wi-Fi）を確認します。
4. 条件を満たさない場合はコピーを行わず、スキップ理由を履歴に記録します。

WorkManager と Android のバックグラウンド実行制御により、実行が設定時刻より遅れる場合があります。スキップは履歴に残りますが、同日中の再実行は保証しません。手動実行はネットワーク接続を WorkManager constraint として要求し、daily / retry とは別の unique work 名を使用します。

## セキュリティ上の原則

- Copy to SMB 以外の SMB 境界は読み取り専用です。
- destination は connection root-relative で、root escape、absolute path、予約 backup tree の通常 destination を拒否します。
- source は SAF tree の read permission を使い、source file を変更・削除しません。
- deletion propagation は行いません。
- `.part` は完成ファイルとして公開しません。
- Preview は実際の write boundary を呼び出さず、読み取りだけで計画を作ります。

## WorkManager

Copy to SMB の manual / daily / retry はそれぞれ別の unique work 名を使います。daily はルール単位の one-time request chain とローカル時刻計算で実装され、正確な時刻のアラームではありません。Worker が充電・ネットワーク条件を確認し、スキップを履歴に残します。起動時の reconciliation は、有効なルールの scheduled work が欠けていれば再登録し、無効なルールの scheduled work をキャンセルします。

Mirror の periodic sync も WorkManager を使用しますが、Copy to SMB とは独立した work とストレージ境界です。
