# テスト戦略

[English](testing.md) | **日本語**

## 自動テストの範囲

Pure JVM テストでは、リモートパスの正規化と traversal 拒否、root mode の継承、更新検出、`Long` によるバイト数計算、設定値の検証、キャッシュの鮮度と上限処理、境界付きコピーとサイズ不一致、ユーザー向けエラー変換、Mirror 比較方向、local-only/local-newer の保護、Copy to SMB の decision engine、rule execution gate、source-relative path、scheduler tag、tree-copy progress を検証します。

Copy to SMB の decision engine は `NEW / UNCHANGED / KEEP_BOTH / REPLACE / REUSE_EXISTING` を副作用なしで判定します。実行側は `.part`、size / SHA-256 検証、promotion、conflict policy、backup、cancellation を fake SMB / SAF 境界で検証します。

Room / Android instrumentation では、正常スキャン後の整合、Connection の連鎖削除、Root index の削除、parent 単位の paging、Unicode ファイル名、認証情報の置換、Preferences DataStore、SAF tree の分離、外部 viewer へ渡す read-only intent を検証します。Folder cache marker については、子孫に `CACHED` ファイルがあるフォルダと、キャッシュがないフォルダの projection/state を確認することを受け入れ条件とします。

## Backup ルールと毎日の自動実行

Backup 画面は、既存の NAS 接続と Copy to SMB のルールモデルを使った UI です。エミュレーターまたは実機で次を確認します。

- NAS 接続確認に成功しない場合、接続を保存しない。
- 接続フォームで host、port、username、password、任意の domain、SMB share 名を入力できる。share 欄にはフォルダパスではなく share 名を指定する。
- バックアップルールが SAF source-tree permission と、選択した connection root からの相対 destination を保持する。
- ルール編集で日次スケジュールが更新され、自動実行を無効にすると該当する unique work がキャンセルされる。
- NAS 接続の削除でアプリ内のルール・履歴・保存済みログインが削除される一方、NAS 上のファイルと端末側 source ファイルは残る。
- NAS destination picker が選択した share / connection root 配下のフォルダだけを表示し、親階層へ戻れる。
- picker で選択した現在のフォルダが、connection root からの相対 destination としてルールに保存される。
- 新規フォルダ作成で空白のみの名前が拒否され、作成後に現在階層の一覧が再取得される。
- `..` や absolute path など root 外へ抜ける入力が拒否される。
- daily work が端末のローカルタイムゾーンで次回時刻を計算し、scheduled worker の開始時に次回分を再登録する。
- 充電・ネットワーク条件は Worker の実行時に確認する。条件を満たさない場合はスキップを履歴に残し、ファイルコピーを行わない。
- WorkManager の実行は設定時刻より遅れる場合がある。正確なアラームとして検証せず、スキップ後の同日再実行も前提にしない。
- 同じルールの manual / daily / retry work が別々の unique work として扱われる。

## Copy to SMB の受け入れ確認

最低限、次を確認します。

- Backup 画面の **Test** から Preview を開き、ローディング中・エラー時・結果表示時の各状態で画面がクラッシュしない。
- 同じ source-relative path が複数の結果に含まれる場合も、LazyColumn の item key が衝突しない。
- Preview が SMB を変更しない。
- 同一 SHA-256 の original destination は `Unchanged` になる。
- `KEEP_BOTH` の numbered destination が正しく選ばれる。
- 同一内容の numbered destination があれば `Reuse existing` になる。
- `REPLACE_WITH_BACKUP` が backup を作ってから promotion する。
- `.part` が失敗・キャンセル時に残らない。
- source file がコピーによって変更・削除されない。
- deletion propagation がない。
- manual / daily / retry の work がルール単位で干渉しない。
- Activity が file-level result と error code / backup path を表示できる。
- 失敗 source path だけを retry work に渡せる。

## 環境依存の確認

Room instrumentation、Android Keystore、SAF provider permission、WorkManager scheduling/cancellation、外部 viewer の grant、実 SMB2/3 相互運用性にはエミュレーターまたは実端末が必要です。

実 NAS を使った Copy to SMB の受け入れでは、connection root の外へ出られないこと、Preview と実行の差異があり得ること、KEEP_BOTH / REPLACE_WITH_BACKUP の実際の SMB 挙動、ネットワーク切断、巨大ファイルの `Long` byte accounting、キャンセル時の `.part` cleanup を確認します。

Mirror については NAS-only / NAS-newer だけを NAS → Device へコピーし、local-only / local-newer を削除・自動上書きしないことを確認します。オフライン時は以前の index、取得済み Mirror、valid ON_DEMAND cache が利用できることを確認します。

## Database migration

production database の migration と `app/schemas` を一致させます。migration の検証では production migration object を直接実行する `MigrationTestHelper` を優先し、SQL をテストコードへ複製しません。Schema version 5 では copy rule に `scheduledTimeMinutesOfDay` を追加し、デフォルト値は午前 4:00（240 分）です。既存ルールがこの値で移行され、自動実行は明示的に有効化するまで無効のままであることを確認します。

## コマンド

Windows では次を実行します。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

`connectedDebugAndroidTest` には起動中のエミュレーターまたは接続済み端末が必要です。debug APK のインストールには次を使えます。

```powershell
.\gradlew.bat installDebug
```

現在のリポジトリには Unix 用 `gradlew` はありません。Wrapper のメンテナンスは通常のアプリ変更とは別管理です。
