# Build and verification record

確認日: 2026-07-26

## v0.3.1再接続・露出同期・保存先・配色修正

- ライブビュー停止時の再接続を、同じコントローラーの再開からUSB/PTPセッション全体の再生成へ変更
- 古いコントローラーの停止を時間制限付きにし、USB接続を強制解放できる復旧経路を追加
- 停止検知時に古いフレーム、ヒストグラム、白飛び、グレーカード解析を消去
- 絞り・シャッター・ISO・露出補正・WBの現在値を約1.8秒ごとに直列化して再取得
- DeviceInfo未掲載時にも既知のOM／標準プロパティ記述子を読み取り確認する経路を追加
- MediaStore保存先を `Pictures/OM Tether/yyyy-MM-dd` に一元化し、保存先ポリシーの単体テストを追加
- 画面配色をニュートラルなダークグレーへ変更

v0.3.1はGitHub Actionsで構造検証、単体テスト、APK生成を行った後、実機で「初回接続 → ケーブル抜去または停止 → 再接続」とカメラ側ダイヤル変更の追従を確認します。

## v0.3.0保存形式・デュアルカード対応

- Android保存をJPEG／RAWの2択にし、選択を端末へ記憶
- 全ストレージのObjectInfoからJPEG／ORF候補を分離し、同形式が両カードにある場合は大きい候補から1件だけ取得
- 優先カード側の転送失敗時に、同形式の別カード候補を試す経路を追加
- JPEG不在時のRAWプレビューJPEG代替保存と画質制限警告を追加
- RAW選択時はORFだけを保存し、取得したJPEGはプレビュー用途に限定
- カード1＝RAW／カード2＝JPEG、および同形式の二重記録を模した選別単体テストを追加

v0.3.0はGitHub Actionsで単体テストとAPK生成を行った後、カード1／カード2の割り当てを入れ替えてJPEG・RAW各1枚を実機確認します。

## v0.2.2実機フィードバック対応

- OM‑1 Mark IIとAndroid実機のUSB列挙・接続、ライブビュー1フレーム受信を確認
- USBゼロ長パケットの継続読み取りと、残留した古いPTP応答の排出処理を追加
- OM独自の絞り・シャッター・露出補正・ISO・WB形式を単体テストへ追加
- ライブビュー停止検知、手動再開、表示更新と画像解析の分離を実装
- 起動時競合を避け、［0 RAW/Control］のカメラ操作を先に案内する接続ガイドを追加
- シャッター命令前の撮影先読み取りと、`DeviceBusy` に限定した再試行を追加

v0.2.2はGitHub Actionsで単体テストとAPK生成を行った後、実機で30秒以上のライブビューと1枚撮影を再確認します。

## v0.2.1修正確認

- デモモードと実機接続の分離、WB実行確認、ガイド進行条件を純粋ロジックとして検証
- ガイド中断時の補正ロールバック、中央枠でのジェスチャー無効化、システムバー・戻る操作対応を確認
- ライブビュー収集をキャンセル型から `conflate().collect` に変更し、解析完了を保証する経路を確認

## v0.2.0追加確認

- `python3 tools/verify_source.py`: PASS
- 撮影前ガイド、中央グレーカードRGB判定、プレビュー専用ColorMatrix補正、SharedPreferences保存の実装マーカーを確認
- 補正前のBitmapを `ImageAnalysis.analyze` へ渡し、その後Composeの `Image` にだけColorFilterを適用する経路を確認
- この環境ではv0.2.0追加部分のAndroid／Compose完全コンパイルは未実施

実機では、端末の画面モードごとの見え方、Android設定画面からの復帰、ガイドの縦横レイアウト、グレーカード判定閾値を追加確認してください。

## この配布物で実施済み

- 全Android XML（Manifest、style、USB filter）の構文解析
- Kotlin 2.4.10 JVMコンパイラによるPTPモデル／コーデック／露出値処理の実コンパイル
- 合成したPTPデータセットを用いた次の実行テスト
  - little-endian command container
  - DeviceInfoの文字列・対応命令・イベント・プロパティ解析
  - DevicePropDescの列挙値と現在値解析
  - UINT32 object handle配列解析
  - 128 MiBを超える不正コンテナ長の拒否
- 最小Android APIサーフェスを使った、USB transport、OM‑1 Mark II controller、画像解析、MediaStore保存、ViewModelのKotlinコンパイル
- v0.1.1のCameraController／USB transport／ViewModel／画像解析／MediaStore保存を含む全非UI Kotlinソースの再コンパイル
- 97バイト単位に分割した250,000バイトの合成PTPデータコンテナを使い、低メモリ受信処理と後続responseの連続解析を実行確認
- 合成MediaStoreを使い、正常保存と書き込み失敗時の未完了行削除を実行確認
- 合成CameraControllerを使い、撮影ボタン連打が1回に抑止され、撮影終了後に露出変更が実行される順序と、バックグラウンド停止／復帰時のライブビュー再開を確認
- 操作排他、オブジェクト再試行、逐次保存、フレーム抑制、USBイベント通常タイムアウト処理の実装マーカー確認
- Compose UIソースのKotlin構文解析（Android／Compose依存関係を使った完全コンパイルは未実施）
- `tools/verify_source.py` によるUSB ID、必須機能マーカー、カメラ削除／フォーマット命令不在の確認

## この環境では未実施

- Android Gradle PluginによるAPK生成（Android SDK／Gradleが実行環境にないため）
- Compose UIのAndroid実機レンダリング
- 修正後v0.3.1での停止後再接続、30秒以上のライブビュー、シャッター、カード1/2別のJPEG／ORF選択転送
- 実機のカメラ側ダイヤル変更に対する絞り・シャッター・ISO表示の定期同期
- 長時間・連写・ケーブル抜去テスト
- 実機でのバックグラウンド移行／復帰と、保存容量不足時の部分成功表示

## Android Studioで最初に実行するコマンド

Gradle同期後、プロジェクトルートで次を実行します。

```bash
python3 tools/verify_source.py
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Gradle Wrapperをまだ生成していない場合はREADMEの手順に従います。ビルド成功後も、`REAL_DEVICE_CHECKLIST.md` が完了するまでは実運用判定にしません。

## 合格基準

| 段階 | 合格条件 |
|---|---|
| Source | `verify_source.py` が全項目PASS |
| JVM unit | `PtpCodecTest` が全件PASS |
| Android build | lint以外のcompile errorなし、debug APK生成 |
| USB read-only | VID/PID、メーカー、機種、能力一覧が診断に出る |
| Live view | 30秒以上継続し、切断で復帰可能 |
| Single capture | 選択したJPGまたはORFが1件だけ保存され、SDカード上の元画像も残る |
| Field candidate | 50枚連続と30分ライブビューに欠落・破損・停止なし |
