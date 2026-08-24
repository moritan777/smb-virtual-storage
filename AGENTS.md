# AGENTS.md

## 1. このファイルの役割

このファイルは、本リポジトリで作業するCodexおよびその他のコーディングエージェントが常に従う開発ルールを定義する。

V1の機能仕様の正本は以下である。

`docs/v1-spec.md`

実装上の都合とV1仕様が衝突する場合、実装を優先して仕様を暗黙に変更してはならない。

必要な変更を明示し、仕様変更として扱うこと。

---

# 2. プロジェクトの目的

本プロジェクトは、SMB共有をAndroidのStorage Access Frameworkへread-onlyの仮想ストレージとして公開するAndroidアプリケーションを開発する。

利用者から見て、

> SMB/NAS上のファイル群を「読み取り専用の仮想SDカード」に近い感覚で利用できること

を目標とする。

本プロジェクトは単なるSMBファイルマネージャーを作ることを目的としない。

SMB共有をAndroidのストレージProviderとして公開することが中心目的である。

---

# 3. V1の中心原則

V1では以下を必ず維持する。

- Android `DocumentsProvider` / Storage Access Frameworkを利用する。
- SMB2/SMB3共有を対象とする。
- SMB側はread-onlyとする。
- 複数SMB共有をrootとして登録可能にする。
- ファイル取得はファイル単位とする。
- ファイルは全量取得してから対象アプリへ提供する。
- 取得済みファイルはローカルread cacheとして再利用する。
- キャッシュ容量を管理する。
- キャッシュ整理はLRUを基本とする。
- SMBへ接続できない場合でも、有効なキャッシュが存在すれば読み込めるようにする。
- サムネイル生成はV1では行わない。
- 音楽固有の先読み・ストリーミング最適化はV1では行わない。
- VPN機能は本アプリに実装しない。

詳細は必ず `docs/v1-spec.md` を参照すること。

---

# 4. V1で禁止する機能追加

以下を明示的な仕様変更なしに実装してはならない。

- SMBへのwrite
- overwrite
- create
- delete
- rename
- move
- directory creation
- 双方向同期
- オフライン編集
- 競合解決
- 部分ファイルキャッシュ
- SMBからの直接ストリーミング
- ZIPの部分取得
- 動画ストリーミング最適化
- 音楽先読み
- サムネイル生成
- 独自VPN
- SMBポートのインターネット直接公開を前提とした機能
- root権限を必要とするファイルシステムmount

「便利だから」という理由だけでV1のスコープを拡張しないこと。

---

# 5. Read-onlyと将来のWrite対応

V1はread-onlyである。

ただし、

> 永久にread-onlyである

という意味ではない。

将来的に以下を追加する可能性がある。

- create
- write
- overwrite
- delete
- rename
- move
- directory creation
- Pin / Keep Offline
- オフライン編集
- 再同期
- 競合検出・解決

したがって内部設計では、read-onlyのV1仕様とStorage Backendそのものを不必要に一体化しないこと。

例えばストレージ抽象化が存在する場合、

```text
list
stat
openRead

create
openWrite
delete
rename
```

等へ将来拡張可能な責務分離を検討すること。

ただしV1で未使用のwrite処理を先行実装してはならない。

将来拡張可能な設計と、未要求機能の先行実装を混同しないこと。

---

# 6. ファイル取得原則

V1ではファイルを全量取得してから対象アプリへ提供する。

以下の方式へ独断で変更してはならない。

- ストリーミング
- Range的な部分取得
- SMBファイルを対象アプリへ直接seekさせる方式
- ブロックキャッシュ
- ZIPの部分取得

基本データフローは以下とする。

```text
User selects file
        ↓
Check cache
        ↓
Valid cache exists?
   ├─ Yes → use local cache
   │
   └─ No
        ↓
Download complete file from SMB
        ↓
Store temporary/incomplete state
        ↓
Download completes successfully
        ↓
Atomically promote to valid cache
        ↓
Provide file to requesting application
```

不完全なダウンロードを正常なキャッシュとして扱ってはならない。

---

# 7. キャッシュ設計原則

キャッシュはファイル単位とする。

少なくとも以下を管理できる設計とする。

- キャッシュ対象のSMBファイル識別情報
- path
- size
- lastModified
- local cache location
- cache size
- last access time
- cache validity

将来的なPin機能追加を妨げないこと。

ただしV1ではPin機能を必須実装しない。

キャッシュ容量上限を超えた場合はLRUを基本として整理する。

---

# 8. SMB側変更への対応

SMB側ファイルが変更された場合、古いキャッシュを正常ファイルとして利用し続けないこと。

V1では少なくとも、

```text
path
+
size
+
lastModified
```

