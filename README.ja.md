# Network Storage

[English](README.md) | **日本語**

<img width="1536" height="1024" alt="Network Storage screenshots" src="https://github.com/user-attachments/assets/fbcfbda1-51e1-4fe6-bfed-d1ee2592c7b5" />

**ステータス: Public Beta / v0.1.x 開発系列**

Network Storage は、ユーザーが指定した SMB2/SMB3 NAS のサブツリーをローカル Room インデックスに保存し、必要なファイルを端末上の完全なローカルファイルとして扱う root 権限不要の Android 10+ 向けアプリです。Android のファイルシステムとして NAS をマウントするアプリではありません。

## 主な機能

- **ON_DEMAND** — NAS のメタデータをスキャンし、ファイルを開くときに完全なファイルをダウンロードして、削除可能なローカルキャッシュとして保持します。キャッシュ上限を超えると LRU に基づいて整理されます。
- **MIRROR / Keep offline** — オフライン利用のために完全なローカルコピーを保持します。手動同期と、任意で有効化できる WorkManager 定期同期は **NAS → Device の一方向**です。Mirror ファイルはキャッシュ整理の対象外です。
- **COPY_TO_SMB / Copy to SMB** — Android Storage Access Framework で選択した端末側フォルダを、設定した SMB 接続ルート配下へ一方向コピーします。元ファイルは削除・移動・変更されません。
- **Copy to SMB の Preview** — コピー前に、SHA-256 に基づく `New / Unchanged / Keep both / Replace + backup / Reuse existing` の判断を確認できます。Preview 自体は SMB を変更しません。
- **Copy to SMB の Activity / 進捗 / キャンセル** — WorkManager の状態、ファイル数、現在処理中のパス、成功・スキップ・失敗を表示し、手動コピーをキャンセルできます。失敗ファイルだけを再実行するバックエンド経路も備えています。
- Room に保存されたローカルインデックスを参照してフォルダを閲覧します。ブラウザは通常の閲覧時に SMB へ直接アクセスしません。
- 完全取得済みのローカルファイルだけを read-only の Android content URI で外部ビューアーへ渡します。
- SMB2/SMB3 を使用します。**SMB1 はサポートしていません。** SMB 側への通常アクセスは読み取り専用で、Copy to SMB だけが狭く限定された書き込み境界を使用します。

## オフライン時の動作

オフラインまたはスキャン失敗時には、それまでの Room インデックスを保持します。取得済みの Mirror ファイルはオフラインでも開けます。有効な ON_DEMAND キャッシュもネットワーク転送なしで再利用できます。未取得ファイルはオフラインでは取得できません。

ブラウザのフォルダには、**そのフォルダ以下のどこかにキャッシュ済みファイルがある場合だけ、フォルダ内にローカルデータが存在することを示すマーカー**を表示します。このマーカーは「フォルダ全体がオフライン利用可能」という意味ではありません。

## Copy to SMB

Copy to SMB は双方向同期ではなく、**Device → SMB の一方向コピー**です。

- source は SAF の選択フォルダです。
- destination は設定した connection root からの相対パスです。
- `KEEP_BOTH` と `REPLACE_WITH_BACKUP` をサポートします。
- 同一内容は SHA-256 で `Unchanged` / `Reuse existing` として再利用し、不要な重複を作りません。
- 競合時は元の SMB ファイルを保持するか、`.network-storage-backup` に退避してから置換します。
- 転送は `.part` → 検証 → 正式名への昇格の順で行い、不完全なファイルを完成品として公開しません。
- 削除伝播、アップロード後の source 削除、任意の SMB パスへの書き込みは行いません。
- Preview は作成・変更・rename・delete を行わない dry-run です。

## はじめに

1. **Settings** で ON_DEMAND Cache と Mirror / Keep offline データ用の SAF フォルダを選択し、必要に応じてキャッシュ上限を設定します。
2. NAS のホスト、share/base folder、ユーザー名、パスワードを入力して接続を追加し、必要に応じて ON_DEMAND または MIRROR を選択します。
3. スキャンを実行し、インデックス化されたフォルダを閲覧します。
4. ON_DEMAND ではファイルを開くと完全にダウンロードしてからビューアーを起動します。
5. MIRROR / Keep offline では手動同期または任意の定期同期を実行します。
6. Copy to SMB を使う場合は、接続ごとにルールを作成し、source folder、destination、競合ポリシー、自動実行条件を設定します。実行前に **Preview** で予定操作を確認できます。

認証情報は Android Keystore で保護され、保存後に再表示されません。

## ビルド要件

- JDK 17 以上。現在の Gradle 8.11.1 では JDK 21 が正式サポート範囲です。
- compileSdk 35 の Android SDK（targetSdk 35、minSdk 29 / Android 10）
- Windows PowerShell またはコマンドプロンプト
- instrumentation test / debug 実行用のエミュレーターまたは Android 10+ 端末

リポジトリには Windows 用の `gradlew.bat` が含まれています。Unix 用 `gradlew` は現在含まれていません。通常のアプリ変更で Wrapper を再生成しないでください。

### Windows: テスト・ビルド・インストール

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

エミュレーターまたは端末が `adb devices` で認識されている場合、debug APK のビルドとインストールは次で行えます。

```powershell
.\gradlew.bat installDebug
```

instrumentation test は起動済みのエミュレーターまたは接続済み端末に対して実行します。

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## 既知の制限

- Public Beta の開発系列です。`feature/copy-decision-dry-run` では Copy to SMB の Preview、実行判断、Activity、キャンセル、失敗ファイル再試行基盤を含みます。
- SMB1、双方向同期、削除伝播、ストリーミング、部分ファイルのオープン、転送レジュームには対応していません。中断された転送は先頭から再開します。
- Copy to SMB の自動実行時刻は WorkManager が決定するため、正確な時計時刻ではありません。
- Copy to SMB の Preview は実行時の SMB 状態を保証しません。Preview 後に他の SMB クライアントが変更した場合、実行時の判断が変わる可能性があります。
- Mirror の local-only / local-newer ファイルは保守的に保持され、自動削除・自動上書きされません。
- 実端末での動作は document provider、利用可能なローカルストレージ、ネットワーク、対象ファイル形式を開けるビューアーに依存します。

## ドキュメント

設計・実装境界は [`docs/architecture.ja.md`](docs/architecture.ja.md)、テスト戦略は [`docs/testing.ja.md`](docs/testing.ja.md)、Copy to SMB の実装監査は [`docs/copy-to-smb-v1-audit.md`](docs/copy-to-smb-v1-audit.md)、V1 の製品仕様は [`docs/v1-spec.md`](docs/v1-spec.md) を参照してください。

## ライセンス

[Apache License 2.0](LICENSE) の下で公開しています。正式なライセンス条件は `LICENSE` を参照してください。
