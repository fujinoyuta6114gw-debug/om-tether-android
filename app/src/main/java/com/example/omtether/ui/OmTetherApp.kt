package com.example.omtether.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import com.example.omtether.ConnectionPhase
import com.example.omtether.DisplayCalibration
import com.example.omtether.MainUiState
import com.example.omtether.camera.ExposureControl
import com.example.omtether.camera.ExposureFormatter
import com.example.omtether.camera.PtpScalar
import java.util.Locale
import kotlin.math.max

@Composable
fun OmTetherApp(
    state: MainUiState,
    onUsbConnect: () -> Unit,
    onDemo: () -> Unit,
    onCapture: () -> Unit,
    onExposureChange: (Int, PtpScalar) -> Unit,
    onHighlightEnabled: (Boolean) -> Unit,
    onHighlightThreshold: (Float) -> Unit,
    onOpenSetupGuide: () -> Unit,
    onDismissSetupGuide: () -> Unit,
    onNextSetupStep: () -> Unit,
    onPreviousSetupStep: () -> Unit,
    onDisplayCalibration: (DisplayCalibration) -> Unit,
    onResetDisplayCalibration: () -> Unit,
    onSetupWbConfirmed: (Boolean) -> Unit,
    onSetupGrayCardSkipped: (Boolean) -> Unit,
    onCompleteSetupGuide: () -> Unit,
    diagnostics: () -> String,
) {
    var diagnosticsText by remember { mutableStateOf<String?>(null) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            AppHeader(
                state = state,
                onUsbConnect = onUsbConnect,
                onDemo = onDemo,
                onSetupGuide = onOpenSetupGuide,
                onDiagnostics = { diagnosticsText = diagnostics() },
            )
            StatusLine(state.statusMessage)
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(10.dp),
            ) {
                if (maxWidth >= 720.dp) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PreviewPane(
                            bitmap = state.reviewBitmap ?: state.liveBitmap,
                            overlay = if (state.highlightEnabled) {
                                if (state.reviewBitmap != null) state.reviewHighlightOverlay else state.highlightOverlay
                            } else {
                                null
                            },
                            highlightPercent = if (state.reviewBitmap != null) state.reviewHighlightPercent else state.highlightPercent,
                            reviewing = state.reviewBitmap != null,
                            calibration = state.displayCalibration,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        ControlPanel(
                            state = state,
                            onCapture = onCapture,
                            onExposureChange = onExposureChange,
                            onHighlightEnabled = onHighlightEnabled,
                            onHighlightThreshold = onHighlightThreshold,
                            modifier = Modifier.width(360.dp).fillMaxHeight(),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PreviewPane(
                            bitmap = state.reviewBitmap ?: state.liveBitmap,
                            overlay = if (state.highlightEnabled) {
                                if (state.reviewBitmap != null) state.reviewHighlightOverlay else state.highlightOverlay
                            } else {
                                null
                            },
                            highlightPercent = if (state.reviewBitmap != null) state.reviewHighlightPercent else state.highlightPercent,
                            reviewing = state.reviewBitmap != null,
                            calibration = state.displayCalibration,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        ControlPanel(
                            state = state,
                            onCapture = onCapture,
                            onExposureChange = onExposureChange,
                            onHighlightEnabled = onHighlightEnabled,
                            onHighlightThreshold = onHighlightThreshold,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 355.dp),
                        )
                    }
                }
            }
        }
    }

    if (state.showSetupGuide) {
        SetupGuide(
            state = state,
            onUsbConnect = onUsbConnect,
            onDismiss = onDismissSetupGuide,
            onNext = onNextSetupStep,
            onPrevious = onPreviousSetupStep,
            onCalibrationChange = onDisplayCalibration,
            onResetCalibration = onResetDisplayCalibration,
            onWbConfirmed = onSetupWbConfirmed,
            onGrayCardSkipped = onSetupGrayCardSkipped,
            onComplete = onCompleteSetupGuide,
        )
    }

    diagnosticsText?.let { text ->
        DiagnosticsDialog(
            text = text,
            onDismiss = { diagnosticsText = null },
        )
    }
}

