# USB/PTP protocol notes

この文書は試作で使用した、OM‑D系PTP拡張値の監査用メモです。アプリは標準PTPコンテナをAndroid USB hostのbulk IN/OUTで送受信します。

## 対象識別子

- OM SYSTEM OM‑1 Mark II USB vendor ID: `0x33A2`
- product ID: `0x0136`

現行libgphoto2のカメラ定義では `Olympus:OM-1MarkII` にこの組み合わせとcapture/preview能力が割り当てられています。

## 使用する標準PTP命令

| 命令 | 値 | 用途 |
|---|---:|---|
| GetDeviceInfo | `0x1001` | メーカー、機種、対応命令・プロパティの取得 |
| OpenSession / CloseSession | `0x1002` / `0x1003` | セッション管理 |
| GetObjectHandles | `0x1007` | 撮影前後のオブジェクト差分取得 |
| GetObjectInfo / GetObject / GetThumb | `0x1008` / `0x1009` / `0x100A` | ファイル情報、本体、RAW用プレビュー取得 |
| GetDevicePropDesc | `0x1014` | 型、現在値、書き込み可否、選択肢を取得 |
| SetDevicePropValue | `0x1016` | カメラが列挙した露出値だけを書き込み |

## 使用するOM‑D拡張

| 命令／プロパティ | 値 | 試作での用途 |
|---|---:|---|
| OMD Capture | `0x9481` | パラメータ3（押下）、6（解放） |
| GetLiveViewImage | `0x9484` | パラメータ1でJPEGフレーム取得 |
| OMD GetImage | `0x9485` | オブジェクト通知が得られない場合のJPEG読み取りフォールバック |
| ChangedProperties | `0x9486` | 撮影後の変更通知読み取り |
| PC mode | `0xD052` | OM‑1 Mark IIの機種二重確認後、UINT16の1へ設定。非掲載ボディがあるため記述子未掲載時も初期化を試行 |
| LiveView mode | `0xD06D` | 記述子を優先確認し、非掲載時も既知のUINT32 `0x04000300` で初期化を試行 |
| Capture target | `0xD0DC` | シャッター命令直前の撮影先診断（読み取りのみ） |
| Aperture / ISO / Exposure compensation | `0xD002` / `0xD007` / `0xD008` | 記述子の列挙値から操作 |
| Image format / Shutter speed / White balance | `0xD00D` / `0xD01C` / `0xD01E` | 画質は読み取りのみ、露出関連は記述子の列挙値から操作 |

イベント通知の既知値は標準ObjectAdded `0x4002`、Olympus `0xC002`、OM‑1 Mark II系 `0xC102` です。この試作は割り込みINエンドポイントがある場合に3種類をすべて受け取り、撮影前後の `GetObjectHandles` 差分も並行して確認します。`GetObjectHandles` のstorage IDには `0xFFFFFFFF`（全ストレージ）を指定するため、カード1／カード2のどちらへ作成されたオブジェクトも候補になります。割り込み通知とカード上の差分のどちらも得られない場合は、`GetImage` を読み取り専用フォールバックにしています。

v0.1.1では、通知直後にオブジェクトが未確定の場合を考慮し、`GetObjectInfo`、`GetThumb`、`GetObject` が `DeviceBusy` または `InvalidObjectHandle` を返した場合だけ、待機時間を延ばしながら最大6回再試行します。先に全ObjectInfoを読み、JPEG候補をRAWより先に処理します。サムネイルが公開されている場合は本体転送前に撮影プレビューへ渡します。

v0.2.2ではOM独自プロパティを公開実装の形式に合わせて表示します。絞り `0xD002` は10倍値、シャッター `0xD01C` は上位16bitの分子／下位16bitの分母、露出補正 `0xD008` は符号付き16bitの1/1000 EVとして解釈します。ISOの `0xFFFF` / `0xFFFD` はAUTO / LOWとして扱います。

v0.3.0では全ObjectInfoのstorage ID、ファイル名、形式、サイズを確認し、Androidで選択したJPEGまたはORFだけを転送します。同形式が複数ストレージにある場合は大きい候補から試し、最初に正常取得できた1件で終了します。JPEGが生成されていない場合に限り、RAWの `GetThumb` 結果またはOMD `GetImage` をプレビューJPEGとして保存し、フルJPEGではないことをUIと診断に明記します。これらはすべて読み取り命令で、カメラ側の画質・保存先・既存オブジェクトは変更しません。

v0.3.1では露出表示の同期用に、取得済み記述子のデータ型を使って `GetDevicePropValue` を約1.8秒間隔で実行します。ライブビュー、撮影、露出変更と同じ直列化経路を通すため、PTPトランザクションを並行送信しません。DeviceInfoのプロパティ一覧にない場合も、既知のOM固有コードと標準PTPコードへ `GetDevicePropDesc` を安全に試し、正常応答があった項目だけを表示します。停止後の復旧時は古いUSB接続を閉じ、DeviceInfo取得とOpenSessionからやり直します。

## コンテナと制限

PTP USBコンテナはlittle-endianで、`length:u32, type:u16, code:u16, transactionId:u32` の12バイトヘッダーにパラメータまたはデータが続きます。typeはcommand=1、data=2、response=3、event=4です。セッション外のGetDeviceInfoとOpenSessionには予約済みtransaction ID 0を使い、セッション内の最初の命令から1ずつ増加させます。

試作ではコンテナ長を12バイト以上128 MiB以下に制限します。RAW受信時はコンテナ全体とpayloadを二重確保せず、12バイトのヘッダー確認後にpayloadだけを確保します。USBのデータ長が最大パケットの整数倍になった際のゼロ長パケットは読み飛ばし、中断された前トランザクションの応答が残っていた場合は排出して現在のtransaction IDまで再同期します。response code `0x2001` 以外は例外として診断ログへ残し、ライブビューの `DeviceBusy (0x2019)` と上記オブジェクト確定待ちだけを再試行します。

## 明示的に実装しない命令

- DeleteObject
- FormatStore
- SendObject / SendObjectInfo
- ファームウェア更新
- 電源制御
- カメラ内オブジェクトの名前変更

## 出典とライセンス上の扱い

拡張コード、USB ID、OM‑1 Mark II対応状況はlibgphoto2の公開ヘッダー・カメラ定義・NEWSを相互確認しました。

- <https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h>
- <https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/library.c>
- <https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/config.c>
- <https://github.com/gphoto/libgphoto2/blob/master/NEWS>

libgphoto2はGNU LGPL 2.0以降です。本プロジェクトはlibgphoto2のバイナリやソースを同梱せず、公開されたPTP数値と標準コンテナ仕様を参照してKotlinで独立実装しています。将来libgphoto2をAndroid nativeライブラリとして組み込む場合は、LGPLの再リンク・ソース提供等の条件を別途満たす必要があります。
