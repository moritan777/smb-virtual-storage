# テスト戦略

[English](testing.md) | **日本語**

## 自動テストの範囲

Pure JVM テストでは、リモートパスの正規化と traversal 拒否、root mode の継承、更新検出、`Long` によるバイト数計算、設定値の検証、キャッシュの鮮度と上限処理、境界付きコピーとサイズ不一致、ユーザー向けエラー変換、Mirror 比較方向、local-only/local-newer の保護、Copy to SMB の decision engine、rule execution gate、source-relative path、scheduler tag、tree-copy progress を検証します。

Copy to SMB の decision engine は `NEW / UNCHANGED / KEEP_BOTH / REPLACE / REUSE_EXISTING` を副作用なしで判定します。実行側は `.part`、size / SHA-256 検証、promotion、conflict policy、backup、cancellation を fake SMB / SAF 境界で検証します。

Room / Android instrumentation では、正常スキャン後の整合、Connection の連鎖削除、Root index の削除、parent 単位の paging、Unicode ファイル名、認証情報の置換、Preferences DataStore、SAF tree の分離、外部 viewer へ渡す read-only intent を検証します。Folder cache marker については、子孫に `CACHED` ファイルがあるフォルダと、キャッシュがないフォルダの projection/state を確認することを受け入れ条件とします。

## Copy to SMB の受け入れ確認

最低限、次を確認します。

- Preview が SMB を変更しない。
- 同一 SHA-256 の original destination は `Unchanged` になる。
- `KEEP_BOTH` の numbered destination が正しく選ばれる。
- 同一内容の numbered destination があれば `Reuse existing` になる。
- `REPLACE_WITH_BACKUP` が backup を作ってから promotion する。
- `.part` が失敗・キャンセル時に残らない。
- source file がコピーによって変更・削除されない。
- deletion propagation がない。
- manual / periodic / retry の work がルール単位で干渉しない。
- Activity が file-level result と error code / backup path を表示できる。
- 失敗 source path だけを retry work に渡せる。

## 環境依存の確認

Room instrumentation、Android Keystore、SAF provider permission、WorkManager scheduling/cancellation、外部 viewer の grant、実 SMB2/3 相互運用性にはエミュレーターまたは実端末が必要です。

実 NAS を使った Copy to SMB の受け入れでは、connection root の外へ出られないこと、Preview と実行の差異があり得ること、KEEP_BOTH / REPLACE_WITH_BACKUP の実際の SMB 挙動、ネットワーク切断、巨大ファイルの `Long` byte accounting、キャンセル時の `.part` cleanup を確認します。

Mirror については NAS-only / NAS-newer だけを NAS → Device へコピーし、local-only / local-newer を削除・自動上書きしないことを確認します。オフライン時は以前の index、取得済み Mirror、valid ON_DEMAND cache が利用できることを確認します。

## Database migration

production database の migration と `app/schemas` を一致させます。migration の検証では production migration object を直接実行する `MigrationTestHelper` を優先し、SQL をテストコードへ複製しません。

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
