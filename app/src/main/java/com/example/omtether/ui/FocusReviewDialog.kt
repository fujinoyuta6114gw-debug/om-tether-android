package com.example.omtether.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.omtether.focus.FaceRegion
import com.example.omtether.focus.FaceRegionDetector
import com.example.omtether.focus.FocusFitGeometry
import com.example.omtether.focus.FocusMaskAnalysis
import com.example.omtether.focus.FocusMaskResult
import com.example.omtether.focus.FocusViewportMath
import com.example.omtether.focus.NormalizedFocusPoint
import com.example.omtether.history.CaptureHistoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun FocusReviewDialog(
    history: List<CaptureHistoryItem>,
    startId: String,
    rememberedCenterX: Float,
    rememberedCenterY: Float,
    onRememberPosition: (Float, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    // Only the two newest captures retain review Bitmaps. Reverse them here so the arrows follow
    // the natural old -> new direction while the history strip remains new -> old.
    val comparisonItems = history
        .asSequence()
        .filter { it.focusReviewBitmap != null }
        .take(MAX_COMPARISON_IMAGES)
        .toList()
        .asReversed()
    if (comparisonItems.isEmpty()) {
        LaunchedEffect(startId) { onDismiss() }
        return
    }

    var activeId by rememberSaveable(startId) {
        mutableStateOf(
            startId.takeIf { requested -> comparisonItems.any { it.id == requested } }
                ?: comparisonItems.last().id,
        )
    }
    val activeIndex = comparisonItems.indexOfFirst { it.id == activeId }
        .takeIf { it >= 0 }
        ?: comparisonItems.lastIndex
    val active = comparisonItems[activeIndex]
    val bitmap = requireNotNull(active.focusReviewBitmap)

    var centerX by rememberSaveable(startId) {
        mutableFloatStateOf(rememberedCenterX.coerceIn(0f, 1f))
    }
    var centerY by rememberSaveable(startId) {
        mutableFloatStateOf(rememberedCenterY.coerceIn(0f, 1f))
    }
    var zoom by rememberSaveable(startId) { mutableFloatStateOf(1f) }
    var oneToOneMode by rememberSaveable(startId) { mutableStateOf(true) }
    var selectPointMode by rememberSaveable(startId) { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val geometry = remember(viewportSize, bitmap.width, bitmap.height) {
        FocusViewportMath.fitGeometry(
            viewportWidth = viewportSize.width,
            viewportHeight = viewportSize.height,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
        )
    }
    val oneToOneZoom = FocusViewportMath.oneToOneZoom(geometry)
    val minimumZoom = min(1f, oneToOneZoom)
    val maximumZoom = max(16f, oneToOneZoom * 4f)

    LaunchedEffect(active.id, viewportSize, oneToOneMode) {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        if (oneToOneMode) zoom = oneToOneZoom.coerceIn(minimumZoom, maximumZoom)
        val clamped = FocusViewportMath.clampCenter(
            center = NormalizedFocusPoint(centerX, centerY),
            geometry = geometry,
            zoom = zoom,
        )
        centerX = clamped.x
        centerY = clamped.y
    }

    var maskEnabled by rememberSaveable(startId) { mutableStateOf(false) }
    var maskSensitivity by rememberSaveable(startId) { mutableFloatStateOf(0.55f) }
    val sensitivityStep = (maskSensitivity * 10f).roundToInt().coerceIn(0, 10)
    var maskResult by remember { mutableStateOf<FocusMaskResult?>(null) }
    var maskLoading by remember { mutableStateOf(false) }
    LaunchedEffect(active.id, maskEnabled, sensitivityStep) {
        maskResult = null
        if (!maskEnabled) {
            maskLoading = false
            return@LaunchedEffect
        }
        maskLoading = true
        // Avoid starting several CPU analyses while the slider is still moving.
        delay(MASK_SLIDER_DEBOUNCE_MS)
        try {
            maskResult = withContext(Dispatchers.Default) {
                FocusMaskAnalysis.createMask(bitmap, sensitivityStep / 10f)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            maskResult = null
        } finally {
            maskLoading = false
        }
    }

    var faces by remember { mutableStateOf<List<FaceRegion>>(emptyList()) }
    var facesLoading by remember { mutableStateOf(false) }
    var nextFaceIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(active.id) {
        faces = emptyList()
        nextFaceIndex = 0
        facesLoading = true
        faces = withContext(Dispatchers.Default) { FaceRegionDetector.detect(bitmap) }
        facesLoading = false
    }

    fun rememberCurrentPosition() {
        onRememberPosition(centerX.coerceIn(0f, 1f), centerY.coerceIn(0f, 1f))
    }

    fun jumpTo(point: NormalizedFocusPoint, useOneToOne: Boolean = true) {
        selectPointMode = false
        oneToOneMode = useOneToOne
        val targetZoom = if (useOneToOne) oneToOneZoom else zoom
        zoom = targetZoom.coerceIn(minimumZoom, maximumZoom)
        val clamped = FocusViewportMath.clampCenter(point, geometry, zoom)
        centerX = clamped.x
        centerY = clamped.y
        rememberCurrentPosition()
    }

    fun switchTo(index: Int) {
        if (index !in comparisonItems.indices) return
        rememberCurrentPosition()
        activeId = comparisonItems[index].id
        selectPointMode = false
    }

    Dialog(
        onDismissRequest = {
            rememberCurrentPosition()
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0F1113),
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                FocusReviewHeader(
                    item = active,
                    currentIndex = activeIndex,
                    total = comparisonItems.size,
                    canGoPrevious = activeIndex > 0,
                    canGoNext = activeIndex < comparisonItems.lastIndex,
                    onPrevious = { switchTo(activeIndex - 1) },
                    onNext = { switchTo(activeIndex + 1) },
                    onDismiss = {
                        rememberCurrentPosition()
                        onDismiss()
                    },
                )
                FocusImageViewport(
                    modifier = Modifier.weight(1f),
                    bitmap = bitmap,
                    maskResult = maskResult,
                    maskLoading = maskLoading,
                    geometry = geometry,
                    zoom = zoom,
                    center = NormalizedFocusPoint(centerX, centerY),
                    selectPointMode = selectPointMode,
                    canGoPrevious = activeIndex > 0,
                    canGoNext = activeIndex < comparisonItems.lastIndex,
                    onViewportSize = { viewportSize = it },
                    onTransform = { pan, zoomChange ->
                        val nextZoom = (zoom * zoomChange).coerceIn(minimumZoom, maximumZoom)
                        oneToOneMode = abs(nextZoom - oneToOneZoom) < 0.02f
                        val moved = FocusViewportMath.panCenter(
                            center = NormalizedFocusPoint(centerX, centerY),
                            panX = pan.x,
                            panY = pan.y,
                            geometry = geometry,
                            zoom = nextZoom,
                        )
                        zoom = nextZoom
                        centerX = moved.x
                        centerY = moved.y
                    },
                    onPointSelected = { point -> jumpTo(point) },
                    onPrevious = { switchTo(activeIndex - 1) },
                    onNext = { switchTo(activeIndex + 1) },
                )
                FocusReviewTools(
                    pixelPercent = FocusViewportMath.displayedPixelPercent(geometry, zoom),
                    oneToOneActive = abs(zoom - oneToOneZoom) < 0.02f,
                    selectPointMode = selectPointMode,
                    maskEnabled = maskEnabled,
                    maskSensitivity = maskSensitivity,
                    maskResult = maskResult,
                    maskLoading = maskLoading,
                    faces = faces,
                    facesLoading = facesLoading,
                    onOneToOne = {
                        oneToOneMode = true
                        zoom = oneToOneZoom.coerceIn(minimumZoom, maximumZoom)
                    },
                    onFit = {
                        oneToOneMode = false
                        selectPointMode = false
                        zoom = 1f
                        centerX = 0.5f
                        centerY = 0.5f
                    },
                    onPreviousPosition = {
                        jumpTo(
                            NormalizedFocusPoint(
                                rememberedCenterX.coerceIn(0f, 1f),
                                rememberedCenterY.coerceIn(0f, 1f),
                            ),
                        )
                    },
                    onCenter = { jumpTo(NormalizedFocusPoint(0.5f, 0.5f)) },
                    onFace = {
                        if (faces.isNotEmpty()) {
                            val face = faces[nextFaceIndex % faces.size]
                            nextFaceIndex = (nextFaceIndex + 1) % faces.size
                            jumpTo(NormalizedFocusPoint(face.centerX, face.centerY))
                        }
                    },
                    onSelectPoint = {
                        selectPointMode = !selectPointMode
                        if (selectPointMode) {
                            oneToOneMode = false
                            zoom = 1f
                            centerX = 0.5f
                            centerY = 0.5f
                        }
                    },
                    onMaskToggle = { maskEnabled = !maskEnabled },
                    onMaskSensitivity = { maskSensitivity = it },
                )
                FocusReviewSourceNote(active = active, bitmap = bitmap)
            }
        }
    }
}

@Composable
private fun FocusReviewHeader(
    item: CaptureHistoryItem,
    currentIndex: Int,
    total: Int,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(color = Color(0xFF1B1E22)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onPrevious,
                enabled = canGoPrevious,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("‹ 前") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ピント確認", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "${currentIndex + 1}/$total · ${item.filename}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onNext,
                enabled = canGoNext,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("次 ›") }
            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("閉じる") }
        }
    }
}

