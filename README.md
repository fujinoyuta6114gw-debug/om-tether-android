# OM Tether for Android — USB-C prototype v0.2.2

OM SYSTEM OM‑1 Mark IIをAndroid端末からUSB-Cでテザー撮影するための、非公式の試作アプリです。撮影現場で必要になる表示・保存・画像確認を先に実装し、カメラ固有のUSB応答は診断ログで追える構成にしています。

## 試作に含まれる機能

- USBホストからOM‑1 Mark II（VID `0x33A2` / PID `0x0136`）を検出し、AndroidのUSB許可を取得
- PTPセッション、機種名・対応命令・対応プロパティの読み取り診断
- OM‑D系USB命令によるライブビューJPEG取得
- シャッター操作と、生成されたJPEG／ORFオブジェクトの自動取得
- Androidの `Pictures/OM Tether/yyyy-MM-dd` への自動保存
- ライブビューのピンチ拡大、輝度ヒストグラム、白飛び警告オーバーレイ
- 絞り・シャッター・ISO・露出補正・ホワイトバランスの列挙値読み取りと変更
- カメラなしでUIとJPEG保存を確認できるデモモード
- USBトランザクションとカメラ能力をコピーできる診断画面
- 初回起動時の撮影前ガイド（端末設定、USB接続、グレーカード判定、表示微調整）

## v0.2.2の実機フィードバック修正

- USB受信のゼロ長パケットと古いPTP応答を安全に読み飛ばし、1フレーム後にライブビューが停止する問題を修正
- ライブビュー表示とヒストグラム／白飛び解析の更新頻度を分離し、低速端末での画面停止とメモリ負荷を軽減
- 4秒以上フレームが届かない場合に停止を表示し、画面上の「再開」から復旧できるよう変更
- OM独自形式に合わせ、絞り・シャッター速度・露出補正・ISO・WBの表示を修正
- OM-Dシャッター命令の直前に撮影先を確認し、`DeviceBusy` の場合だけ安全に再試行
- 起動時のデモ接続とUSB接続の競合を廃止
- 起動時とUSB検出時に、カメラ側で［0 RAW/Control］を選ぶ具体的な接続手順を表示

## 撮影前ガイド

初回起動時に、次の順序で撮影環境を整えるガイドを表示します。完了後もヘッダーの「撮影前ガイド」から再実行できます。

1. Androidの画面をナチュラル／標準／sRGBにし、自動調光や夜間表示などをオフ
2. OM‑1 Mark IIをRAW/Control・RAW+JPEGに設定してUSB-C接続
3. 中央枠にグレーカードを入れ、明るさとRGBの偏りを自動判定してカメラ側のワンタッチWBを案内
4. カメラのモニターと見比べながら、Androidプレビューの色温度・色かぶり・明るさを微調整
5. 表示プロファイルを端末へ保存してテザー撮影を開始

表示微調整はComposeのプレビュー描画にだけ適用します。保存するJPEG／ORFは無加工で、ヒストグラムと白飛び判定も補正前の画像から計算します。色差計を使わないため、これは厳密なディスプレイ校正ではなく、現場で視覚差を減らすための実用的な調整です。

## v0.2.1のガイド修正

- デモモードを実機接続として扱わず、OM‑1 Mark II接続後だけガイドを進行
- グレーカード判定に加えて、カメラ側でワンタッチWBを実行したことを明示確認
- USB未接続・判定未完了・実機切断中の完了操作をViewModelとUIの両方で拒否
- グレーカードを使わない場合は、成功表示ではなく明示的な「スキップ」として表示
- ガイドを完了せず閉じた場合、調整途中のプレビュー補正を保存済み値へ戻す
- グレーカード確認中の拡大・移動を無効化し、画面上の中央枠と解析位置のずれを防止
- システムバー領域とAndroidの戻る操作へ対応
- ライブビュー解析を `collectLatest` から最新1フレーム保持の逐次処理へ変更し、低速端末で解析がキャンセルされ続ける問題を防止

## v0.1.1の安定性修正

- 撮影、露出変更、接続切替を操作単位で直列化し、撮影シーケンスへの割り込みを防止
- ObjectInfoとサムネイルを先に取得し、JPEGをRAWより先にプレビュー
- `DeviceBusy` / `InvalidObjectHandle` のオブジェクト読み取りを段階的に再試行
- JPEG／ORFを1ファイルずつMediaStoreへ保存し、RAW+JPEG全体をメモリに保持しない方式へ変更
- ObjectInfoの申告サイズと受信サイズを照合し、空データや不完全なJPEGを保存前に拒否
- PTP受信時の二重バッファを廃止し、ライブビュー解析を最大10fps・長辺1280pxへ制限
- アプリがバックグラウンドへ移動した際にライブビュー取得を停止し、復帰時に再開
- USB切断時に古いプレビューを消去し、JPEG／RAWの一部保存失敗を画面へ個別表示
- 割り込みエンドポイントの通常タイムアウトをエラーとして記録しないよう修正

## 重要な現在地

このソースは、Android側のUI・保存・画像解析とPTP通信層までを実装した**実機検証中のプロトタイプ**です。OM‑1 Mark IIのUSB制御はメーカー公開SDKではなく、カメラが公開するPTP能力とlibgphoto2の公開実装を根拠にしています。ファームウェアやAndroid端末のUSB実装差があるため、本番撮影に使う前に必ず予備カードとテスト撮影で確認してください。

