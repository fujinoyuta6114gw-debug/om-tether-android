package com.example.omtether.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import com.example.omtether.ConnectionPhase
import com.example.omtether.CaptureQueueState
import com.example.omtether.DisplayCalibration
import com.example.omtether.MainUiState
import com.example.omtether.SaveProgress
import com.example.omtether.SaveProgressStage
import com.example.omtether.camera.ExposureControl
import com.example.omtether.camera.ExposureFormatter
import com.example.omtether.camera.PhoneSaveFormat
import com.example.omtether.camera.PtpScalar
import com.example.omtether.history.CameraStorageSlot
import com.example.omtether.history.CaptureHistoryItem
import com.example.omtether.history.CaptureTimeSource
import com.example.omtether.history.PhotoMetadataFormatter
import com.example.omtether.history.SmartphoneSaveState
import com.example.omtether.storage.CapturePathPolicy
import java.util.Locale
import kotlin.math.max

@Composable
fun OmTetherApp(
    state: MainUiState,
    onUsbConnect: () -> Unit,
    onSetupUsbConnect: () -> Unit,
    onDismissConnectionGuide: () -> Unit,
    onConfirmConnectionGuide: () -> Unit,
    onRestartLiveView: () -> Unit,
    onDemo: () -> Unit,
    onCapture: () -> Unit,
    onCaptureHistorySelect: (String?) -> Unit,
    onPhoneSaveFormatChange: (PhoneSaveFormat) -> Unit,
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
    var showTips by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            AppHeader(
                state = state,
                onUsbConnect = onUsbConnect,
                onDemo = onDemo,
                onTips = { showTips = true },
                onDiagnostics = { diagnosticsText = diagnostics() },
            )
            StatusLine(
                message = state.statusMessage,
                actionLabel = if (state.liveViewIssue != null) "再接続" else null,
                onAction = onRestartLiveView,
            )
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
                            emptyMessage = state.liveViewIssue ?: "USB接続またはデモを開始してください",
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        ControlPanel(
                            state = state,
                            onCapture = onCapture,
                            onPhoneSaveFormatChange = onPhoneSaveFormatChange,
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
                            emptyMessage = state.liveViewIssue ?: "USB接続またはデモを開始してください",
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        ControlPanel(
                            state = state,
                            onCapture = onCapture,
                            onPhoneSaveFormatChange = onPhoneSaveFormatChange,
                            onExposureChange = onExposureChange,
                            onHighlightEnabled = onHighlightEnabled,
                            onHighlightThreshold = onHighlightThreshold,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 380.dp),
                        )
                    }
                }
            }
            if (state.captureHistory.isNotEmpty()) {
                CaptureHistoryStrip(
                    history = state.captureHistory,
                    selectedId = state.selectedHistoryId,
                    onSelect = { onCaptureHistorySelect(it.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (state.showSetupGuide) {
        SetupGuide(
            state = state,
            onUsbConnect = onSetupUsbConnect,
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

    if (state.showConnectionGuide) {
        UsbConnectionGuide(
            statusMessage = state.statusMessage,
            cableAssessment = state.usbCableAssessment,
            onDismiss = onDismissConnectionGuide,
            onConfirm = onConfirmConnectionGuide,
        )
    }

    if (showTips) {
        TipsDialog(
            state = state,
            onOpenSetupGuide = {
                showTips = false
                onOpenSetupGuide()
            },
            onOpenConnectionGuide = {
                showTips = false
                onUsbConnect()
            },
            onDismiss = { showTips = false },
        )
    }

    diagnosticsText?.let { text ->
        DiagnosticsDialog(
            text = text,
            onDismiss = { diagnosticsText = null },
        )
    }

    state.selectedHistoryId
        ?.let { selectedId -> state.captureHistory.firstOrNull { it.id == selectedId } }
        ?.let { selected ->
            CaptureHistoryDetailDialog(
                item = selected,
                onDismiss = { onCaptureHistorySelect(null) },
            )
        }
}

@Composable
private fun AppHeader(
    state: MainUiState,
    onUsbConnect: () -> Unit,
    onDemo: () -> Unit,
    onTips: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val canSwitchController = !state.isCapturing &&
        state.phase !in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.REQUESTING_PERMISSION)
    Column(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF15171A)).padding(horizontal = 12.dp, vertical = 8.dp),
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
            TextButton(onClick = onTips, contentPadding = PaddingValues(horizontal = 7.dp)) {
                Text("！ TIPS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(
                onClick = onDiagnostics,
                contentPadding = PaddingValues(horizontal = 7.dp),
            ) { Text("診断", fontSize = 12.sp) }
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
        ConnectionPhase.CONNECTED -> "接続中" to Color(0xFF8FB39C)
        ConnectionPhase.DEMO -> "DEMO" to Color(0xFF9DA7B5)
        ConnectionPhase.CONNECTING -> "接続処理" to Color(0xFFB7B2A8)
        ConnectionPhase.REQUESTING_PERMISSION -> "許可待ち" to Color(0xFFB7B2A8)
        ConnectionPhase.ERROR -> "エラー" to Color(0xFFC58E8E)
        ConnectionPhase.DISCONNECTED -> "未接続" to Color(0xFF8A9098)
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
private fun StatusLine(
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF202327)).padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        actionLabel?.let {
            TextButton(onClick = onAction) { Text(it) }
        }
    }
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
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
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
private fun CaptureHistoryStrip(
    history: List<CaptureHistoryItem>,
    selectedId: String?,
    onSelect: (CaptureHistoryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(94.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "撮影履歴",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "直近 ${history.size}/20枚 · タップで実EXIF",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(end = 4.dp),
            ) {
                items(history, key = CaptureHistoryItem::id) { item ->
                    CaptureHistoryThumbnail(
                        item = item,
                        selected = item.id == selectedId,
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureHistoryThumbnail(
    item: CaptureHistoryItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    }
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(shape)
            .background(Color(0xFF101214))
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick),
    ) {
        item.thumbnail?.let { thumbnail ->
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = "${item.filename}のサムネイル",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: Text(
            text = PhotoMetadataFormatter.format(item.fileFormat, item.isPreviewFallback),
            modifier = Modifier.align(Alignment.Center).padding(4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            shape = RoundedCornerShape(4.dp),
            color = Color(0xD9202327),
        ) {
            Text(
                text = when (item.fileFormat) {
                    com.example.omtether.history.CaptureFileFormat.JPEG -> "JPG"
                    com.example.omtether.history.CaptureFileFormat.ORF -> "ORF"
                    com.example.omtether.history.CaptureFileFormat.UNKNOWN -> "?"
                },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            shape = CircleShape,
            color = when (item.smartphoneSaveState) {
                SmartphoneSaveState.SAVING -> Color(0xFFB7B2A8)
                SmartphoneSaveState.SAVED -> Color(0xFF8FB39C)
                SmartphoneSaveState.FAILED -> Color(0xFFC58E8E)
            },
        ) {
            Text(
                text = when (item.smartphoneSaveState) {
                    SmartphoneSaveState.SAVING -> "…"
                    SmartphoneSaveState.SAVED -> "✓"
                    SmartphoneSaveState.FAILED -> "!"
                },
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                color = Color(0xFF101214),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
            )
        }
        item.sourceCardSlot?.let { slot ->
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color(0xD9202327),
            ) {
                Text(
                    "C$slot",
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    fontSize = 8.sp,
                )
            }
        }
    }
}

@Composable
private fun CaptureHistoryDetailDialog(
    item: CaptureHistoryItem,
    onDismiss: () -> Unit,
) {
    val metadata = item.metadata
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("撮影詳細")
                Text(
                    item.filename,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    color = Color.Black,
                    shape = RoundedCornerShape(9.dp),
                ) {
                    item.thumbnail?.let { thumbnail ->
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = "${item.filename}の撮影画像",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } ?: Box(contentAlignment = Alignment.Center) {
                        Text(
                            "サムネイルなし",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
                Surface(
                    color = if (metadata.hasActualExif) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    },
                    shape = RoundedCornerShape(7.dp),
                ) {
                    Text(
                        text = if (metadata.hasActualExif) {
                            "保存対象ファイルから読み取った実EXIF"
                        } else {
                            "撮影EXIFを読み取れませんでした"
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
                        color = if (metadata.hasActualExif) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                HistoryDetailRow("F値", PhotoMetadataFormatter.aperture(metadata.apertureFNumber))
                HistoryDetailRow(
                    "シャッター",
                    PhotoMetadataFormatter.exposureTime(metadata.exposureTimeSeconds),
                )
                HistoryDetailRow("ISO", PhotoMetadataFormatter.iso(metadata.iso))
                HistoryDetailRow(
                    "露出補正",
                    PhotoMetadataFormatter.exposureBias(metadata.exposureBiasEv),
                )
                HistoryDetailRow(
                    "焦点距離",
                    PhotoMetadataFormatter.focalLength(metadata.focalLengthMm),
                )
                val timeSuffix = when (metadata.captureTimeSource) {
                    CaptureTimeSource.EXIF -> ""
                    CaptureTimeSource.PTP_OBJECT_INFO -> "（PTP情報）"
                    CaptureTimeSource.UNAVAILABLE -> ""
                }
                HistoryDetailRow(
                    "撮影時刻",
                    PhotoMetadataFormatter.capturedAt(metadata.capturedAt) + timeSuffix,
                )
                HistoryDetailRow(
                    "形式",
                    PhotoMetadataFormatter.format(item.fileFormat, item.isPreviewFallback),
                )
                HistoryDetailRow(
                    "保存元",
                    CameraStorageSlot.label(item.sourceStorageId, item.sourceCardSlot),
                )
                HistoryDetailRow(
                    "スマホ保存",
                    PhotoMetadataFormatter.smartphoneSaveState(item),
                )
                item.metadataWarning?.let { warning ->
                    Text(
                        warning,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 10.sp,
                    )
                }
                if (metadata.captureTimeSource == CaptureTimeSource.PTP_OBJECT_INFO) {
                    Text(
                        "撮影時刻のみPTPの画像情報を使用。露出値は現在のカメラ設定で補完していません。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
    )
}

@Composable
private fun HistoryDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.width(82.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PreviewBadge(
    text: String,
    warning: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = if (warning) Color(0xCC6F3E3E) else Color(0xCC202327),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 11.sp)
    }
}

@Composable
private fun ControlPanel(
    state: MainUiState,
    onCapture: () -> Unit,
    onPhoneSaveFormatChange: (PhoneSaveFormat) -> Unit,
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
                Text(
                    when {
                        state.captureQueue.isBusy -> "キュー処理中"
                        state.exposureSyncActive -> "本体撮影・同期中"
                        else -> "本体撮影待機"
                    },
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SaveFormatChoice(
                    label = "スマホにJPEG",
                    selected = state.phoneSaveFormat == PhoneSaveFormat.JPEG,
                    enabled = !state.isCapturing && !state.captureQueue.isBusy,
                    onClick = { onPhoneSaveFormatChange(PhoneSaveFormat.JPEG) },
                    modifier = Modifier.weight(1f),
                )
                SaveFormatChoice(
                    label = "スマホにRAW",
                    selected = state.phoneSaveFormat == PhoneSaveFormat.RAW,
                    enabled = !state.isCapturing && !state.captureQueue.isBusy,
                    onClick = { onPhoneSaveFormatChange(PhoneSaveFormat.RAW) },
                    modifier = Modifier.weight(1f),
                )
            }
            state.saveProgress?.let { progress ->
                SaveProgressIndicator(progress)
            }
            if (state.captureQueue.hasActivity) {
                CaptureQueueIndicator(state.captureQueue)
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
                            enabled = !state.isCapturing && !state.captureQueue.isBusy,
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
                    Text(
                        "専用保存先: " +
                            (state.lastCapture?.files?.firstOrNull()?.relativePath
                                ?: CapturePathPolicy.DISPLAY_PATH),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                    enabled = !state.isCapturing &&
                        !state.captureQueue.isBusy &&
                        state.phase in setOf(ConnectionPhase.CONNECTED, ConnectionPhase.DEMO),
                    modifier = Modifier.size(78.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    if (state.isCapturing) {
                        CircularProgressIndicator(
                            Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("撮影", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureQueueIndicator(queue: CaptureQueueState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "撮影キュー",
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        queue.activeFilename != null -> "転送中"
                        queue.waitingCount > 0 -> "確認中"
                        queue.failedCount > 0 -> "完了・要確認"
                        else -> "完了"
                    },
                    fontSize = 10.sp,
                    color = if (queue.failedCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            queue.activeFilename?.let { filename ->
                Text(
                    filename,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                QueueMetric("待機", queue.waitingCount)
                QueueMetric("転送中", if (queue.activeFilename != null) 1 else 0)
                QueueMetric("保存済", queue.savedCount)
                QueueMetric("失敗", queue.failedCount, error = queue.failedCount > 0)
            }
        }
    }
}

@Composable
private fun QueueMetric(label: String, count: Int, error: Boolean = false) {
    Text(
        "$label $count",
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SaveProgressIndicator(progress: SaveProgress) {
    val title = when (progress.stage) {
        SaveProgressStage.PREPARING -> "撮影データを確認しています…"
        SaveProgressStage.DOWNLOADING -> "カメラから転送中"
        SaveProgressStage.WRITING -> "スマホへ保存中"
        SaveProgressStage.FINALIZING -> "保存を最終処理中…"
    }
    val fraction = progress.fraction
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (fraction != null) {
                    Text(
                        "${(fraction * 100f).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    progress.remainingSeconds?.let { seconds ->
                        Text(
                            " · 残り約${formatRemainingTime(seconds)}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (progress.totalBytes > 0L) {
                Text(
                    buildString {
                        progress.filename?.takeIf { it.isNotBlank() }?.let {
                            append(it)
                            append(" · ")
                        }
                        append(formatBytes(progress.completedBytes))
                        append(" / ")
                        append(formatBytes(progress.totalBytes))
                        progress.bytesPerSecond?.let {
                            append(" · ")
                            append(formatBytes(it))
                            append("/秒")
                        }
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatRemainingTime(seconds: Int): String = when {
    seconds < 60 -> "${seconds}秒"
    seconds < 60 * 60 -> "${seconds / 60}分${seconds % 60}秒"
    else -> "${seconds / 3600}時間${(seconds % 3600) / 60}分"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        "%.1f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L ->
        "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024L ->
        "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun SaveFormatChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(label, fontSize = 11.sp, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(label, fontSize = 11.sp, maxLines = 1)
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
    Canvas(modifier = modifier.background(Color(0xFF111315), RoundedCornerShape(5.dp)).border(1.dp, Color(0xFF3B3F45), RoundedCornerShape(5.dp))) {
        val peak = max(1, histogram.maxOrNull() ?: 1)
        val barWidth = size.width / histogram.size.coerceAtLeast(1)
        histogram.forEachIndexed { index, count ->
            val x = index * barWidth
            val y = size.height - (count.toFloat() / peak * size.height)
            drawLine(
                color = Color(0xFFC8CCD2),
                start = Offset(x, size.height),
                end = Offset(x, y),
                strokeWidth = max(1f, barWidth),
            )
        }
    }
}

@Composable
private fun TipsDialog(
    state: MainUiState,
    onOpenSetupGuide: () -> Unit,
    onOpenConnectionGuide: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("！ TIPS") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TipSection(
                    title = "USB接続",
                    body = "カメラで［0 RAW/Control］を選択します。USB PD／ストレージ／MTP／ウェブカメラではテザー撮影できません。",
                )
                TipSection(
                    title = "接続が切れたとき",
                    body = "カードアクセスランプが消えていることを確認し、画面の「再接続」を押してください。直らない場合はカメラの電源を入れ直し、0 RAW/Controlを選び直します。",
                )
                TipSection(
                    title = "スマホへの保存",
                    body = when (state.phoneSaveFormat) {
                        PhoneSaveFormat.JPEG ->
                            "現在はJPEG保存です。カード1/2にあるフルJPEGを優先し、ない場合だけRAW内プレビューを利用します。"
                        PhoneSaveFormat.RAW ->
                            "現在はRAW保存です。カード1/2からORFを探してスマホの専用日付フォルダへ保存します。"
                    },
                )
                TipSection(
                    title = "撮影キュー",
                    body = "本体シャッターの連写は待機列へ積み、1撮影ずつスマホへ保存します。待機・転送中・保存済み・失敗の件数は撮影コントロール内で確認できます。",
                )
                TipSection(
                    title = "カメラのカード設定",
                    body = "カード1＝RAW／カード2＝JPEGなど、カメラ側の振り分けは変更しません。アプリは両方のカードを確認します。",
                )
                TipSection(
                    title = "ケーブル",
                    body = state.usbCableAssessment?.let { "${it.title}。${it.detail}" }
                        ?: "USB 3.x対応のデータ通信ケーブルで、ハブを挟まず直結することを推奨します。",
                )
                TipSection(
                    title = "露出と表示",
                    body = "絞り・シャッター速度・ISOはカメラから読んだ対応値だけを表示します。画面の色合わせは「撮影前ガイド」で調整できます。",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenConnectionGuide,
                        modifier = Modifier.weight(1f),
                    ) { Text("接続手順") }
                    OutlinedButton(
                        onClick = onOpenSetupGuide,
                        modifier = Modifier.weight(1f),
                    ) { Text("撮影前ガイド") }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("閉じる") }
        },
    )
}

@Composable
private fun TipSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(
            body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
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