@Composable
private fun FocusImageViewport(
    modifier: Modifier = Modifier,
    bitmap: Bitmap,
    maskResult: FocusMaskResult?,
    maskLoading: Boolean,
    geometry: FocusFitGeometry,
    zoom: Float,
    center: NormalizedFocusPoint,
    selectPointMode: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onViewportSize: (IntSize) -> Unit,
    onTransform: (Offset, Float) -> Unit,
    onPointSelected: (NormalizedFocusPoint) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val translation = FocusViewportMath.translationForCenter(center, geometry, zoom)
    val interaction = Modifier.pointerInput(
        bitmap,
        selectPointMode,
        geometry,
    ) {
        if (selectPointMode) {
            detectTapGestures { tap ->
                FocusViewportMath.normalizedPointAtViewport(
                    viewportX = tap.x,
                    viewportY = tap.y,
                    center = center,
                    geometry = geometry,
                    zoom = zoom,
                )?.let(onPointSelected)
            }
        } else {
            detectTransformGestures { _, pan, zoomChange, _ ->
                onTransform(pan, zoomChange)
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged(onViewportSize)
            .then(interaction),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = translation.x
                translationY = translation.y
            },
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "ピント確認画像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            maskResult?.bitmap?.let { mask ->
                Image(
                    bitmap = mask.asImageBitmap(),
                    contentDescription = "フォーカスマスク",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        if (maskLoading) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                shape = RoundedCornerShape(50),
                color = Color(0xD9202327),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("マスク解析中", fontSize = 10.sp)
                }
            }
        }
        if (selectPointMode) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    "確認したい位置をタップ",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (canGoPrevious) {
            FocusCompareArrow(
                label = "‹",
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 5.dp),
                onClick = onPrevious,
            )
        }
        if (canGoNext) {
            FocusCompareArrow(
                label = "›",
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 5.dp),
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun FocusCompareArrow(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(42.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xB9202327),
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 25.sp)
    }
}

