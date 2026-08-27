# Network Storage

[English](README.md) | **日本語**

**ステータス: Public Beta / v0.1.0**

Network Storage は、ユーザーが指定した SMB2/SMB3 NAS のサブツリーをインデックス化し、ファイル全体を端末のローカルストレージへ取得して開く、root 権限不要の Android 10+ 向けアプリです。Android のファイルシステムとしてマウントするアプリではなく、NAS をマウント済みドライブとして Android に公開するものでも、microSD の代替でもありません。

## 主な機能

- **ON_DEMAND** — NAS のメタデータをスキャンし、ファイルを開くときにファイル全体をダウンロードして、削除可能なローカルキャッシュとして保持します。設定したキャッシュ上限を超えると、オンデマンドキャッシュは LRU に基づいて整理されます。
- **MIRROR** — オフライン利用のためにファイル全体のローカルコピーを保持します。手動同期と、任意で有効化できる WorkManager の定期同期は、**NAS → Device の一方向**にファイルをコピーします。Mirror ファイルは保持データとして扱われ、キャッシュ整理の対象外です。
- Room に保存されたローカルインデックスを参照してファイルを閲覧し、取得済みのローカルファイルを Android の読み取り専用 content URI で開きます。
- SMB2/SMB3 を使用します。**SMB1 はサポートしていません。** SMB 側へのアクセスは読み取り専用です。

アップロードと双方向同期は実装していません。転送のレジュームも未実装で、中断された転送は先頭から再開します。Mirror 同期は local-only ファイルを自動削除せず、local-newer ファイルを自動的に上書きしません。

## オフライン時の動作

オフラインまたはスキャン失敗時には、NAS の内容が削除されたものとして扱わず、それまでのインデックスを保持します。正常に取得済みの Mirror ファイルはオフラインでも開けます。ON_DEMAND モードで未取得のファイルはオフラインでは取得できませんが、すでに有効なローカルキャッシュがある場合は開くことができます。

## はじめに

1. **Settings** で、オンデマンド Cache と Mirror データ用に別々の Android Storage Access Framework フォルダを選択し、必要に応じてキャッシュ上限を設定します。
2. NAS のホスト、SMB share/base folder、ユーザー名、パスワードを入力して接続を追加し、**ON_DEMAND** または **MIRROR** を選択します。
3. スキャンを実行し、インデックス化されたフォルダを閲覧します。
4. ON_DEMAND モードでは、ファイルを開くとファイル全体をダウンロードした後、端末にインストールされているビューアーを起動します。
5. MIRROR モードでは、手動同期を実行するか、自動同期を有効にして定期間隔を選択します。取得済みの Mirror ファイルはオンライン・オフラインのどちらでも開けます。

認証情報は Android Keystore で保護され、保存後に再表示されません。

## ビルド要件

- JDK 17
- compileSdk 35 の Android SDK（targetSdk 35、minSdk 29 / Android 10）
- 以下の Wrapper コマンドを実行するための Windows PowerShell またはコマンドプロンプト
- インストールおよび instrumentation test 用のエミュレーター、または Android 10+ 端末

現在のリポジトリには Windows 用の `gradlew.bat` が含まれています。標準の Unix 用 `gradlew` スクリプトは**現在含まれていません**。そのため Linux/macOS でのビルドには、別途インストールした互換 Gradle、または将来の人間管理による Wrapper 更新が必要です。通常のアプリ変更の一環として Wrapper ファイルを再生成しないでください。

### Windows: debug APK のビルドとインストール

リポジトリのルートで実行します。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

debug APK は `app\build\outputs\apk\debug\` 以下に生成されます。エミュレーターまたは端末を接続している場合は、次のコマンドでインストールできます。

```powershell
.\gradlew.bat installDebug
```

## 既知の制限

- Public Beta です。現在サポート対象としているのは最新の `main` / v0.1.x 系列のみです。
- SMB1、アップロード、リモート側の変更、双方向同期、ストリーミング、部分ファイルのオープン、転送レジュームには対応していません。
- システム全体のマウントやストレージボリュームの代替ではなく、アプリ内のインデックス／キャッシュ／Mirror です。
- 定期 Mirror 同期の実行タイミングは Android WorkManager によって制御され、正確な時刻指定ではありません。
- Mirror の競合処理は保守的です。local-only と local-newer のファイルはユーザーによる管理が必要で、自動削除されません。
- 実端末での動作は、選択した document provider、利用可能なローカルストレージ、ネットワークの安定性、対象ファイル形式を開けるビューアーの有無に依存します。

## ライセンス

[Apache License 2.0](LICENSE) の下で公開しています。正式なライセンス条件は `LICENSE` を参照してください。