@Composable
private fun AppHeader(
    state: MainUiState,
    onUsbConnect: () -> Unit,
    onDemo: () -> Unit,
    onSetupGuide: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val canSwitchController = !state.isCapturing &&
        state.phase !in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.REQUESTING_PERMISSION)
    Column(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0E1217)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "OM TETHER",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.width(10.dp))
            PhaseChip(state.phase)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSetupGuide) { Text("撮影前ガイド") }
            TextButton(onClick = onDiagnostics) { Text("診断") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.identity?.displayName ?: "OM‑1 Mark II · USB-C",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
            )
            OutlinedButton(
                onClick = onDemo,
                enabled = canSwitchController,
                modifier = Modifier.height(38.dp),
                contentPadding = ButtonDefaults.ContentPadding,
            ) { Text("デモ") }
            Spacer(Modifier.width(7.dp))
            Button(
                onClick = onUsbConnect,
                enabled = canSwitchController,
                modifier = Modifier.height(38.dp),
            ) { Text("USB接続") }
        }
    }
}

@Composable
private fun PhaseChip(phase: ConnectionPhase) {
    val (label, color) = when (phase) {
        ConnectionPhase.CONNECTED -> "接続中" to Color(0xFF4ADE80)
        ConnectionPhase.DEMO -> "DEMO" to Color(0xFF7DD3FC)
        ConnectionPhase.CONNECTING -> "接続処理" to Color(0xFFFFB000)
        ConnectionPhase.REQUESTING_PERMISSION -> "許可待ち" to Color(0xFFFFB000)
        ConnectionPhase.ERROR -> "エラー" to Color(0xFFFF6B6B)
        ConnectionPhase.DISCONNECTED -> "未接続" to Color(0xFF9CA3AF)
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusLine(message: String) {
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth().background(Color(0xFF171C22)).padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun PreviewPane(
    bitmap: Bitmap?,
    overlay: Bitmap?,
    highlightPercent: Float,
    reviewing: Boolean,
    calibration: DisplayCalibration = DisplayCalibration(),
    showNeutralTarget: Boolean = false,
    gesturesEnabled: Boolean = true,
    emptyMessage: String = "USB接続またはデモを開始してください",
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val gestureModifier = if (gesturesEnabled) {
        Modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                val nextScale = (scale * zoom).coerceIn(1f, 8f)
                scale = nextScale
                offset = if (nextScale == 1f) Offset.Zero else offset + pan
            }
        }
    } else {
        Modifier
    }
    Surface(
        modifier = modifier,
        color = Color.Black,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().then(gestureModifier),
        ) {
            if (bitmap == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("LIVE VIEW", color = Color(0xFF6B7280), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(emptyMessage, color = Color(0xFF9CA3AF), fontSize = 13.sp)
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "カメラのライブビュー",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        colorFilter = previewColorFilter(calibration),
                    )
                    overlay?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "白飛び警告",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
            if (showNeutralTarget) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.34f)
                        .fillMaxHeight(0.34f)
                        .border(2.dp, Color(0xFFFFB000), RoundedCornerShape(8.dp)),
                )
                PreviewBadge(
                    text = "グレーカードを枠内へ",
                    warning = true,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 44.dp),
                )
            }

            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (reviewing) PreviewBadge("撮影プレビュー", warning = true)
                PreviewBadge("×%.1f".format(Locale.US, scale))
                if (overlay != null) PreviewBadge("白飛び %.1f%%".format(Locale.US, highlightPercent), warning = true)
            }
            if (gesturesEnabled) {
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    TextButton(onClick = { scale = (scale * 1.5f).coerceAtMost(8f) }) { Text("＋") }
                    TextButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(1f); if (scale == 1f) offset = Offset.Zero }) { Text("−") }
                    TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("リセット") }
                }
            }
        }
    }
}