@Composable
private fun FocusReviewTools(
    pixelPercent: Int,
    oneToOneActive: Boolean,
    selectPointMode: Boolean,
    maskEnabled: Boolean,
    maskSensitivity: Float,
    maskResult: FocusMaskResult?,
    maskLoading: Boolean,
    faces: List<FaceRegion>,
    facesLoading: Boolean,
    onOneToOne: () -> Unit,
    onFit: () -> Unit,
    onPreviousPosition: () -> Unit,
    onCenter: () -> Unit,
    onFace: () -> Unit,
    onSelectPoint: () -> Unit,
    onMaskToggle: () -> Unit,
    onMaskSensitivity: (Float) -> Unit,
) {
    Surface(color = Color(0xFF1B1E22)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 9.dp),
            ) {
                items(
                    listOf(
                        FocusToolSpec("one-to-one", "100% · 現在$pixelPercent%", oneToOneActive, true, onOneToOne),
                        FocusToolSpec("fit", "全体", false, true, onFit),
                        FocusToolSpec("previous-position", "前回位置", false, true, onPreviousPosition),
                        FocusToolSpec("center", "中央", false, true, onCenter),
                        FocusToolSpec(
                            id = "face",
                            label = when {
                                facesLoading -> "顔検出中"
                                faces.isEmpty() -> "顔なし"
                                faces.size == 1 -> "顔"
                                else -> "顔 ${faces.size}"
                            },
                            active = false,
                            enabled = !facesLoading && faces.isNotEmpty(),
                            onClick = onFace,
                        ),
                        FocusToolSpec("pick", "任意位置", selectPointMode, true, onSelectPoint),
                        FocusToolSpec("mask", "マスク", maskEnabled, true, onMaskToggle),
                    ),
                    key = FocusToolSpec::id,
                ) { spec ->
                    FocusToolButton(spec)
                }
            }
            if (maskEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("マスク感度", fontSize = 10.sp)
                    Slider(
                        value = maskSensitivity,
                        onValueChange = onMaskSensitivity,
                        modifier = Modifier.weight(1f).height(28.dp),
                        valueRange = 0f..1f,
                        steps = 9,
                    )
                    Text(
                        maskResult?.let {
                            "%.1f%%".format(java.util.Locale.US, it.highlightedPercent)
                        } ?: if (maskLoading) "解析中" else "表示なし",
                        modifier = Modifier.width(42.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                    )
                }
                Text(
                    "高コントラスト部の補助表示です。合焦を保証する表示ではありません。",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

private data class FocusToolSpec(
    val id: String,
    val label: String,
    val active: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun FocusToolButton(spec: FocusToolSpec) {
    if (spec.active) {
        Button(
            onClick = spec.onClick,
            enabled = spec.enabled,
            modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 11.dp),
        ) { Text(spec.label, fontSize = 10.sp) }
    } else {
        OutlinedButton(
            onClick = spec.onClick,
            enabled = spec.enabled,
            modifier = Modifier.height(34.dp),
            contentPadding = PaddingValues(horizontal = 11.dp),
        ) { Text(spec.label, fontSize = 10.sp) }
    }
}

@Composable
private fun FocusReviewSourceNote(
    active: CaptureHistoryItem,
    bitmap: Bitmap,
) {
    val sourceLabel = if (active.focusReviewUsesEmbeddedPreview) {
        "カメラ内蔵プレビュー"
    } else {
        "保存JPEG"
    }
    Text(
        text = "$sourceLabel ${bitmap.width}×${bitmap.height} · " +
            "100%は確認画像の1pxを画面1pxで表示",
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF15171A))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 9.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val MAX_COMPARISON_IMAGES = 2
private const val MASK_SLIDER_DEBOUNCE_MS = 120L
