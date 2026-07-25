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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
        color = Color(0xFF0B0E12),
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
                color = Color(0xFF151A21),
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
    GuideBody("カメラを準備して接続", "OM‑1 Mark II側を設定してからUSB-Cデータケーブルで直結します。") {
        Checklist("USB接続モード：RAW/Control")
        Checklist("画質：RAW+JPEG")
        Checklist("カメラのモニター調整は標準値に戻す")
        Checklist("充電専用ではないUSB-Cケーブルを使用")
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
        "実機を接続してください" to Color(0xFFFF6B6B)
    } else when (assessNeutralPatch(result)) {
        NeutralPatchAssessment.WAITING -> "ライブビューを待っています" to Color(0xFF9CA3AF)
        NeutralPatchAssessment.TOO_DARK -> "カードが暗すぎます。光を増やしてください" to Color(0xFFFFB000)
        NeutralPatchAssessment.TOO_BRIGHT -> "カードが明るすぎます。露出を下げてください" to Color(0xFFFFB000)
        NeutralPatchAssessment.GOOD -> "WBは良好です" to Color(0xFF4ADE80)
        NeutralPatchAssessment.CLOSE -> "あと少しです。必要ならカメラ側で微調整" to Color(0xFFFFB000)
        NeutralPatchAssessment.NEEDS_WB -> "カメラ側でワンタッチWBを実行してください" to Color(0xFFFF6B6B)
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
        Text(message, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(10.dp))
    }
}

@Composable
private fun Checklist(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("✓", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold)
        Text(text)
    }
}

@Composable
private fun StatusItem(text: String, passed: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            if (passed) "✓" else "—",
            color = if (passed) Color(0xFF4ADE80) else Color(0xFFFFB000),
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
            if (warning) Color(0xFF3A211B) else Color(0xFF222A34),
            RoundedCornerShape(8.dp),
        ).padding(12.dp),
        color = if (warning) Color(0xFFFFB4A7) else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun percent(value: Float): String {
    val rounded = (value * 100f).roundToInt()
    return if (rounded > 0) "+$rounded%" else "$rounded%"
}
