package com.example.omtether

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.omtether.camera.CameraController
import com.example.omtether.camera.CameraIdentity
import com.example.omtether.camera.ExposureControl
import com.example.omtether.camera.MockCameraController
import com.example.omtether.camera.OmUsbCameraController
import com.example.omtether.camera.Ptp
import com.example.omtether.camera.PtpScalar
import com.example.omtether.image.ImageAnalysis
import com.example.omtether.image.NeutralPatchResult
import com.example.omtether.storage.CaptureStorage
import com.example.omtether.storage.SavedObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ConnectionPhase {
    DISCONNECTED,
    REQUESTING_PERMISSION,
    CONNECTING,
    CONNECTED,
    DEMO,
    ERROR,
}

data class LastCapture(
    val files: List<SavedObject>,
    val demo: Boolean,
    val failures: List<String> = emptyList(),
)

data class DisplayCalibration(
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val brightness: Float = 0f,
)

data class MainUiState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val identity: CameraIdentity? = null,
    val liveBitmap: Bitmap? = null,
    val highlightOverlay: Bitmap? = null,
    val histogram: IntArray = IntArray(256),
    val reviewBitmap: Bitmap? = null,
    val reviewHighlightOverlay: Bitmap? = null,
    val reviewHistogram: IntArray = IntArray(256),
    val reviewHighlightPercent: Float = 0f,
    val highlightEnabled: Boolean = true,
    val highlightThreshold: Float = 0.97f,
    val highlightPercent: Float = 0f,
    val exposureControls: List<ExposureControl> = emptyList(),
    val isCapturing: Boolean = false,
    val lastCapture: LastCapture? = null,
    val showSetupGuide: Boolean = false,
    val setupStep: Int = 0,
    val displayCalibration: DisplayCalibration = DisplayCalibration(),
    val neutralPatch: NeutralPatchResult? = null,
    val setupWbConfirmed: Boolean = false,
    val setupGrayCardSkipped: Boolean = false,
    val statusMessage: String = "USB-CでOM‑1 Mark IIを接続してください",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val storage = CaptureStorage(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val cameraOperationMutex = Mutex()
    private val mutableState = MutableStateFlow(
        MainUiState(
            showSetupGuide = !preferences.getBoolean(KEY_SETUP_COMPLETE, false),
            displayCalibration = savedDisplayCalibration(),
        ),
    )
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    private var controller: CameraController? = null
    private var frameCollectionJob: Job? = null
    private var analysisJob: Job? = null
    private var reviewJob: Job? = null
    private var captureJob: Job? = null
    private var lifecycleJob: Job? = null
    private var frameGeneration = 0L
    private var lastFrameAnalyzedAt = 0L
    private var appInForeground = true

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: return
                    if (!isOm1MarkII(device)) return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        connectUsb(device)
                    } else {
                        mutableState.update {
                            it.copy(
                                phase = ConnectionPhase.ERROR,
                                statusMessage = "USBアクセスが許可されませんでした",
                            )
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    intent.usbDevice()?.takeIf(::isOm1MarkII)?.let(::requestUsbPermissionOrConnect)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = intent.usbDevice() ?: return
                    if (isOm1MarkII(detached)) handleUsbDetached()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        startDemo()
    }

    fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            intent.usbDevice()?.takeIf(::isOm1MarkII)?.let(::requestUsbPermissionOrConnect)
        }
    }

    fun requestUsbConnection() {
        val device = usbManager.deviceList.values.firstOrNull(::isOm1MarkII)
        if (device == null) {
            mutableState.update {
                it.copy(
                    phase = if (controller is MockCameraController) ConnectionPhase.DEMO else ConnectionPhase.DISCONNECTED,
                    statusMessage = "OM‑1 Mark IIが見つかりません。RAW/Controlとデータケーブルを確認してください",
                )
            }
            return
        }
        requestUsbPermissionOrConnect(device)
    }

    fun startDemo() {
        viewModelScope.launch {
            activateController(MockCameraController(), demo = true)
        }
    }

    fun capture() {
        val requestedController = controller ?: return
        if (captureJob?.isActive == true) return
        reviewJob?.cancel()
        mutableState.update {
            it.copy(
                isCapturing = true,
                reviewBitmap = null,
                reviewHighlightOverlay = null,
                reviewHistogram = IntArray(256),
                reviewHighlightPercent = 0f,
                statusMessage = "撮影して保存しています…",
            )
        }
        captureJob = viewModelScope.launch {
            cameraOperationMutex.withLock {
                if (controller !== requestedController) {
                    mutableState.update { it.copy(isCapturing = false) }
                    return@withLock
                }

                val saved = mutableListOf<SavedObject>()
                val failures = mutableListOf<String>()
                try {
                    val report = requestedController.capture(
                        onPreview = { jpeg ->
                            if (controller === requestedController) showCaptureReview(jpeg)
                        },
                        onObject = { item ->
                            try {
                                val result = storage.saveOne(item)
                                saved += result
                                if (controller === requestedController) {
                                    mutableState.update {
                                        it.copy(statusMessage = "保存中: ${saved.joinToString { file -> file.filename }}")
                                    }
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                failures += "${item.filename}: ${error.userMessage()}"
                            }
                        },
                    )
                    failures += report.warnings
                    if (controller === requestedController) {
                        val status = when {
                            saved.isEmpty() -> {
                                val reason = failures.firstOrNull()?.take(120) ?: "保存可能な画像がありません"
                                "保存失敗: $reason"
                            }
                            failures.isEmpty() -> saved.joinToString(prefix = "保存完了: ") { it.filename }
                            else -> saved.joinToString(prefix = "一部保存完了: ") { it.filename } +
                                "（警告${failures.size}件）"
                        }
                        mutableState.update {
                            it.copy(
                                lastCapture = LastCapture(
                                    files = saved.toList(),
                                    demo = requestedController is MockCameraController,
                                    failures = failures.distinct(),
                                ),
                                statusMessage = status,
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (controller === requestedController) {
                        mutableState.update {
                            it.copy(statusMessage = "撮影エラー: ${error.userMessage()}")
                        }
                    }
                } finally {
                    if (controller === requestedController) {
                        mutableState.update { it.copy(isCapturing = false) }
                        scheduleReviewClear()
                    }
                }
            }
        }
    }

    fun setExposure(propertyCode: Int, value: PtpScalar) {
        val requestedController = controller ?: return
        viewModelScope.launch {
            cameraOperationMutex.withLock {
                if (controller !== requestedController) return@withLock
                try {
                    val controls = requestedController.setExposure(propertyCode, value)
                    if (controller === requestedController) {
                        mutableState.update {
                            it.copy(exposureControls = controls, statusMessage = "露出設定をカメラへ反映しました")
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (controller === requestedController) {
                        mutableState.update { it.copy(statusMessage = "設定エラー: ${error.userMessage()}") }
                    }
                }
            }
        }
    }

    fun setHighlightEnabled(enabled: Boolean) {
        mutableState.update {
            it.copy(
                highlightEnabled = enabled,
                highlightOverlay = if (enabled) it.highlightOverlay else null,
                reviewHighlightOverlay = if (enabled) it.reviewHighlightOverlay else null,
            )
        }
        if (enabled) reanalyzeCurrentFrame()
    }

    fun setHighlightThreshold(value: Float) {
        mutableState.update { it.copy(highlightThreshold = value) }
        reanalyzeCurrentFrame()
    }

    fun openSetupGuide() {
        mutableState.update {
            it.copy(
                showSetupGuide = true,
                setupStep = 0,
                displayCalibration = savedDisplayCalibration(),
                setupWbConfirmed = false,
                setupGrayCardSkipped = false,
            )
        }
    }

    fun dismissSetupGuide() {
        mutableState.update {
            it.copy(
                showSetupGuide = false,
                setupStep = 0,
                displayCalibration = savedDisplayCalibration(),
                setupWbConfirmed = false,
                setupGrayCardSkipped = false,
            )
        }
    }

    fun nextSetupStep() {
        mutableState.update {
            if (
                canAdvanceSetup(
                    step = it.setupStep,
                    phase = it.phase,
                    wbConfirmed = it.setupWbConfirmed,
                    grayCardSkipped = it.setupGrayCardSkipped,
                    neutralPatch = it.neutralPatch,
                )
            ) {
                it.copy(setupStep = (it.setupStep + 1).coerceAtMost(5))
            } else {
                it
            }
        }
    }

    fun previousSetupStep() {
        mutableState.update { it.copy(setupStep = (it.setupStep - 1).coerceAtLeast(0)) }
    }

    fun setDisplayCalibration(calibration: DisplayCalibration) {
        mutableState.update {
            it.copy(
                displayCalibration = calibration.copy(
                    temperature = calibration.temperature.coerceIn(-1f, 1f),
                    tint = calibration.tint.coerceIn(-1f, 1f),
                    brightness = calibration.brightness.coerceIn(-0.15f, 0.15f),
                ),
            )
        }
    }

    fun resetDisplayCalibration() = setDisplayCalibration(DisplayCalibration())

    fun setSetupWbConfirmed(confirmed: Boolean) {
        mutableState.update {
            it.copy(
                setupWbConfirmed = confirmed,
                setupGrayCardSkipped = if (confirmed) false else it.setupGrayCardSkipped,
            )
        }
    }

    fun setSetupGrayCardSkipped(skipped: Boolean) {
        mutableState.update {
            it.copy(
                setupGrayCardSkipped = skipped,
                setupWbConfirmed = if (skipped) false else it.setupWbConfirmed,
            )
        }
    }

    fun completeSetupGuide() {
        val snapshot = mutableState.value
        if (
            !canAdvanceSetup(
                step = 5,
                phase = snapshot.phase,
                wbConfirmed = snapshot.setupWbConfirmed,
                grayCardSkipped = snapshot.setupGrayCardSkipped,
                neutralPatch = snapshot.neutralPatch,
            )
        ) {
            return
        }
        val calibration = snapshot.displayCalibration
        preferences.edit()
            .putBoolean(KEY_SETUP_COMPLETE, true)
            .putFloat(KEY_TEMPERATURE, calibration.temperature)
            .putFloat(KEY_TINT, calibration.tint)
            .putFloat(KEY_BRIGHTNESS, calibration.brightness)
            .apply()
        mutableState.update {
            it.copy(
                showSetupGuide = false,
                setupStep = 0,
                setupWbConfirmed = false,
                setupGrayCardSkipped = false,
                statusMessage = "撮影前ガイド完了 — プレビュー補正を適用しました",
            )
        }
    }

    fun diagnosticsText(): String = controller?.diagnosticsText()
        ?: "OM Tether 0.2.1\nCamera controller is not active."

    private fun requestUsbPermissionOrConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            connectUsb(device)
            return
        }
        val permissionIntent = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        mutableState.update {
            it.copy(
                phase = ConnectionPhase.REQUESTING_PERMISSION,
                statusMessage = "AndroidのUSBアクセス許可を確認してください",
            )
        }
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun connectUsb(device: UsbDevice) {
        viewModelScope.launch {
            activateController(OmUsbCameraController(usbManager, device), demo = false)
        }
    }

    private suspend fun activateController(newController: CameraController, demo: Boolean) {
        cameraOperationMutex.withLock {
            frameGeneration++
            lastFrameAnalyzedAt = 0L
            frameCollectionJob?.cancel()
            frameCollectionJob = null
            analysisJob?.cancel()
            reviewJob?.cancel()
            controller?.let { old ->
                runCatching { old.disconnect() }
                old.forceClose()
            }
            controller = newController
            mutableState.update {
                it.copy(
                    phase = if (demo) ConnectionPhase.DEMO else ConnectionPhase.CONNECTING,
                    identity = null,
                    liveBitmap = null,
                    highlightOverlay = null,
                    histogram = IntArray(256),
                    highlightPercent = 0f,
                    neutralPatch = null,
                    setupWbConfirmed = false,
                    setupGrayCardSkipped = false,
                    reviewBitmap = null,
                    reviewHighlightOverlay = null,
                    reviewHistogram = IntArray(256),
                    reviewHighlightPercent = 0f,
                    exposureControls = emptyList(),
                    isCapturing = false,
                    statusMessage = if (demo) "デモモード — カメラへ命令は送信しません" else "OM‑1 Mark IIへ接続しています…",
                )
            }
            try {
                val session = newController.connect()
                val generation = frameGeneration
                frameCollectionJob = viewModelScope.launch {
                    // Analyze one frame to completion and retain only the newest waiting frame.
                    // collectLatest could repeatedly cancel JPEG analysis on a slower Android device.
                    newController.frames.conflate().collect { jpeg -> updateFrame(jpeg, generation) }
                }
                mutableState.update {
                    it.copy(
                        phase = if (demo) ConnectionPhase.DEMO else ConnectionPhase.CONNECTED,
                        identity = session.identity,
                        exposureControls = session.exposureControls,
                        statusMessage = if (demo) {
                            "デモモード — USB接続で実機へ切り替えられます"
                        } else {
                            "USB接続完了 — RAW+JPEGで撮影できます"
                        },
                    )
                }
                if (appInForeground) newController.startLiveView()
            } catch (error: CancellationException) {
                frameCollectionJob?.cancel()
                frameCollectionJob = null
                newController.forceClose()
                if (controller === newController) controller = null
                throw error
            } catch (error: Throwable) {
                frameCollectionJob?.cancel()
                frameCollectionJob = null
                newController.forceClose()
                if (controller === newController) controller = null
                mutableState.update {
                    it.copy(
                        phase = ConnectionPhase.ERROR,
                        identity = null,
                        liveBitmap = null,
                        highlightOverlay = null,
                        histogram = IntArray(256),
                        highlightPercent = 0f,
                        neutralPatch = null,
                        setupWbConfirmed = false,
                        setupGrayCardSkipped = false,
                        setupStep = if (it.showSetupGuide && it.setupStep > 2) 2 else it.setupStep,
                        exposureControls = emptyList(),
                        statusMessage = "接続エラー: ${error.userMessage()}",
                    )
                }
            }
        }
    }

    private suspend fun updateFrame(jpeg: ByteArray, generation: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAnalyzedAt < LIVE_FRAME_INTERVAL_MS) return
        lastFrameAnalyzedAt = now
        val snapshot = mutableState.value
        val threshold = snapshot.highlightThreshold
        val includeHighlight = snapshot.highlightEnabled
        val decodedAndAnalyzed = withContext(Dispatchers.Default) {
            val bitmap = ImageAnalysis.decodeJpeg(jpeg, LIVE_PREVIEW_MAX_DIMENSION) ?: return@withContext null
            bitmap to ImageAnalysis.analyze(bitmap, threshold, includeHighlight)
        } ?: return
        if (generation != frameGeneration) return
        mutableState.update { current ->
            if (
                generation != frameGeneration ||
                current.highlightThreshold != threshold ||
                current.highlightEnabled != includeHighlight
            ) {
                current
            } else {
                current.copy(
                    liveBitmap = decodedAndAnalyzed.first,
                    histogram = decodedAndAnalyzed.second.histogram,
                    highlightOverlay = decodedAndAnalyzed.second.highlightOverlay,
                    highlightPercent = decodedAndAnalyzed.second.highlightPercent,
                    neutralPatch = decodedAndAnalyzed.second.neutralPatch,
                )
            }
        }
    }

    private suspend fun showCaptureReview(jpeg: ByteArray) {
        val snapshot = mutableState.value
        val threshold = snapshot.highlightThreshold
        val includeHighlight = snapshot.highlightEnabled
        val decodedAndAnalyzed = withContext(Dispatchers.Default) {
            val bitmap = ImageAnalysis.decodeJpeg(jpeg, CAPTURE_PREVIEW_MAX_DIMENSION) ?: return@withContext null
            bitmap to ImageAnalysis.analyze(bitmap, threshold, includeHighlight)
        } ?: return
        var needsReanalysis = false
        mutableState.update { current ->
            if (current.highlightThreshold != threshold || current.highlightEnabled != includeHighlight) {
                needsReanalysis = true
                current.copy(
                    reviewBitmap = decodedAndAnalyzed.first,
                    reviewHistogram = decodedAndAnalyzed.second.histogram,
                    reviewHighlightOverlay = null,
                    reviewHighlightPercent = 0f,
                )
            } else {
                current.copy(
                    reviewBitmap = decodedAndAnalyzed.first,
                    reviewHistogram = decodedAndAnalyzed.second.histogram,
                    reviewHighlightOverlay = decodedAndAnalyzed.second.highlightOverlay,
                    reviewHighlightPercent = decodedAndAnalyzed.second.highlightPercent,
                )
            }
        }
        if (needsReanalysis) reanalyzeCurrentFrame()
    }

    private fun reanalyzeCurrentFrame() {
        val snapshot = mutableState.value
        val bitmap = snapshot.reviewBitmap ?: snapshot.liveBitmap ?: return
        val threshold = snapshot.highlightThreshold
        val includeHighlight = snapshot.highlightEnabled
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            val result = ImageAnalysis.analyze(bitmap, threshold, includeHighlight)
            mutableState.update { current ->
                if (
                    current.highlightThreshold == threshold &&
                    current.highlightEnabled == includeHighlight &&
                    current.reviewBitmap === bitmap
                ) {
                    current.copy(
                        reviewHistogram = result.histogram,
                        reviewHighlightOverlay = result.highlightOverlay,
                        reviewHighlightPercent = result.highlightPercent,
                    )
                } else if (
                    current.highlightThreshold == threshold &&
                    current.highlightEnabled == includeHighlight &&
                    current.liveBitmap === bitmap
                ) {
                    current.copy(
                        histogram = result.histogram,
                        highlightOverlay = result.highlightOverlay,
                        highlightPercent = result.highlightPercent,
                        neutralPatch = result.neutralPatch,
                    )
                } else {
                    current
                }
            }
        }
    }

    fun onAppForegroundChanged(foreground: Boolean) {
        if (appInForeground == foreground) return
        appInForeground = foreground
        val requestedController = controller ?: return
        lifecycleJob?.cancel()
        lifecycleJob = viewModelScope.launch {
            cameraOperationMutex.withLock {
                if (controller !== requestedController) return@withLock
                try {
                    if (foreground) {
                        requestedController.startLiveView()
                        mutableState.update {
                            it.copy(statusMessage = if (requestedController is MockCameraController) {
                                "デモモード — USB接続で実機へ切り替えられます"
                            } else {
                                "ライブビューを再開しました"
                            })
                        }
                    } else {
                        requestedController.stopLiveView()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (controller === requestedController) {
                        mutableState.update {
                            it.copy(statusMessage = "ライブビュー制御エラー: ${error.userMessage()}")
                        }
                    }
                }
            }
        }
    }

    private fun scheduleReviewClear() {
        reviewJob?.cancel()
        if (mutableState.value.reviewBitmap == null) return
        reviewJob = viewModelScope.launch {
            kotlinx.coroutines.delay(CAPTURE_REVIEW_MS)
            mutableState.update {
                it.copy(
                    reviewBitmap = null,
                    reviewHighlightOverlay = null,
                    reviewHistogram = IntArray(256),
                    reviewHighlightPercent = 0f,
                )
            }
        }
    }

    private fun handleUsbDetached() {
        frameGeneration++
        captureJob?.cancel()
        captureJob = null
        lifecycleJob?.cancel()
        frameCollectionJob?.cancel()
        frameCollectionJob = null
        analysisJob?.cancel()
        reviewJob?.cancel()
        controller?.forceClose()
        controller = null
        mutableState.update {
            it.copy(
                phase = ConnectionPhase.DISCONNECTED,
                identity = null,
                liveBitmap = null,
                highlightOverlay = null,
                histogram = IntArray(256),
                highlightPercent = 0f,
                neutralPatch = null,
                setupWbConfirmed = false,
                setupGrayCardSkipped = false,
                setupStep = if (it.showSetupGuide && it.setupStep > 2) 2 else it.setupStep,
                reviewBitmap = null,
                reviewHighlightOverlay = null,
                reviewHistogram = IntArray(256),
                reviewHighlightPercent = 0f,
                exposureControls = emptyList(),
                isCapturing = false,
                statusMessage = "USBが切断されました。既に保存済みのファイルは保持されています",
            )
        }
    }

    private fun isOm1MarkII(device: UsbDevice): Boolean =
        device.vendorId == Ptp.OM1_MARK_II_VENDOR_ID && device.productId == Ptp.OM1_MARK_II_PRODUCT_ID

    override fun onCleared() {
        runCatching { appContext.unregisterReceiver(usbReceiver) }
        frameCollectionJob?.cancel()
        analysisJob?.cancel()
        reviewJob?.cancel()
        captureJob?.cancel()
        lifecycleJob?.cancel()
        controller?.forceClose()
        super.onCleared()
    }

    private fun Throwable.userMessage(): String = message?.take(240) ?: this::class.java.simpleName

    private fun savedDisplayCalibration(): DisplayCalibration = DisplayCalibration(
        temperature = preferences.getFloat(KEY_TEMPERATURE, 0f).coerceIn(-1f, 1f),
        tint = preferences.getFloat(KEY_TINT, 0f).coerceIn(-1f, 1f),
        brightness = preferences.getFloat(KEY_BRIGHTNESS, 0f).coerceIn(-0.15f, 0.15f),
    )

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.omtether.USB_PERMISSION"
        private const val CAPTURE_REVIEW_MS = 2_500L
        private const val LIVE_FRAME_INTERVAL_MS = 100L
        private const val LIVE_PREVIEW_MAX_DIMENSION = 1_280
        private const val CAPTURE_PREVIEW_MAX_DIMENSION = 2_048
        private const val PREFERENCES_NAME = "display_calibration"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TINT = "tint"
        private const val KEY_BRIGHTNESS = "brightness"
    }
}