private fun previewColorFilter(calibration: DisplayCalibration): ColorFilter? {
    if (calibration == DisplayCalibration()) return null
    val warm = calibration.temperature
    val tint = calibration.tint
    val offset = calibration.brightness * 255f
    val redGain = (1f + warm * 0.12f + tint * 0.04f).coerceIn(0.75f, 1.25f)
    val greenGain = (1f - tint * 0.10f).coerceIn(0.75f, 1.25f)
    val blueGain = (1f - warm * 0.12f + tint * 0.04f).coerceIn(0.75f, 1.25f)
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                redGain, 0f, 0f, 0f, offset,
                0f, greenGain, 0f, 0f, offset,
                0f, 0f, blueGain, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

@Composable
private fun PreviewBadge(
    text: String,
    warning: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = if (warning) Color(0xCC8A1C14) else Color(0xB312161B),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 11.sp)
    }
}

@Composable
private fun ControlPanel(
    state: MainUiState,
    onCapture: () -> Unit,
    onExposureChange: (Int, PtpScalar) -> Unit,
    onHighlightEnabled: (Boolean) -> Unit,
    onHighlightThreshold: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("撮影コントロール", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("JPG / ORF 自動保存", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (state.exposureControls.isEmpty()) {
                        item {
                            Text(
                                "露出値はカメラ接続後に読み取ります",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 14.dp),
                            )
                        }
                    }
                    items(state.exposureControls, key = { it.propertyCode }) { control ->
                        ExposureRow(
                            control = control,
                            enabled = !state.isCapturing,
                            onExposureChange = onExposureChange,
                        )
                    }
                }
                Column(
                    modifier = Modifier.widthIn(min = 132.dp, max = 165.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("輝度ヒストグラム", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Histogram(
                        if (state.reviewBitmap != null) state.reviewHistogram else state.histogram,
                        Modifier.fillMaxWidth().height(65.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("白飛び", modifier = Modifier.weight(1f), fontSize = 12.sp)
                        Switch(
                            checked = state.highlightEnabled,
                            onCheckedChange = onHighlightEnabled,
                            modifier = Modifier.height(28.dp),
                        )
                    }
                    Text(
                        "しきい値 ${(state.highlightThreshold * 100).toInt()}%",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = state.highlightThreshold,
                        onValueChange = onHighlightThreshold,
                        valueRange = 0.90f..1f,
                        modifier = Modifier.height(26.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("保存先: Pictures/OM Tether", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        state.lastCapture?.files?.takeIf { it.isNotEmpty() }?.joinToString { it.filename }
                            ?: "まだ保存していません",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp,
                    )
                    state.lastCapture?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
                        Text(
                            "警告: ${failures.first()}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 10.sp,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onCapture,
                    enabled = !state.isCapturing && state.phase in setOf(ConnectionPhase.CONNECTED, ConnectionPhase.DEMO),
                    modifier = Modifier.size(78.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFB000),
                        contentColor = Color(0xFF201800),
                    ),
                ) {
                    if (state.isCapturing) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color(0xFF201800))
                    } else {
                        Text("撮影", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExposureRow(
    control: ExposureControl,
    enabled: Boolean,
    onExposureChange: (Int, PtpScalar) -> Unit,
) {
    var expanded by remember(control.propertyCode, control.current.raw) { mutableStateOf(false) }
    val currentLabel = control.options.firstOrNull { it.value.raw == control.current.raw }?.label
        ?: ExposureFormatter.format(control.propertyCode, control.current.raw)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            control.title,
            modifier = Modifier.width(70.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = control.writable && enabled,
                modifier = Modifier.fillMaxWidth().height(36.dp),
            ) {
                Text(currentLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                control.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            expanded = false
                            onExposureChange(control.propertyCode, option.value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Histogram(histogram: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color(0xFF090B0E), RoundedCornerShape(5.dp)).border(1.dp, Color(0xFF2E3742), RoundedCornerShape(5.dp))) {
        val peak = max(1, histogram.maxOrNull() ?: 1)
        val barWidth = size.width / histogram.size.coerceAtLeast(1)
        histogram.forEachIndexed { index, count ->
            val x = index * barWidth
            val y = size.height - (count.toFloat() / peak * size.height)
            drawLine(
                color = Color(0xFFCBD5E1),
                start = Offset(x, size.height),
                end = Offset(x, y),
                strokeWidth = max(1f, barWidth),
            )
        }
    }
}

@Composable
private fun DiagnosticsDialog(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("USB / PTP 診断") },
        text = {
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        },
        confirmButton = {
            Button(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("コピー") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
    )
}
