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
import com.example.omtether.camera.PhoneSaveFormat
import com.example.omtether.camera.Ptp
import com.example.omtether.camera.PtpScalar
import com.example.omtether.image.ImageAnalysis
import com.example.omtether.image.NeutralPatchResult
import com.example.omtether.storage.CaptureStorage
import com.example.omtether.storage.SavedObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.withTimeoutOrNull

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
    val previewJpegFallbackUsed: Boolean = false,
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
    val exposureSyncActive: Boolean = false,
    val phoneSaveFormat: PhoneSaveFormat = PhoneSaveFormat.JPEG,
    val isCapturing: Boolean = false,
    val lastCapture: LastCapture? = null,
    val showConnectionGuide: Boolean = true,
    val showSetupGuide: Boolean = false,
    val setupStep: Int = 0,
    val displayCalibration: DisplayCalibration = DisplayCalibration(),
    val neutralPatch: NeutralPatchResult? = null,
    val setupWbConfirmed: Boolean = false,
    val setupGrayCardSkipped: Boolean = false,
    val liveViewIssue: String? = null,
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
            phoneSaveFormat = savedPhoneSaveFormat(),
        ),
    )
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    private var controller: CameraController? = null
    private var frameCollectionJob: Job? = null
    private var analysisJob: Job? = null
    private var reviewJob: Job? = null
    private var captureJob: Job? = null
    private var externalCaptureEventJob: Job? = null
    private var externalCaptureDebounceJob: Job? = null
    private var lifecycleJob: Job? = null
    private var liveViewWatchdogJob: Job? = null
    private var exposureSyncJob: Job? = null
    private var frameGeneration = 0L
    private var liveViewStartedAt = 0L
    private var lastLiveFrameReceivedAt = 0L
    private var lastFrameDecodedAt = 0L
    private var lastFrameAnalyzedAt = 0L
    private var appInForeground = true
    private var recoverPtpAfterPermission = false
    private var realCameraConnectedOnce = false
    @Volatile
    private var lastDiagnosticsText = "OM Tether 0.3.4\nCamera controller has not been activated."

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: return
                    if (!isOm1MarkII(device)) return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        val recoverPtpSession = recoverPtpAfterPermission
                        recoverPtpAfterPermission = false
                        connectUsb(device, recoverPtpSession)
                    } else {
                        recoverPtpAfterPermission = false
                        mutableState.update {
                            it.copy(
                                phase = ConnectionPhase.ERROR,
                                statusMessage = "USBアクセスが許可されませんでした",
                            )
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    intent.usbDevice()?.takeIf(::isOm1MarkII)?.let(::handleUsbAttached)
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
    }

    fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            intent.usbDevice()?.takeIf(::isOm1MarkII)?.let(::handleUsbAttached)
        }
    }

    fun openUsbConnectionGuide() {
        mutableState.update {
            it.copy(
                showConnectionGuide = true,
                statusMessage = "カメラ側で「0 RAW/Control」を選んでから接続してください",
            )
        }
    }

    fun dismissUsbConnectionGuide() {
        mutableState.update { it.copy(showConnectionGuide = false) }
    }

    fun confirmUsbSetupAndConnect() {
        val device = usbManager.deviceList.values.firstOrNull(::isOm1MarkII)
        if (device == null) {
            mutableState.update {
                it.copy(
                    phase = if (controller is MockCameraController) ConnectionPhase.DEMO else ConnectionPhase.DISCONNECTED,
                    showConnectionGuide = true,
                    statusMessage = "カメラが見つかりません。電源・0 RAW/Control・データケーブルを確認してください",
                )
            }
            return
        }
        mutableState.update { it.copy(showConnectionGuide = false) }
        requestUsbPermissionOrConnect(device)
    }

    fun startDemo() {
        mutableState.update { it.copy(showConnectionGuide = false) }
        viewModelScope.launch {
            activateController(MockCameraController(), demo = true)
        }
    }

    fun capture() {
        val requestedController = controller ?: return
        beginCaptureTransfer(requestedController, cameraSideShutter = false)
    }

    private fun beginCaptureTransfer(
        requestedController: CameraController,
        cameraSideShutter: Boolean,
    ) {
        if (captureJob?.isActive == true || mutableState.value.isCapturing) return
        val requestedSaveFormat = mutableState.value.phoneSaveFormat
        reviewJob?.cancel()
        mutableState.update {
            it.copy(
                isCapturing = true,
                reviewBitmap = null,
                reviewHighlightOverlay = null,
                reviewHistogram = IntArray(256),
                reviewHighlightPercent = 0f,
                statusMessage = when {
                    cameraSideShutter && requestedSaveFormat == PhoneSaveFormat.JPEG ->
                        "カメラ側の撮影を受信してJPEGを保存しています…"
                    cameraSideShutter && requestedSaveFormat == PhoneSaveFormat.RAW ->
                        "カメラ側の撮影を受信してRAWを保存しています…"
                    requestedSaveFormat == PhoneSaveFormat.JPEG -> "撮影してJPEGを保存しています…"
                    else -> "撮影してRAWを保存しています…"
                },
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
                    val onPreview: suspend (ByteArray) -> Unit = { jpeg ->
                        if (controller === requestedController) showCaptureReview(jpeg)
                    }
                    val onObject: suspend (com.example.omtether.camera.DownloadedObject) -> Unit = { item ->
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
                    }
                    val report = if (cameraSideShutter) {
                        requestedController.importExternalCapture(
                            phoneSaveFormat = requestedSaveFormat,
                            onPreview = onPreview,
                            onObject = onObject,
                        )
                    } else {
                        requestedController.capture(
                            phoneSaveFormat = requestedSaveFormat,
                            onPreview = onPreview,
                            onObject = onObject,
                        )
                    }
                    failures += report.warnings
                    if (controller === requestedController) {
                        val status = when {
                            saved.isEmpty() -> {
                                val reason = failures.firstOrNull()?.take(120) ?: "保存可能な画像がありません"
                                "保存失敗: $reason"
                            }
                            report.previewJpegFallbackUsed ->
                                saved.joinToString(prefix = "保存完了: ") { it.filename } +
                                    "（プレビューJPEG・画質制限あり）"
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
                                    previewJpegFallbackUsed = report.previewJpegFallbackUsed,
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
                            it.copy(
                                statusMessage = if (cameraSideShutter) {
                                    "カメラ側撮影の受信エラー: ${error.userMessage()}"
                                } else {
                                    "撮影エラー: ${error.userMessage()}"
                                },
                            )
                        }
                    }
                } finally {
                    if (controller === requestedController) {
                        if (requestedController !is MockCameraController && appInForeground) {
                            liveViewStartedAt = SystemClock.elapsedRealtime()
                        }
                        mutableState.update { it.copy(isCapturing = false) }
                        scheduleReviewClear()
                    }
                }
            }
        }
    }

    fun setPhoneSaveFormat(format: PhoneSaveFormat) {
        if (mutableState.value.isCapturing) return
        preferences.edit().putString(KEY_PHONE_SAVE_FORMAT, format.name).apply()
        mutableState.update {
            it.copy(
                phoneSaveFormat = format,
                statusMessage = when (format) {
                    PhoneSaveFormat.JPEG ->
                        "スマホ保存：JPEG（カード1/2のフルJPEGを優先）"
                    PhoneSaveFormat.RAW ->
                        "スマホ保存：RAW（カード1/2のORFを検索）"
                },
            )
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

    fun diagnosticsText(): String = controller?.diagnosticsText()?.also {
        lastDiagnosticsText = it
    } ?: lastDiagnosticsText

    fun restartLiveView() {
        if (controller is MockCameraController) return
        val device = usbManager.deviceList.values.firstOrNull(::isOm1MarkII)
        if (device == null) {
            mutableState.update {
                it.copy(
                    phase = ConnectionPhase.DISCONNECTED,
                    exposureSyncActive = false,
                    liveViewIssue = "OM‑1 Mark IIが見つかりません",
                    statusMessage = "カメラを0 RAW/Controlで接続し直してください",
                )
            }
            return
        }
        mutableState.update {
            it.copy(
                exposureSyncActive = false,
                liveViewIssue = null,
                statusMessage = "USB/PTPセッションを作り直しています…",
            )
        }
        requestUsbPermissionOrConnect(device, recoverPtpSession = true)
    }

    private fun requestUsbPermissionOrConnect(
        device: UsbDevice,
        recoverPtpSession: Boolean = false,
    ) {
        val shouldRecoverPtp = recoverPtpSession || realCameraConnectedOnce
        if (usbManager.hasPermission(device)) {
            connectUsb(device, shouldRecoverPtp)
            return
        }
        recoverPtpAfterPermission = shouldRecoverPtp
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

    private fun connectUsb(device: UsbDevice, recoverPtpSession: Boolean = false) {
        lifecycleJob?.cancel()
        lifecycleJob = viewModelScope.launch {
            activateController(
                OmUsbCameraController(
                    usbManager = usbManager,
                    device = device,
                    recoverPtpSession = recoverPtpSession,
                ),
                demo = false,
            )
        }
    }

    private fun handleUsbAttached(device: UsbDevice) {
        if (!isOm1MarkII(device)) return
        mutableState.update {
            it.copy(
                showConnectionGuide = true,
                statusMessage = "USBを検出しました。カメラ側で「0 RAW/Control」を選択してください",
            )
        }
    }

    private suspend fun activateController(newController: CameraController, demo: Boolean) {
        cameraOperationMutex.withLock {
            frameGeneration++
            liveViewStartedAt = 0L
            lastLiveFrameReceivedAt = 0L
            lastFrameDecodedAt = 0L
            lastFrameAnalyzedAt = 0L
            liveViewWatchdogJob?.cancel()
            exposureSyncJob?.cancel()
            exposureSyncJob = null
            externalCaptureEventJob?.cancel()
            externalCaptureEventJob = null
            externalCaptureDebounceJob?.cancel()
            externalCaptureDebounceJob = null
            frameCollectionJob?.cancel()
            frameCollectionJob = null
            analysisJob?.cancel()
            reviewJob?.cancel()
            controller?.let { old ->
                // A stalled bulk transfer is a blocking Android USB call and cannot be
                // interrupted reliably by coroutine cancellation alone. Close the old
                // UsbDeviceConnection first so the read unblocks, then finish cleanup
                // within a short bound before opening a fresh PTP session.
                rememberDiagnostics(old)
                runCatching { old.forceClose() }
                withTimeoutOrNull(CONTROLLER_SHUTDOWN_TIMEOUT_MS) {
                    runCatching { old.disconnect() }
                }
                rememberDiagnostics(old)
                delay(USB_REOPEN_SETTLE_MS)
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
                    exposureSyncActive = false,
                    isCapturing = false,
                    showConnectionGuide = false,
                    liveViewIssue = null,
                    statusMessage = if (demo) "デモモード — カメラへ命令は送信しません" else "OM‑1 Mark IIへ接続しています…",
                )
            }
            var connectionTimedOut = false
            val firstFrameReady = CompletableDeferred<Unit>()
            val connectionTimeoutJob = if (demo) {
                null
            } else {
                viewModelScope.launch {
                    delay(CONNECTION_TIMEOUT_MS)
                    if (
                        controller === newController &&
                        firstFrameReady.completeExceptionally(
                            IllegalStateException("First live-view frame timed out"),
                        )
                    ) {
                        connectionTimedOut = true
                        // Keep the timeout armed through live-view property setup and the
                        // first successfully decoded JPEG. Closing UsbDeviceConnection is
                        // what releases a blocking Android bulkTransfer.
                        newController.forceClose()
                    }
                }
            }
            try {
                val session = newController.connect()
                if (!demo) realCameraConnectedOnce = true
                val generation = frameGeneration
                frameCollectionJob = viewModelScope.launch {
                    // Decode one frame to completion and retain only the newest waiting frame.
                    // Analysis runs less often than display updates to avoid GC stalls.
                    newController.frames.conflate().collect { jpeg ->
                        val announceFirstFrame = lastLiveFrameReceivedAt == 0L
                        lastLiveFrameReceivedAt = SystemClock.elapsedRealtime()
                        if (updateFrame(jpeg, generation, announceFirstFrame)) {
                            firstFrameReady.complete(Unit)
                        }
                    }
                }
                if (!demo) {
                    externalCaptureEventJob = viewModelScope.launch {
                        newController.externalCaptureEvents.collect {
                            if (
                                controller !== newController ||
                                generation != frameGeneration ||
                                mutableState.value.phase == ConnectionPhase.ERROR ||
                                mutableState.value.isCapturing
                            ) {
                                return@collect
                            }
                            externalCaptureDebounceJob?.cancel()
                            externalCaptureDebounceJob = viewModelScope.launch {
                                delay(EXTERNAL_CAPTURE_DEBOUNCE_MS)
                                while (
                                    controller === newController &&
                                    generation == frameGeneration &&
                                    mutableState.value.isCapturing
                                ) {
                                    delay(EXTERNAL_CAPTURE_BUSY_RETRY_MS)
                                }
                                if (
                                    controller === newController &&
                                    generation == frameGeneration &&
                                    mutableState.value.phase == ConnectionPhase.CONNECTED
                                ) {
                                    beginCaptureTransfer(newController, cameraSideShutter = true)
                                }
                            }
                        }
                    }
                }
                mutableState.update {
                    it.copy(
                        phase = if (demo) ConnectionPhase.DEMO else ConnectionPhase.CONNECTING,
                        identity = session.identity,
                        exposureControls = session.exposureControls,
                        exposureSyncActive = false,
                        statusMessage = if (demo) {
                            "デモモード — USB接続で実機へ切り替えられます"
                        } else {
                            "ライブビューを開始しています…"
                        },
                    )
                }
                if (appInForeground) {
                    liveViewStartedAt = SystemClock.elapsedRealtime()
                    newController.startLiveView()
                    if (!demo) {
                        firstFrameReady.await()
                        connectionTimeoutJob?.cancel()
                        mutableState.update {
                            it.copy(
                                phase = ConnectionPhase.CONNECTED,
                                exposureSyncActive = true,
                                statusMessage = when (it.phoneSaveFormat) {
                                    PhoneSaveFormat.JPEG -> "ライブビュー受信中 — スマホへJPEG保存"
                                    PhoneSaveFormat.RAW -> "ライブビュー受信中 — スマホへRAW保存"
                                },
                            )
                        }
                        startLiveViewWatchdog(newController, generation)
                        startExposureSync(newController, generation)
                    }
                } else if (!demo) {
                    connectionTimeoutJob?.cancel()
                    mutableState.update {
                        it.copy(
                            phase = ConnectionPhase.CONNECTED,
                            statusMessage = "アプリを表示するとライブビューを開始します",
                        )
                    }
                }
            } catch (error: CancellationException) {
                connectionTimeoutJob?.cancel()
                frameCollectionJob?.cancel()
                frameCollectionJob = null
                externalCaptureEventJob?.cancel()
                externalCaptureEventJob = null
                externalCaptureDebounceJob?.cancel()
                externalCaptureDebounceJob = null
                newController.forceClose()
                rememberDiagnostics(newController)
                if (controller === newController) controller = null
                throw error
            } catch (error: Throwable) {
                connectionTimeoutJob?.cancel()
                frameCollectionJob?.cancel()
                frameCollectionJob = null
                externalCaptureEventJob?.cancel()
                externalCaptureEventJob = null
                externalCaptureDebounceJob?.cancel()
                externalCaptureDebounceJob = null
                newController.forceClose()
                rememberDiagnostics(newController)
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
                        exposureSyncActive = false,
                        liveViewIssue = null,
                        statusMessage = if (connectionTimedOut) {
                            "接続が30秒でタイムアウトしました。0 RAW/Controlを確認してUSBを接続し直してください"
                        } else {
                            "接続エラー: ${error.userMessage()}"
                        },
                    )
                }
            }
        }
    }

    private suspend fun updateFrame(
        jpeg: ByteArray,
        generation: Long,
        announceFirstFrame: Boolean,
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameDecodedAt < LIVE_FRAME_INTERVAL_MS) return false
        lastFrameDecodedAt = now
        val snapshot = mutableState.value
        val threshold = snapshot.highlightThreshold
        val includeHighlight = snapshot.highlightEnabled
        val shouldAnalyze = now - lastFrameAnalyzedAt >= LIVE_ANALYSIS_INTERVAL_MS
        if (shouldAnalyze) lastFrameAnalyzedAt = now
        val decodedAndAnalyzed = withContext(Dispatchers.Default) {
            val bitmap = ImageAnalysis.decodeJpeg(jpeg, LIVE_PREVIEW_MAX_DIMENSION) ?: return@withContext null
            bitmap to if (shouldAnalyze) ImageAnalysis.analyze(bitmap, threshold, includeHighlight) else null
        } ?: return false
        if (generation != frameGeneration) return false
        val decodedBitmap = decodedAndAnalyzed.first
        mutableState.update { current ->
            if (
                generation != frameGeneration ||
                current.highlightThreshold != threshold ||
                current.highlightEnabled != includeHighlight
            ) {
                current
            } else {
                val analysis = decodedAndAnalyzed.second
                current.copy(
                    liveBitmap = decodedBitmap,
                    histogram = analysis?.histogram ?: current.histogram,
                    highlightOverlay = analysis?.highlightOverlay ?: current.highlightOverlay,
                    highlightPercent = analysis?.highlightPercent ?: current.highlightPercent,
                    neutralPatch = analysis?.neutralPatch ?: current.neutralPatch,
                    liveViewIssue = null,
                    statusMessage = if (
                        announceFirstFrame ||
                        current.liveBitmap == null ||
                        current.liveViewIssue != null
                    ) {
                        when (current.phoneSaveFormat) {
                            PhoneSaveFormat.JPEG -> "ライブビュー受信中 — スマホへJPEG保存"
                            PhoneSaveFormat.RAW -> "ライブビュー受信中 — スマホへRAW保存"
                        }
                    } else {
                        current.statusMessage
                    },
                )
            }
        }
        return generation == frameGeneration && mutableState.value.liveBitmap === decodedBitmap
    }

    private fun startLiveViewWatchdog(requestedController: CameraController, generation: Long) {
        liveViewWatchdogJob?.cancel()
        liveViewWatchdogJob = viewModelScope.launch {
            while (controller === requestedController && generation == frameGeneration) {
                delay(LIVE_VIEW_WATCHDOG_INTERVAL_MS)
                if (
                    !appInForeground ||
                    mutableState.value.phase != ConnectionPhase.CONNECTED ||
                    mutableState.value.isCapturing
                ) {
                    continue
                }
                val newestActivity = maxOf(liveViewStartedAt, lastLiveFrameReceivedAt)
                if (
                    newestActivity > 0L &&
                    SystemClock.elapsedRealtime() - newestActivity >= LIVE_VIEW_STALL_TIMEOUT_MS &&
                    mutableState.value.liveViewIssue == null
                ) {
                    val message = "ライブビューが停止しました。「再接続」でUSB/PTPを初期化してください"
                    mutableState.update {
                        it.copy(
                            liveBitmap = null,
                            highlightOverlay = null,
                            histogram = IntArray(256),
                            highlightPercent = 0f,
                            neutralPatch = null,
                            exposureSyncActive = false,
                            liveViewIssue = message,
                            statusMessage = message,
                        )
                    }
                }
            }
        }
    }

    private fun startExposureSync(requestedController: CameraController, generation: Long) {
        exposureSyncJob?.cancel()
        exposureSyncJob = viewModelScope.launch {
            while (controller === requestedController && generation == frameGeneration) {
                delay(EXPOSURE_SYNC_INTERVAL_MS)
                if (
                    !appInForeground ||
                    mutableState.value.phase != ConnectionPhase.CONNECTED ||
                    mutableState.value.isCapturing ||
                    mutableState.value.liveViewIssue != null ||
                    lastLiveFrameReceivedAt == 0L
                ) {
                    continue
                }
                try {
                    val controls = cameraOperationMutex.withLock {
                        if (controller !== requestedController || generation != frameGeneration) {
                            null
                        } else {
                            requestedController.refreshExposureControls()
                        }
                    } ?: continue
                    if (controller === requestedController && generation == frameGeneration) {
                        mutableState.update {
                            it.copy(
                                exposureControls = controls,
                                exposureSyncActive = true,
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    if (controller === requestedController && generation == frameGeneration) {
                        mutableState.update { it.copy(exposureSyncActive = false) }
                    }
                }
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
                        liveViewStartedAt = SystemClock.elapsedRealtime()
                        lastLiveFrameReceivedAt = 0L
                        if (requestedController !is MockCameraController) {
                            startLiveViewWatchdog(requestedController, frameGeneration)
                        }
                        requestedController.startLiveView()
                        if (requestedController !is MockCameraController) {
                            startExposureSync(requestedController, frameGeneration)
                        }
                        mutableState.update {
                            it.copy(
                                liveViewIssue = null,
                                exposureSyncActive = requestedController !is MockCameraController,
                                statusMessage = if (requestedController is MockCameraController) {
                                    "デモモード — USB接続で実機へ切り替えられます"
                                } else {
                                    "ライブビューを再開しています…"
                                },
                            )
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
        liveViewWatchdogJob?.cancel()
        exposureSyncJob?.cancel()
        exposureSyncJob = null
        externalCaptureEventJob?.cancel()
        externalCaptureEventJob = null
        externalCaptureDebounceJob?.cancel()
        externalCaptureDebounceJob = null
        captureJob?.cancel()
        captureJob = null
        lifecycleJob?.cancel()
        frameCollectionJob?.cancel()
        frameCollectionJob = null
        analysisJob?.cancel()
        reviewJob?.cancel()
        controller?.let { active ->
            rememberDiagnostics(active)
            active.forceClose()
            rememberDiagnostics(active)
        }
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
                exposureSyncActive = false,
                isCapturing = false,
                liveViewIssue = null,
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
        liveViewWatchdogJob?.cancel()
        exposureSyncJob?.cancel()
        externalCaptureEventJob?.cancel()
        externalCaptureDebounceJob?.cancel()
        controller?.let { active ->
            rememberDiagnostics(active)
            active.forceClose()
            rememberDiagnostics(active)
        }
        super.onCleared()
    }

    private fun rememberDiagnostics(source: CameraController) {
        runCatching { source.diagnosticsText() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { lastDiagnosticsText = it }
    }

    private fun Throwable.userMessage(): String = message?.take(240) ?: this::class.java.simpleName

    private fun savedDisplayCalibration(): DisplayCalibration = DisplayCalibration(
        temperature = preferences.getFloat(KEY_TEMPERATURE, 0f).coerceIn(-1f, 1f),
        tint = preferences.getFloat(KEY_TINT, 0f).coerceIn(-1f, 1f),
        brightness = preferences.getFloat(KEY_BRIGHTNESS, 0f).coerceIn(-0.15f, 0.15f),
    )

    private fun savedPhoneSaveFormat(): PhoneSaveFormat = runCatching {
        PhoneSaveFormat.valueOf(
            preferences.getString(KEY_PHONE_SAVE_FORMAT, PhoneSaveFormat.JPEG.name)
                ?: PhoneSaveFormat.JPEG.name,
        )
    }.getOrDefault(PhoneSaveFormat.JPEG)

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.omtether.USB_PERMISSION"
        private const val CAPTURE_REVIEW_MS = 2_500L
        private const val LIVE_FRAME_INTERVAL_MS = 125L
        private const val LIVE_ANALYSIS_INTERVAL_MS = 500L
        private const val LIVE_VIEW_WATCHDOG_INTERVAL_MS = 1_000L
        private const val LIVE_VIEW_STALL_TIMEOUT_MS = 4_000L
        private const val EXPOSURE_SYNC_INTERVAL_MS = 1_800L
        private const val EXTERNAL_CAPTURE_DEBOUNCE_MS = 650L
        private const val EXTERNAL_CAPTURE_BUSY_RETRY_MS = 250L
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val CONTROLLER_SHUTDOWN_TIMEOUT_MS = 1_500L
        private const val USB_REOPEN_SETTLE_MS = 150L
        private const val LIVE_PREVIEW_MAX_DIMENSION = 1_024
        private const val CAPTURE_PREVIEW_MAX_DIMENSION = 2_048
        private const val PREFERENCES_NAME = "display_calibration"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TINT = "tint"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_PHONE_SAVE_FORMAT = "phone_save_format"
    }
}
