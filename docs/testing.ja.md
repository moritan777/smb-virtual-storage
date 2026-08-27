# テスト戦略

[English](testing.md) | **日本語**

## 自動テストの範囲

Pure JVM テストでは、リモートパスの正規化と traversal 拒否、root mode の継承、更新検出、`Long` によるバイト数計算、設定値の検証、キャッシュの鮮度と overflow-safe な上限処理、境界付きコピーとサイズ不一致、ユーザー向けネットワークエラーへの変換、Mirror 比較方向、local-only/local-newer ファイルの保護を検証します。SMB 境界は差し替え可能で、実際の認証情報を使わず fake によって一覧取得、読み取り失敗、キャンセル、タイムアウトを検証できます。

Room/device テストでは、正常スキャン後の整合、Connection の連鎖削除、Root index の削除、parent 単位の paging、Unicode ファイル名、認証情報の置換動作、Preferences DataStore の永続化、SAF tree の分離、外部アプリで開く際の読み取り専用 intent を検証します。version 1、2、3 の Room schema は `app/schemas` に保持されています。

Cache と Mirror の受け入れ確認には、`.part` の削除、完全サイズの検証、キャッシュ上限超過後の LRU eviction、Mirror がキャッシュ整理から除外されること、手動および定期 Mirror 同期、local-only/local-newer ファイルの保守的な扱いが含まれます。オフライン確認では、失敗したスキャンが以前のインデックスを保持すること、取得済み Mirror ファイルを開けること、有効なオンデマンドキャッシュを再利用できることを検証します。

## 環境依存の確認

Room instrumentation、Android Keystore の動作、SAF provider permission、WorkManager の scheduling/cancellation、外部 viewer への grant、実際の SMB2/3 相互運用性の確認にはエミュレーターまたは実端末が必要です。実 NAS を使った受け入れ確認では、設定した root の外へ出られないこと、およそ 1,000 件のインデックス、巨大ファイル（2 GiB 超を含むバイト数計算）、ネットワーク切断、転送レジューム未実装のため先頭から再開することを確認する必要があります。

定期同期は正確なタイマーではなく、WorkManager の制約付き work としてテストします。受け入れ確認では NAS-only/NAS-newer ファイルだけを NAS → Device 方向へコピーすること、アップロードしないこと、local-newer ファイルを自動上書きしないこと、local-only ファイルを保持することを確認する必要があります。

## データベース migration

production database は migration 1→2 と 2→3 を登録し、3世代すべての schema を export しています。専用の migration instrumentation test は今後追加する価値があります。その際は SQL をテストコードへ複製するのではなく、Room の `MigrationTestHelper` から production migration object 自体を実行して検証するべきです。別途承認された production code の変更によって migration object をテストから参照可能な場所へ移すまでは、schema の存在確認と通常の instrumentation/database creation が利用可能な確認手段です。

## コマンド

Windows では次を実行します。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

`connectedDebugAndroidTest` には起動中のエミュレーターまたは接続済み端末が必要です。現在のリポジトリには Unix 用 `gradlew` スクリプトはありません。Wrapper のメンテナンスは意図的に別管理としています。
