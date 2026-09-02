# アーキテクチャ

[English](architecture.md) | **日本語**

Network Storage は、単一モジュールで構成された root 権限不要の Android アプリケーションです。設定された SMB サブツリーを Room にインデックス化し、完全なローカルファイルを管理します。ファイルシステムをマウントしたり、`DocumentsProvider` として公開したりはしません。

## 境界と責務

Compose と ViewModel が接続編集、ローカルインデックスの閲覧、設定、Copy to SMB のルール編集・Preview・Activity・実行状態を担当します。Room は接続メタデータ、インデックス済みエントリ、キャッシュメタデータ、Copy to SMB のルールと履歴を保存します。

SMB の通常アクセスは読み取り専用 `SmbClient` の背後に限定されています。Copy to SMB だけが別の `SmbCopyClient` を使用し、connection root 配下の狭い書き込み操作に限定されます。認証情報は Android Keystore で保護され、Room や Worker input/history には保存しません。connection-root-relative path は各境界で正規化します。

## インデックスとブラウザ

Room は connection と parent をスコープとする PagingSource を公開するため、ブラウズ時には SMB へ直接アクセスせずローカルインデックスを参照します。失敗・キャンセル・オフラインによるスキャンでは以前のインデックスを保持し、正常完了したスキャンだけが未確認エントリとの整合を確定します。

フォルダについては、配下の任意の深さに `CACHED` なファイルが存在する場合、ブラウザ用 projection がフォルダをローカルデータありとして表現します。UI のフォルダマーカーは「子孫にキャッシュがある」ことだけを示し、フォルダ全体がオフライン利用可能であることを意味しません。ファイル自身のキャッシュ状態は従来どおり remote metadata と local cache metadata の鮮度判定で決定します。

## ローカルストレージ

`CacheRepository` は完全なファイルを `.part` に境界付きコピーし、サイズを検証して正式ファイルへ昇格させ、その後キャッシュメタデータを確定します。有効なエントリは再利用でき、キャッシュ整理は LRU で行います。

`MirrorRepository` は NAS メタデータと保持ファイルを比較し、NAS-only / NAS-newer を NAS → Device にコピーします。Mirror はキャッシュ整理の対象外で、local-only / local-newer を自動削除・上書きしません。

## Copy to SMB

Copy to SMB は Device → SMB の一方向コピーで、双方向同期ではありません。`CopyToSmbOrchestrator` と `CopyToSmbTreeExecutor` が実行を担当し、`CopyToSmbScheduler` / WorkManager が manual、periodic、retry の work を分離します。ルールごとの実行は直列化します。

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

WorkManager progress を UI に反映し、total / completed / copied / skipped / failed / current source path / run attempt を表示します。手動コピーはキャンセルでき、失敗した source path だけを `onlyRelativePaths` として再実行する retry 経路もあります。retry は通常の manual / periodic work と別の unique work として扱います。

## セキュリティ上の原則

- Copy to SMB 以外の SMB 境界は読み取り専用です。
- destination は connection root-relative で、root escape、absolute path、予約 backup tree の通常 destination を拒否します。
- source は SAF tree の read permission を使い、source file を変更・削除しません。
- deletion propagation は行いません。
- `.part` は完成ファイルとして公開しません。
- Preview は実際の write boundary を呼び出さず、読み取りだけで計画を作ります。

## WorkManager

Copy to SMB の automatic work はネットワーク、charging、battery、storage のルール条件に従います。WorkManager は正確な時計時刻を保証しません。manual / periodic / retry はそれぞれ unique work 名を持ち、起動中の work を UI から識別できるようにします。

Mirror の periodic sync も WorkManager を使用しますが、Copy to SMB とは独立した work とストレージ境界です。
