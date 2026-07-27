package com.example.omtether.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.omtether.ConnectionPhase
import com.example.omtether.DisplayCalibration
import com.example.omtether.MainUiState
import com.example.omtether.NeutralPatchAssessment
import com.example.omtether.UsbCableAssessment
import com.example.omtether.UsbCableGrade
import com.example.omtether.assessNeutralPatch
import com.example.omtether.canAdvanceSetup
import com.example.omtether.image.NeutralPatchResult
import kotlin.math.roundToInt

@Composable
internal fun SetupGuide(
    state: MainUiState,
    onUsbConnect: () -> Unit,
    onDismiss: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCalibrationChange: (DisplayCalibration) -> Unit,
    onResetCalibration: () -> Unit,
    onWbConfirmed: (Boolean) -> Unit,
    onGrayCardSkipped: (Boolean) -> Unit,
    onComplete: () -> Unit,
) {
    val canProceed = canAdvanceSetup(
        step = state.setupStep,
        phase = state.phase,
        wbConfirmed = state.setupWbConfirmed,
        grayCardSkipped = state.setupGrayCardSkipped,
        neutralPatch = state.neutralPatch,
    )
    BackHandler {
        if (state.setupStep > 0) onPrevious() else onDismiss()
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF111315),
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("撮影前ガイド", fontSize = 22.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                Text("${state.setupStep + 1} / 6", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1B1E21),
            ) {
                when (state.setupStep) {
                    0 -> IntroStep()
                    1 -> AndroidDisplayStep()
                    2 -> CameraConnectionStep(state, onUsbConnect)
                    3 -> GrayCardStep(state, onWbConfirmed, onGrayCardSkipped)
                    4 -> DisplayAdjustmentStep(state, onCalibrationChange, onResetCalibration)
                    else -> CompleteStep(state)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.setupStep > 0) {
                    OutlinedButton(onClick = onPrevious, modifier = Modifier.weight(1f)) { Text("戻る") }
                }
                Button(
                    onClick = if (state.setupStep == 5) onComplete else onNext,
                    enabled = canProceed,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.setupStep == 5) "テザー撮影を開始" else "次へ")
                }
            }
        }
    }
}

@Composable
private fun GuideBody(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun IntroStep() = GuideBody(
    "約3分で表示を整えます",
    "カメラの画像をAndroidで見たときの違和感を減らす、撮影前のおすすめ手順です。",
) {
    Checklist("Androidの画面設定を確認")
    Checklist("OM‑1 Mark IIをUSB-Cで接続")
    Checklist("グレーカードでカメラのWBを確認")
    Checklist("Androidのプレビューだけを微調整")
    Notice("補正は表示だけです。保存するJPEG／ORF、ヒストグラム、白飛び判定は変更しません。")
}

@Composable
private fun AndroidDisplayStep() {
    val context = LocalContext.current
    GuideBody("Androidの画面を標準状態へ", "設定項目がない端末は、そのままで大丈夫です。") {
        Checklist("画面モード：ナチュラル／標準／sRGB")
        Checklist("明るさの自動調整：オフ")
        Checklist("夜間・読書・ブルーライト軽減：オフ")
        Checklist("鮮やか補正・動画HDR補正：オフ")
        Checklist("撮影中は明るさを固定")
        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Androidの画面設定を開く") }
    }
}

@Composable
private fun CameraConnectionStep(state: MainUiState, onUsbConnect: () -> Unit) =
    GuideBody("0 RAW/Controlで接続", "この順番でOM‑1 Mark IIとAndroidを直結します。") {
        Checklist("カメラの電源をOFF")
        Checklist("USB-Cデータケーブルを接続（充電専用ケーブルは不可）")
        Checklist("カメラの電源をON")
        Checklist("カメラ画面で［0 RAW/Control］を選び、OKボタン")
        Checklist("画質とカード1/2の振り分け設定を確認")
        Notice("カード1＝RAW／カード2＝JPEGなどの設定はそのままで構いません。アプリが両方を確認します。")
        Notice(
            "選択画面が出ない場合：MENU → e → 3. モニター/音/接続 → USBの設定 → " +
                "USB接続モード → 毎回確認",
        )
        Notice("USB PD／ストレージ／MTP／ウェブカメラではテザー操作できません。", warning = true)
        val connected = state.phase == ConnectionPhase.CONNECTED
        val connectionMessage = when (state.phase) {
            ConnectionPhase.CONNECTED -> "実機接続を確認しました：${state.identity?.displayName ?: "OM‑1 Mark II"}"
            ConnectionPhase.DEMO -> "現在はデモモードです。実機接続としては扱いません"
            ConnectionPhase.CONNECTING -> "OM‑1 Mark IIへ接続しています…"
            ConnectionPhase.REQUESTING_PERMISSION -> "AndroidのUSBアクセス許可を確認してください"
            else -> "まだOM‑1 Mark IIを確認できていません"
        }
        Notice(connectionMessage, warning = !connected)
        Button(
            onClick = onUsbConnect,
            enabled = state.phase !in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.REQUESTING_PERMISSION),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (connected) "実機を再確認" else "USB接続を確認") }
    }