| 項目 | 状態 |
|---|---|
| デモのライブビュー／ヒストグラム／白飛び表示 | 実装済み、ソース検証済み |
| JPEGのMediaStore保存 | 実装済み |
| USB列挙・権限・PTPバルク通信 | 実機接続確認済み、継続検証中 |
| OM‑1 Mark IIライブビュー | 実機でフレーム確認済み、停止対策を追加して再検証待ち |
| JPEG／ORF自動取得 | 実装済み、カメラをRAW+JPEGに設定して実機確認が必要 |
| 露出値の変更 | カメラが列挙する値だけを送る安全側実装、OM独自形式の表示変換を追加 |
| PEN E‑P7 | 対象外。公式OM Captureの対応機種一覧にも掲載なし |

アプリには `DeleteObject` やカード初期化などの削除系命令を実装していません。カメラ内のファイルは消しません。

## カメラとAndroidの準備

1. カメラの電源をOFFにします。
2. 充電専用ではないUSB-CデータケーブルでAndroid端末へ直結します。
3. カメラの電源をONにします。
4. カメラ画面で **［0 RAW/Control］** を選び、OKボタンを押します。
5. カメラ側の画質を **RAW+JPEG** にします。アプリはカメラが生成した両方のオブジェクトを保存します。
6. アプリの接続ガイドで「0 RAW/Controlを選択済み・接続」を押し、AndroidのUSBアクセス確認を許可します。

選択画面が出ない場合は、カメラの `MENU → e → 3. モニター/音/接続 → USBの設定 → USB接続モード` を「毎回確認」にします。

## ビルド

必要環境はAndroid Studio、JDK 17、Android SDK 35です。

1. Android Studioでこのフォルダーを開きます。
2. Gradle同期を行います。
3. `app` をAndroid 10（API 29）以上の実機へ実行します。

この配布物はGradle WrapperのバイナリJARを含みません。CLIビルドを行う場合は、Gradle 8.9が入った環境で一度 `gradle wrapper --gradle-version 8.9` を実行してから `./gradlew assembleDebug` を実行してください。

### GitHub ActionsでAPKを作る

`.github/workflows/build-apk.yml` を同梱しています。GitHubの `main` ブランチへ配置すると、ソース検証、JVM単体テスト、debug APK生成を自動実行します。成功時の成果物名は `OM-Tether-v0.2.2-debug` で、中に `app-debug.apk` が入ります。

このAPKは初期動作確認用のdebugビルドです。Google Play配布用のrelease署名APKではありません。

## 保存先

撮影データはAndroidの共有ストレージへ次の形式で保存されます。ストレージ権限の追加操作は不要です。

```text
Pictures/OM Tether/2026-07-23/P7230001.JPG
Pictures/OM Tether/2026-07-23/P7230001.ORF
```

デモモードはJPEGだけを保存します。RAWは実機から受信したORFバイト列を無加工で保存します。AndroidがORFを標準表示できない場合でも、ファイル本体は保持されます。

## 実機で最初に確認する順序

詳細は [docs/REAL_DEVICE_CHECKLIST.md](docs/REAL_DEVICE_CHECKLIST.md) を参照してください。最初は「接続 → 診断ログ → ライブビュー → 1枚だけ撮影 → JPEG/ORF照合」の順で確認します。露出変更は撮影・保存が安定した後に確認してください。

ソース配布時点の検証範囲と未実施項目は [docs/BUILD_VERIFICATION.md](docs/BUILD_VERIFICATION.md) に記録しています。依存関係なしの構造確認は `python3 tools/verify_source.py` で再実行できます。

## 設計上の安全策

- OM‑1 Mark IIの既知VID/PIDと、PTPから読み取った `OMSYSTEM` / `OLYMPUS`・`OM-1` の組み合わせを二重確認
- 露出変更はカメラのプロパティ記述子が `read/write` として列挙した値だけを送信。接続初期化のPC modeだけはOM‑D公開実装で使われる既知値を、機種二重確認後に送信
- USB I/OはUIスレッド外かつ単一直列化し、ライブビューと撮影トランザクションを競合させない
- カメラオブジェクトは1件ずつ検証・保存し、片方の保存失敗時も成功済みファイルを画面へ明示
- 受信コンテナ長を128 MiBに制限し、不正長を拒否
- カメラのファイル削除・フォーマット・電源制御命令は未実装
- 切断時はライブビュー処理を停止してPTPセッションとUSBインターフェースを解放

## 根拠にした一次情報

- [OM Capture 対応機種・概要](https://software.omsystem.com/omcapture/)
- [OM Capture 機能](https://software.omsystem.com/omcapture/en/features.html)
- [OM‑1 Mark II USB接続モード説明](https://learning.omsystem.com/OM-1MarkII/zz_html_manual/en/usb_settings_251.html)
- [Android USB host API](https://developer.android.com/develop/connectivity/usb/host)
- [libgphoto2ソースとリリース情報](https://github.com/gphoto/libgphoto2)

プロトコル上の採用値と出典は [docs/PROTOCOL_NOTES.md](docs/PROTOCOL_NOTES.md) に分離しています。

## 商標と関係性

本プロジェクトはOM Digital Solutions Corporationの公式アプリ、公式SDK、承認製品ではありません。OM SYSTEM、OLYMPUSおよび各製品名は各権利者の商標または登録商標です。