等を利用してキャッシュの有効性を判断する。

毎回ファイル全体をハッシュする実装を必須としない。

より複雑な変更検出方式を採用する場合は、その必要性を説明すること。

---

# 9. ネットワーク責務

本アプリは、

> SMBサーバーへIPネットワーク上で到達可能である

ことだけを前提とする。

ネットワーク経路は本アプリの責務ではない。

利用者は以下のいずれを利用してもよい。

- LAN
- Wi-Fi
- Tailscale
- WireGuard
- その他VPN

アプリ内部でVPN機能を実装しない。

SMBポートをインターネットへ直接公開する運用を前提としない。

---

# 10. SMBライブラリ

SMBプロトコルを独自実装しない。

既存の成熟したSMB2/SMB3ライブラリを利用すること。

ライブラリ選定時には少なくとも以下を評価する。

- Android対応
- SMB2対応
- SMB3対応
- read性能
- 大容量ファイル対応
- キャンセル処理
- timeout
- 再接続
- 認証方式
- ライセンス
- メンテナンス状況
- Android最新バージョンとの互換性

ライブラリ選定は設計段階で理由を明示して決定する。

最初に見つかったライブラリを無条件に採用しないこと。

---

# 11. Androidプラットフォーム

本プロジェクトはAndroidの標準的なStorage Access Frameworkとの統合を優先する。

独自ファイルブラウザだけで完結する設計にしない。

中心となるAndroid機構は、

`DocumentsProvider`

である。

ただしAndroid APIの具体的な利用方法については、最新のAndroid SDK仕様を確認して設計すること。

非root端末を前提とする。

---

# 12. SAF互換性

SMB共有は本物のSDカードとして `/storage/...` にmountされるものではない。

Storage Access Framework上のProviderとして提供する。

したがって対象は原則としてSAF対応アプリである。

ローカルファイルパスしか受け付けないアプリとの完全互換性をV1の必須条件としない。

互換性向上策を検討する場合も、root mount等へ安易に移行しないこと。

---

# 13. UI原則

V1では必要最小限の設定UIを提供する。

UIの装飾より以下を優先する。

- SMB共有を登録できる
- 接続状態を理解できる
- エラー理由を理解できる
- ダウンロード状態を理解できる
- キャッシュ状態を理解できる

V1ではファイル内容からサムネイルを生成しない。

ファイル表示には一般的なファイル種別アイコンを使用する。

---

# 14. エラー処理

正常系だけを実装して完了扱いしてはならない。

少なくとも以下を考慮する。

- Host unreachable
- timeout
- authentication failure
- share not found
- file not found
- SMB connection lost during download
- local storage full
- cache write failure
- file changed during operation
- download cancellation
- Android process termination

エラー時に破損・不完全ファイルを正常キャッシュとして残してはならない。

---

# 15. 認証情報

SMB username/passwordを平文で不用意に保存しない。

Androidで利用可能な安全なCredential保存方式を設計段階で評価する。

ログ、例外、テスト出力へpassword等を出力してはならない。

実ユーザーの認証情報をテストデータへハードコードしてはならない。

---

# 16. ログ

ログはデバッグ可能性を確保しつつ、機密情報を含めない。

ログに出力してはならない情報：

- SMB password
- VPN credential
- secret/token
- その他認証情報

必要に応じてhost、share、path等をログへ出す場合も、将来的なprivacy要件を考慮した責務分離を行う。

---

# 17. テスト原則

新しい機能を追加した場合は、その機能を検証するテストも追加する。

特に純粋ロジックはAndroid実機だけに依存させない。

以下は可能な限りunit test可能な構造とする。

- cache validity
- LRU
- cache size accounting
- path/document ID mapping
- error mapping
- metadata handling

SMB、DocumentsProvider等の境界は適切に抽象化し、テスト可能性を維持する。

---

# 18. 最低限検証すべき異常系

少なくとも以下をテスト計画へ含める。

- 正常なSMB接続
- SMB接続失敗
- 認証失敗
- 存在しないshare
- 存在しないfile
- 0-byte file
- 小さいfile
- 大容量file
- download途中切断
- downloadキャンセル
- cache hit
- cache miss
- cache invalidation
- cache capacity exceeded
- LRU eviction
- NAS offline + valid cache
- NAS offline + no cache
- local storage不足
- incomplete cache cleanup

---

# 19. 実機検証

主要実機としてPixel 9aを想定する。

ただしコードをPixel 9a固有仕様へ不必要に依存させない。

エミュレータまたはunit testで確認可能な項目と、実機でしか確認できない項目を分離すること。

実機確認が必要な変更については、完了報告時にその旨を明示すること。

---

# 20. 変更時のルール