@Composable
internal fun UsbConnectionGuide(
    statusMessage: String,
    cableAssessment: UsbCableAssessment?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OM‑1 Mark II 接続手順") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("USBを挿す前から、次の順番で操作してください。")
                ConnectionStep(1, "カメラの電源をOFF")
                ConnectionStep(2, "USB-CデータケーブルでAndroidと直結")
                ConnectionStep(3, "カメラの電源をON")
                ConnectionStep(4, "カメラ画面で［0 RAW/Control］を選択し、OK")
                ConnectionStep(5, "画質とカード1/2の振り分け設定を確認")
                Notice("カード1＝RAW／カード2＝JPEGでもOK。スマホ側の保存形式は撮影画面で選べます。")
                UsbCableNotice(cableAssessment)
                Notice(
                    "選択画面が出ない場合：MENU → e → 3. モニター/音/接続 → USBの設定 → " +
                        "USB接続モード → 毎回確認",
                )
                Notice("USB PD／ストレージ／MTP／ウェブカメラは選ばないでください。", warning = true)
                Text(
                    "現在：$statusMessage",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("0 RAW/Controlを選択済み・接続") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("あとで") }
        },
    )
}

@Composable
private fun UsbCableNotice(assessment: UsbCableAssessment?) {
    val color = when (assessment?.grade) {
        UsbCableGrade.RECOMMENDED -> Color(0xFFB7E4C7)
        UsbCableGrade.LIMITED -> Color(0xFFFFD6A5)
        UsbCableGrade.UNKNOWN, null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("ケーブル速度の確認", fontWeight = FontWeight.Bold)
        Text(
            assessment?.title ?: "未接続：USB 3.x対応データケーブルを推奨",
            color = color,
        )
        Text(
            assessment?.detail
                ?: "USB 2.0でも接続できる場合がありますが、RAW転送やライブビューの余裕を考え、USB 3.x対応・データ通信対応ケーブルを端末とカメラへ直結してください。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ConnectionStep(number: Int, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                number.toString(),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(text, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun GrayCardStep(
    state: MainUiState,
    onWbConfirmed: (Boolean) -> Unit,
    onGrayCardSkipped: (Boolean) -> Unit,
) {
    val connected = state.phase == ConnectionPhase.CONNECTED
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("カメラのWBを確認", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("無彩色のグレーカードを同じ光の下で枠いっぱいに入れ、カメラ側でワンタッチWBを実行します。")
        PreviewPane(
            bitmap = state.liveBitmap.takeIf { connected },
            overlay = null,
            highlightPercent = 0f,
            reviewing = false,
            calibration = DisplayCalibration(),
            showNeutralTarget = connected,
            gesturesEnabled = false,
            emptyMessage = "OM‑1 Mark IIの実機接続が必要です",
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        NeutralStatus(state.neutralPatch.takeIf { connected }, cameraConnected = connected)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.setupWbConfirmed,
                onCheckedChange = onWbConfirmed,
                enabled = connected && !state.setupGrayCardSkipped,
            )
            Text("カメラでワンタッチWBを実行しました", fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.setupGrayCardSkipped,
                onCheckedChange = onGrayCardSkipped,
                enabled = connected,
            )
            Text("グレーカード判定をスキップし、現在のWBを使用", fontSize = 13.sp)
        }
        Text(
            if (state.setupGrayCardSkipped) {
                "スキップした場合、撮影中にAWBの色味が変わる可能性があります。"
            } else {
                "「WBは良好」または「あと少し」の判定と、実行確認の両方で次へ進めます。"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DisplayAdjustmentStep(
    state: MainUiState,
    onCalibrationChange: (DisplayCalibration) -> Unit,
    onReset: () -> Unit,
) {
    val connected = state.phase == ConnectionPhase.CONNECTED
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Android表示を微調整", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("カメラのモニターと見比べ、違和感が減る方向へ少しだけ動かします。")
        PreviewPane(
            bitmap = state.liveBitmap.takeIf { connected },
            overlay = null,
            highlightPercent = 0f,
            reviewing = false,
            calibration = state.displayCalibration,
            emptyMessage = "OM‑1 Mark IIを再接続してください",
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        CalibrationSlider("青っぽい　← 色温度 →　黄／赤っぽい", state.displayCalibration.temperature, -1f..1f) {
            onCalibrationChange(state.displayCalibration.copy(temperature = it))
        }
        CalibrationSlider("緑っぽい　← 色かぶり →　赤紫っぽい", state.displayCalibration.tint, -1f..1f) {
            onCalibrationChange(state.displayCalibration.copy(tint = it))
        }
        CalibrationSlider("暗い　← 明るさ →　明るい", state.displayCalibration.brightness, -0.15f..0.15f) {
            onCalibrationChange(state.displayCalibration.copy(brightness = it))
        }
        TextButton(onClick = onReset) { Text("補正をリセット") }
        if (!connected) {
            Notice("実機が切断されています。再接続するまで完了できません", warning = true)
        } else if (!state.setupWbConfirmed && !state.setupGrayCardSkipped) {
            Notice("接続変更によりWB確認がリセットされました。「戻る」から再確認してください", warning = true)
        }
    }
}

@Composable
private fun CompleteStep(state: MainUiState) = GuideBody(
    "準備ができました",
    "この設定は端末に保存され、次回のプレビューにも使われます。",
) {
    StatusItem(
        if (state.setupGrayCardSkipped) "グレーカード判定：スキップ" else "カメラのワンタッチWB：確認済み",
        passed = !state.setupGrayCardSkipped,
    )
    StatusItem("Androidプレビュー補正：保存準備済み", passed = true)
    Text(
        "色温度 ${percent(state.displayCalibration.temperature)}　" +
            "色かぶり ${percent(state.displayCalibration.tint)}　" +
            "明るさ ${percent(state.displayCalibration.brightness / 0.15f)}",
        fontWeight = FontWeight.Bold,
    )
    Notice("端末の画面モードや明るさを変えた場合は、上部の「撮影前ガイド」からやり直してください。")
    if (state.phase != ConnectionPhase.CONNECTED) {
        Notice("OM‑1 Mark IIが切断されています。再接続してから開始してください", warning = true)
    } else if (!state.setupWbConfirmed && !state.setupGrayCardSkipped) {
        Notice("WB確認がリセットされています。前の手順へ戻って再確認してください", warning = true)
    }
}

@Composable
private fun CalibrationSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, fontSize = 12.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun NeutralStatus(result: NeutralPatchResult?, cameraConnected: Boolean) {
    val (message, color) = if (!cameraConnected) {
        "実機を接続してください" to Color(0xFFC58E8E)
    } else when (assessNeutralPatch(result)) {
        NeutralPatchAssessment.WAITING -> "ライブビューを待っています" to Color(0xFF9298A0)
        NeutralPatchAssessment.TOO_DARK -> "カードが暗すぎます。光を増やしてください" to Color(0xFFB7B2A8)
        NeutralPatchAssessment.TOO_BRIGHT -> "カードが明るすぎます。露出を下げてください" to Color(0xFFB7B2A8)
        NeutralPatchAssessment.GOOD -> "WBは良好です" to Color(0xFF8FB39C)
        NeutralPatchAssessment.CLOSE -> "あと少しです。必要ならカメラ側で微調整" to Color(0xFFB7B2A8)
        NeutralPatchAssessment.NEEDS_WB -> "カメラ側でワンタッチWBを実行してください" to Color(0xFFC58E8E)
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
        Text(message, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(10.dp))
    }
}

@Composable
private fun Checklist(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("✓", color = Color(0xFF8FB39C), fontWeight = FontWeight.Bold)
        Text(text)
    }
}

@Composable
private fun StatusItem(text: String, passed: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            if (passed) "✓" else "—",
            color = if (passed) Color(0xFF8FB39C) else Color(0xFFB7B2A8),
            fontWeight = FontWeight.Bold,
        )
        Text(text)
    }
}

@Composable
private fun Notice(text: String, warning: Boolean = false) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().background(
            if (warning) Color(0xFF332B2B) else Color(0xFF292D31),
            RoundedCornerShape(8.dp),
        ).padding(12.dp),
        color = if (warning) Color(0xFFD7B8B3) else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun percent(value: Float): String {
    val rounded = (value * 100f).roundToInt()
    return if (rounded > 0) "+$rounded%" else "$rounded%"
}