実装中に `docs/v1-spec.md` と矛盾する必要が生じた場合：

1. 勝手に仕様を変更しない。
2. 問題点を明示する。
3. 代替案を提示する。
4. 仕様変更が必要なら、コード変更前に仕様変更として扱う。

「技術的に簡単だから」を仕様変更理由にしない。

---

# 21. ドキュメントの役割

文書の責務を分離する。

## `docs/v1-spec.md`

V1で何を作るかを定義する機能仕様の正本。

## `AGENTS.md`

Codexおよび開発エージェントがどう作業するかを定義するルール。

## `docs/architecture.md`

採用技術、コンポーネント、責務、依存関係、データフロー等の技術設計。

設計レビュー後に作成する。

## `docs/testing.md`

テスト戦略、テストケース、実機受入条件。

設計確定後に作成する。

同じ内容を複数文書へ無意味に重複させない。

---

# 22. 実装フェーズ

V1は段階的に実装する。

想定フェーズ：

```text
Phase 1
Android project
+
DocumentsProvider skeleton
+
SAF root表示
+
SMBはStub

Phase 2
SMB接続
+
folder/file listing
+
read-only metadata

Phase 3
complete-file download
+
local cache
+
openDocument

Phase 4
cache reuse
+
cache validity

Phase 5
cache capacity
+
LRU eviction

Phase 6
offline behavior
+
error handling
+
V1 integration
```

設計レビューによって合理的な調整を行うことは可能。

ただし複数Phaseを理由なく一括実装しない。

---

# 23. 各Phaseの完了条件

各Phase終了時に以下を報告する。

- 実装した内容
- 変更したファイル
- 追加・変更したテスト
- 実行したテスト
- テスト結果
- 未解決事項
- 実機確認が必要な事項
- 次Phaseへ進む前に確認すべき事項

「コードを書いた」だけでは完了としない。

---

# 24. 設計段階のルール

初回設計フェーズでは実装を開始しない。

まず以下を決定または提案する。

- Android project構成
- Kotlin/Android技術構成
- minSdk
- targetSdk
- DocumentsProvider設計
- SMB library候補
- SMB abstraction
- cache architecture
- metadata storage
- credential storage
- concurrency/cancellation
- download lifecycle
- process deathへの対応
- error model
- testing strategy

選択肢が複数存在する場合は、推奨案だけでなく主要な代替案とtrade-offを示す。

---

# 25. 過剰設計の禁止

将来必要になる可能性だけを理由に、V1で大規模なframeworkや複雑な抽象化を導入しない。

特に以下を避ける。

- 不要なmulti-module化
- 不要なClean Architecture階層
- 不要なRepository乱立
- 未使用のwrite実装
- 未使用のnetwork abstraction
- 未使用のcloud backend
- premature optimization

ただし、SMB・Cache・DocumentsProvider等、明確に責務が異なる境界は分離する。

---

# 26. 外部依存

新しい外部ライブラリを追加する場合は、以下を確認する。

- 何のために必要か
- Android標準APIでは不十分か
- ライセンス
- メンテナンス状況
- package sizeへの影響
- security
- 将来の保守性

目的不明な依存ライブラリを追加しない。

---

# 27. コード品質

以下を優先する。

- 明確な責務
- 読みやすい命名
- 小さく理解可能な変更
- エラーを握り潰さない
- 不要なfallbackを作らない
- silent failureを避ける
- nullable/error stateを明示的に扱う
- magic numberを避ける

コメントはコードから明らかな処理の説明ではなく、設計理由やAndroid/SMB固有の制約を残すために使用する。

---

# 28. Fallbackに関する原則

問題を隠すためのfallbackを追加しない。

例えば、

```text
SMB access failed
↓
理由を隠して空フォルダとして表示
```

のような実装は禁止する。

エラーと「本当に空である」を区別する。

同様にキャッシュ破損時に古いファイルを無条件で返す等の挙動を行わない。

---

# 29. 完了報告

Codexが作業完了を報告する場合は、簡潔でもよいので以下を含める。

```text
Summary

Changes
- ...

Tests
- ...

Result
- ...

Not verified
- ...

Remaining issues
- ...
```

未実行テストを実行済みのように記載しない。

実機未確認の場合は明示する。

---

# 30. 最重要原則

このプロジェクトでは、

> **SMBへ接続すること自体ではなく、NASをAndroidのread-only仮想ストレージとして自然に利用できること**

を中心に判断する。

V1では、

> **安全で単純なread-only + complete-file cache**

を完成させることを優先する。

機能数を増やすことを進捗とみなさない。

`docs/v1-spec.md` に定義された利用体験を、壊れにくく検証可能な形で実現することを優先する。