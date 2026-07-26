package com.example.omtether.camera

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeoutException
import kotlin.coroutines.coroutineContext

class OmUsbCameraController(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val recoverPtpSession: Boolean = false,
) : CameraController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
    override val frames: Flow<ByteArray> = mutableFrames.asSharedFlow()
    private val mutableExternalCaptureEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val externalCaptureEvents: Flow<Unit> = mutableExternalCaptureEvents.asSharedFlow()
    private val log = DiagnosticLog()

    private var connection: UsbDeviceConnection? = null
    private var cameraInterface: UsbInterface? = null
    private var transport: PtpUsbTransport? = null
    private var deviceInfo: PtpDeviceInfo? = null
    private var sessionOpen = false
    private var liveViewJob: Job? = null
    private var eventReaderJob: Job? = null
    private var objectWatcherJob: Job? = null
    private val objectAddedEvents = Channel<Long>(Channel.UNLIMITED)
    private val objectTracker = CameraObjectTracker()
    private val descriptors = mutableMapOf<Int, PtpPropertyDescriptor>()
    private val cameraOperationMutex = Mutex()
    @Volatile
    private var appCaptureActive = false
    @Volatile
    private var externalImportActive = false
    private var forceLiveViewRestart = recoverPtpSession

    override suspend fun connect(): CameraSession {
        require(device.vendorId == Ptp.OM1_MARK_II_VENDOR_ID && device.productId == Ptp.OM1_MARK_II_PRODUCT_ID) {
            "This prototype only enables control for OM‑1 Mark II (${Ptp.hex16(device.vendorId)}:${Ptp.hex16(device.productId)})"
        }
        if (!usbManager.hasPermission(device)) throw PtpException(message = "Android USB permission is not granted")

        try {
            log.add("USB device ${device.deviceName}, VID=${Ptp.hex16(device.vendorId)}, PID=${Ptp.hex16(device.productId)}")
            val endpointSet = findPtpEndpoints(device)
                ?: throw PtpException(message = "Still-image bulk IN/OUT endpoints were not found")
            cameraInterface = endpointSet.usbInterface
            val opened = usbManager.openDevice(device)
                ?: throw PtpException(message = "UsbManager.openDevice returned null")
            connection = opened
            if (!opened.claimInterface(endpointSet.usbInterface, true)) {
                throw PtpException(message = "Could not claim USB interface ${endpointSet.usbInterface.id}")
            }
            log.add(
                "Claimed interface=${endpointSet.usbInterface.id} class=${endpointSet.usbInterface.interfaceClass} " +
                    "bulkIn=${Ptp.hex16(endpointSet.bulkIn.address)} bulkOut=${Ptp.hex16(endpointSet.bulkOut.address)}",
            )
            if (recoverPtpSession) {
                requestPtpDeviceReset(opened, endpointSet.usbInterface)
                forceLiveViewRestart = true
            }
            val activeTransport = PtpUsbTransport(opened, endpointSet.bulkIn, endpointSet.bulkOut, log)
            transport = activeTransport

            val infoData = activeTransport.execute(
                code = Ptp.GET_DEVICE_INFO,
                transactionIdOverride = 0L,
            ).data
                ?: throw PtpException(message = "GetDeviceInfo returned no dataset")
            val info = PtpDatasetParser.deviceInfo(infoData)
            verifyIdentity(info)
            deviceInfo = info
            logDeviceInfo(info)

            activeTransport.execute(
                code = Ptp.OPEN_SESSION,
                parameters = listOf(1L),
                acceptedResponses = setOf(Ptp.RESPONSE_OK, Ptp.RESPONSE_SESSION_ALREADY_OPEN),
                transactionIdOverride = 0L,
            )
            sessionOpen = true

            initializePcMode(info)
            initializeObjectTracking()
            endpointSet.interruptIn?.let(::startEventReader)
            startObjectWatcher()
            val controls = readExposureControls()
            return CameraSession(
                identity = CameraIdentity(
                    manufacturer = info.manufacturer,
                    model = info.model,
                    serialNumber = info.serialNumber,
                    usbVendorId = device.vendorId,
                    usbProductId = device.productId,
                ),
                exposureControls = controls,
            )
        } catch (error: Throwable) {
            log.add("Connect failed: ${error.message}")
            forceClose()
            throw error
        }
    }

    override suspend fun startLiveView() {
        if (liveViewJob?.isActive == true) return
        val info = deviceInfo ?: throw PtpException(message = "Camera is not connected")
        if (Ptp.OMD_GET_LIVE_VIEW_IMAGE !in info.operations) {
            throw PtpException(message = "Camera did not advertise ${Ptp.hex16(Ptp.OMD_GET_LIVE_VIEW_IMAGE)}")
        }
        val forceRestart = forceLiveViewRestart
        initializeLiveViewMode(info, forceRestart)
        forceLiveViewRestart = false
        liveViewJob = scope.launch {
            var consecutiveErrors = 0
            while (isActive) {
                try {
                    val payload = requiredTransport().execute(
                        Ptp.OMD_GET_LIVE_VIEW_IMAGE,
                        parameters = listOf(1L),
                    ).data
                    val jpeg = payload?.let(::extractJpeg)
                    if (jpeg != null && jpeg.size >= 1024) {
                        mutableFrames.emit(jpeg)
                        consecutiveErrors = 0
                    } else {
                        log.add("Live view returned no decodable JPEG")
                        consecutiveErrors++
                    }
                    delay(35)
                } catch (error: PtpException) {
                    consecutiveErrors++
                    if (error.responseCode != Ptp.RESPONSE_DEVICE_BUSY) {
                        log.add("Live view error: ${error.message}")
                    }
                    delay(if (consecutiveErrors >= 5) 500 else 80)
                } catch (error: Throwable) {
                    consecutiveErrors++
                    log.add("Live view stopped by I/O error: ${error.message}")
                    delay(500)
                }
            }
        }
        log.add("Live view loop started")
    }

    override suspend fun stopLiveView() {
        liveViewJob?.cancelAndJoin()
        liveViewJob = null
        log.add("Live view loop stopped")
    }

    override suspend fun capture(
        phoneSaveFormat: PhoneSaveFormat,
        onPreview: suspend (ByteArray) -> Unit,
        onObject: suspend (DownloadedObject) -> Unit,
    ): CaptureReport = cameraOperationMutex.withLock {
        captureLocked(phoneSaveFormat, onPreview, onObject)
    }

    private suspend fun captureLocked(
        phoneSaveFormat: PhoneSaveFormat,
        onPreview: suspend (ByteArray) -> Unit,
        onObject: suspend (DownloadedObject) -> Unit,
    ): CaptureReport {
        val info = deviceInfo ?: throw PtpException(message = "Camera is not connected")
        if (Ptp.OMD_CAPTURE !in info.operations) {
            throw PtpException(message = "Camera did not advertise capture command ${Ptp.hex16(Ptp.OMD_CAPTURE)}")
        }
        val resumeLiveView = liveViewJob?.isActive == true
        stopLiveView()
        appCaptureActive = true
        try {
            drainObjectEvents()
            val baseline = try {
                getAllObjectHandles().toSet()
            } catch (error: Throwable) {
                log.add("Could not take pre-capture object snapshot: ${error.message}")
                null
            }
            baseline?.let { objectTracker.observeSnapshot(it, queueForImport = false) }

            logCaptureTarget()
            executeCaptureCommand(parameter = 3L)
            executeCaptureCommand(parameter = 6L)
            delay(80)
            if (Ptp.OMD_CHANGED_PROPERTIES in info.operations) {
                runCatching { requiredTransport().execute(Ptp.OMD_CHANGED_PROPERTIES) }
                    .onFailure { log.add("ChangedProperties read failed: ${it.message}") }
            }

            val handles = waitForNewHandles(baseline)
            objectTracker.markKnown(handles)
            return transferObjectHandles(
                info = info,
                handles = handles,
                phoneSaveFormat = phoneSaveFormat,
                sourceLabel = "App shutter",
                onPreview = onPreview,
                onObject = onObject,
            )
        } finally {
            appCaptureActive = false
            if (resumeLiveView && connection != null && coroutineContext.isActive) {
                runCatching { startLiveView() }
                    .onFailure { log.add("Could not resume live view: ${it.message}") }
            }
        }
    }

    override suspend fun importExternalCapture(
        phoneSaveFormat: PhoneSaveFormat,
        onPreview: suspend (ByteArray) -> Unit,
        onObject: suspend (DownloadedObject) -> Unit,
    ): CaptureReport = cameraOperationMutex.withLock {
        val info = deviceInfo ?: throw PtpException(message = "Camera is not connected")
        val resumeLiveView = liveViewJob?.isActive == true
        externalImportActive = true
        try {
            stopLiveView()
            val handles = awaitExternalCaptureHandles()
            if (handles.isEmpty()) {
                throw PtpException(message = "カメラ側撮影の新規ファイルをカード1/2から確認できませんでした")
            }
            transferObjectHandles(
                info = info,
                handles = handles,
                phoneSaveFormat = phoneSaveFormat,
                sourceLabel = "Camera shutter",
                onPreview = onPreview,
                onObject = onObject,
            )
        } finally {
            externalImportActive = false
            if (resumeLiveView && connection != null && coroutineContext.isActive) {
                runCatching { startLiveView() }
                    .onFailure { log.add("Could not resume live view after camera-side capture: ${it.message}") }
            }
            if (objectTracker.pendingCount() > 0 && connection != null && coroutineContext.isActive) {
                scope.launch {
                    delay(EXTERNAL_CAPTURE_RENOTIFY_DELAY_MS)
                    if (!externalImportActive && objectTracker.pendingCount() > 0) {
                        mutableExternalCaptureEvents.emit(Unit)
                    }
                }
            }
        }
    }

    private suspend fun awaitExternalCaptureHandles(): List<Long> {
        val started = SystemClock.elapsedRealtime()
        var lastAddedAt = started
        var previousPendingCount = objectTracker.pendingCount()
        while (SystemClock.elapsedRealtime() - started < EXTERNAL_CAPTURE_MAX_WAIT_MS) {
            delay(EXTERNAL_CAPTURE_POLL_INTERVAL_MS)
            runCatching { getAllObjectHandles() }
                .onSuccess { handles ->
                    objectTracker.observeSnapshot(handles, queueForImport = true)
                }
                .onFailure { log.add("Camera-side capture poll failed: ${it.message}") }
            val pendingCount = objectTracker.pendingCount()
            val now = SystemClock.elapsedRealtime()
            if (pendingCount > previousPendingCount) lastAddedAt = now
            previousPendingCount = pendingCount
            if (pendingCount > 0 && now - lastAddedAt >= EXTERNAL_COMPANION_QUIET_MS) break
        }
        return objectTracker.takePending().also { handles ->
            log.add(
                "Camera-side capture handles: " +
                    handles.joinToString { Ptp.hex32(it) }.ifBlank { "none" },
            )
        }
    }

    private suspend fun transferObjectHandles(
        info: PtpDeviceInfo,
        handles: List<Long>,
        phoneSaveFormat: PhoneSaveFormat,
        sourceLabel: String,
        onPreview: suspend (ByteArray) -> Unit,
        onObject: suspend (DownloadedObject) -> Unit,
    ): CaptureReport {
        val warnings = mutableListOf<String>()
        val summaries = mutableListOf<CaptureObjectSummary>()
        var previewDelivered = false
        var previewJpegFallbackUsed = false

        suspend fun deliverPreview(bytes: ByteArray) {
            try {
                onPreview(bytes)
                previewDelivered = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = "Preview delivery failed: ${error.message}"
                warnings += message
                log.add(message)
            }
        }

        suspend fun deliverObject(item: DownloadedObject) {
            summaries += CaptureObjectSummary(
                handle = item.handle,
                filename = item.filename,
                format = item.format,
                byteCount = item.bytes.size,
            )
            try {
                onObject(item)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = "${item.filename} handoff failed: ${error.message}"
                warnings += message
                log.add(message)
            }
        }

        val candidates = mutableListOf<ObjectCandidate>()
        for (handle in handles.take(MAX_OBJECTS_PER_CAPTURE)) {
            try {
                candidates += readObjectCandidate(handle)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = "Object ${Ptp.hex32(handle)} info failed: ${error.message}"
                warnings += message
                log.add(message)
            }
        }

        log.add(
            "$sourceLabel phone save=${phoneSaveFormat.name}; candidates=" +
                candidates.joinToString { candidate ->
                    "${candidate.displayName}@${Ptp.hex32(candidate.info.storageId)}"
                }.ifBlank { "none" },
        )

        fun orderedCandidates(format: PhoneSaveFormat): List<ObjectCandidate> {
            return CaptureSavePolicy
                .orderedPreferred(format, candidates.map(ObjectCandidate::info))
                .mapNotNull { chosen ->
                    candidates.firstOrNull { candidate -> candidate.info === chosen }
                }
        }

        when (phoneSaveFormat) {
            PhoneSaveFormat.JPEG -> {
                val fullJpegCandidates = orderedCandidates(PhoneSaveFormat.JPEG)
                var jpegThumbnailFallback: DownloadedObject? = null
                for (candidate in fullJpegCandidates) {
                    if (!previewDelivered && candidate.info.thumbSize > 0L) {
                        readObjectThumbnail(candidate)?.let { thumbnail ->
                            deliverPreview(thumbnail)
                            jpegThumbnailFallback = DownloadedObject(
                                handle = candidate.handle,
                                filename = previewFilename(candidate.info.filename),
                                format = CaptureSavePolicy.JPEG_OBJECT_FORMAT,
                                bytes = thumbnail,
                            )
                        }
                    }
                    val item = downloadSelectedObject(candidate, warnings)
                    if (item != null) {
                        extractJpeg(item.bytes)?.let { deliverPreview(it) }
                        deliverObject(item)
                        break
                    }
                }
                if (summaries.isEmpty()) {
                    var fallback = jpegThumbnailFallback
                    if (fallback == null) {
                        for (rawCandidate in orderedCandidates(PhoneSaveFormat.RAW)) {
                            val preview = readObjectThumbnail(rawCandidate)
                            if (preview != null) {
                                fallback = DownloadedObject(
                                    handle = rawCandidate.handle,
                                    filename = previewFilename(rawCandidate.info.filename),
                                    format = CaptureSavePolicy.JPEG_OBJECT_FORMAT,
                                    bytes = preview,
                                )
                                break
                            }
                        }
                    }
                    if (fallback == null && Ptp.OMD_GET_IMAGE in info.operations) {
                        fallback = getCapturedJpegFallback()
                    }
                    if (fallback != null) {
                        previewJpegFallbackUsed = true
                        val message =
                            "フルJPEGを保存できなかったため、プレビューJPEGを保存しました（解像度・画質に制限があります）"
                        warnings += message
                        log.add(message)
                        deliverPreview(fallback.bytes)
                        deliverObject(fallback)
                    }
                }
            }

            PhoneSaveFormat.RAW -> {
                val rawCandidates = orderedCandidates(PhoneSaveFormat.RAW)
                rawCandidates.firstOrNull { it.info.thumbSize > 0L }
                    ?.let { readObjectThumbnail(it) }
                    ?.let { deliverPreview(it) }
                if (rawCandidates.isNotEmpty()) {
                    if (!previewDelivered && Ptp.OMD_GET_IMAGE in info.operations) {
                        getCapturedJpegFallback()?.let { deliverPreview(it.bytes) }
                    }
                    for (candidate in rawCandidates) {
                        val item = downloadSelectedObject(candidate, warnings)
                        if (item != null) {
                            deliverObject(item)
                            break
                        }
                    }
                }
            }
        }

        if (summaries.isEmpty()) {
            throw PtpException(
                message = when (phoneSaveFormat) {
                    PhoneSaveFormat.JPEG ->
                        "シャッター後にJPEGもRAWプレビューも取得できませんでした。カメラのカード保存設定と診断ログを確認してください。"
                    PhoneSaveFormat.RAW ->
                        "シャッター後にORFが見つかりませんでした。カメラ側でRAW記録が有効か確認してください。"
                },
            )
        }

        log.add("$sourceLabel complete: ${summaries.joinToString { "${it.filename} (${it.byteCount} B)" }}")
        return CaptureReport(
            objects = summaries,
            warnings = warnings.distinct(),
            previewJpegFallbackUsed = previewJpegFallbackUsed,
        )
    }

    private suspend fun downloadSelectedObject(
        candidate: ObjectCandidate,
        warnings: MutableList<String>,
    ): DownloadedObject? = try {
        downloadObject(candidate)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        val message = "${candidate.displayName} download failed: ${error.message}"
        warnings += message
        log.add(message)
        null
    }

    override suspend fun refreshExposureControls(): List<ExposureControl> =
        cameraOperationMutex.withLock {
            ExposureFormatter.definitions.mapNotNull { definition ->
                val code = definition.candidates.firstOrNull(descriptors::containsKey)
                    ?: return@mapNotNull null
                val previous = descriptors.getValue(code)
                val current = runCatching { getPropertyValue(code, previous.dataType) }
                    .onFailure {
                        log.add("Property ${Ptp.hex16(code)} value refresh failed: ${it.message}")
                    }
                    .getOrNull()
                    ?: previous.current
                if (current.raw != previous.current.raw) {
                    log.add(
                        "Property ${Ptp.hex16(code)} changed " +
                            "${Ptp.hex32(previous.current.raw)} -> ${Ptp.hex32(current.raw)}",
                    )
                }
                val refreshed = previous.copy(current = current)
                descriptors[code] = refreshed
                ExposureFormatter.toControl(definition.title, refreshed)
            }
        }

    override suspend fun setExposure(propertyCode: Int, value: PtpScalar): List<ExposureControl> =
        cameraOperationMutex.withLock {
            val descriptor = descriptors[propertyCode]
                ?: throw PtpException(message = "Property ${Ptp.hex16(propertyCode)} was not read from the camera")
            if (!descriptor.writable) throw PtpException(message = "Property ${Ptp.hex16(propertyCode)} is read-only")
            if (descriptor.dataType != value.dataType) throw PtpException(message = "Property data type mismatch")
            if (!isAllowedValue(descriptor, value)) {
                throw PtpException(message = "The camera did not advertise ${Ptp.hex32(value.raw)} for this property")
            }
            requiredTransport().execute(
                code = Ptp.SET_DEVICE_PROP_VALUE,
                parameters = listOf(propertyCode.toLong()),
                outgoingData = value.encode(),
            )
            log.add("Set property ${Ptp.hex16(propertyCode)} = ${Ptp.hex32(value.raw)}")
            delay(80)
            readExposureControls()
        }

    override suspend fun disconnect() {
        runCatching { stopLiveView() }
        objectWatcherJob?.cancelAndJoin()
        objectWatcherJob = null
        eventReaderJob?.cancelAndJoin()
        eventReaderJob = null
        if (sessionOpen) {
            runCatching { requiredTransport().execute(Ptp.CLOSE_SESSION) }
                .onFailure { log.add("CloseSession failed: ${it.message}") }
        }
        sessionOpen = false
        releaseUsb()
        scope.cancel()
        log.add("Disconnected")
    }

    override fun forceClose() {
        liveViewJob?.cancel()
        liveViewJob = null
        objectWatcherJob?.cancel()
        objectWatcherJob = null
        eventReaderJob?.cancel()
        eventReaderJob = null
        sessionOpen = false
        scope.cancel()
        releaseUsb()
    }

    override fun diagnosticsText(): String = buildString {
        appendLine("OM Tether 0.3.2")
        appendLine("USB: VID=${Ptp.hex16(device.vendorId)} PID=${Ptp.hex16(device.productId)} name=${device.deviceName}")
        deviceInfo?.let { info ->
            appendLine("Camera: ${info.manufacturer} ${info.model}")
            appendLine("Firmware/device version: ${info.deviceVersion}")
            appendLine("Operations: ${info.operations.sorted().joinToString { Ptp.hex16(it) }}")
            appendLine("Events: ${info.events.sorted().joinToString { Ptp.hex16(it) }}")
            appendLine("Properties: ${info.properties.sorted().joinToString { Ptp.hex16(it) }}")
        }
        appendLine()
        append(log.text())
    }

    private suspend fun logCaptureTarget() {
        runCatching {
            val bytes = requiredTransport().execute(
                Ptp.GET_DEVICE_PROP_VALUE,
                parameters = listOf(Ptp.PROP_CAPTURE_TARGET.toLong()),
            ).data ?: return
            if (bytes.size >= 2) {
                val target = PtpCursor(bytes).scalar(Ptp.TYPE_UINT16).raw
                log.add("Capture target ${Ptp.hex16(Ptp.PROP_CAPTURE_TARGET)}=${Ptp.hex32(target)}")
            }
        }.onFailure {
            // Some firmware does not advertise/read this optional property. Capture is
            // still valid, so retain the diagnostic and continue.
            log.add("Capture target read unavailable: ${it.message}")
        }
    }

    private suspend fun executeCaptureCommand(parameter: Long) {
        var lastBusy: PtpException? = null
        repeat(CAPTURE_COMMAND_ATTEMPTS) { attempt ->
            try {
                requiredTransport().execute(Ptp.OMD_CAPTURE, parameters = listOf(parameter))
                return
            } catch (error: PtpException) {
                if (
                    error.responseCode != Ptp.RESPONSE_DEVICE_BUSY ||
                    attempt == CAPTURE_COMMAND_ATTEMPTS - 1
                ) {
                    throw error
                }
                lastBusy = error
                val waitMs = CAPTURE_COMMAND_RETRY_DELAY_MS * (attempt + 1)
                log.add(
                    "Capture parameter $parameter busy; " +
                        "retry ${attempt + 2}/$CAPTURE_COMMAND_ATTEMPTS in ${waitMs}ms",
                )
                delay(waitMs)
            }
        }
        throw lastBusy ?: PtpException(message = "Capture command failed")
    }

    private suspend fun initializePcMode(info: PtpDeviceInfo) {
        val descriptor = if (Ptp.PROP_PC_MODE in info.properties) {
            runCatching { getDescriptor(Ptp.PROP_PC_MODE) }
                .onFailure { log.add("PC mode descriptor unavailable: ${it.message}") }
                .getOrNull()
        } else {
            null
        }
        if (descriptor?.current?.raw == 1L) return
        if (descriptor != null && (!descriptor.writable || descriptor.dataType != Ptp.TYPE_UINT16)) {
            log.add("Refusing PC mode write with descriptor type/access mismatch")
            return
        }

        // OM-D initialization uses UINT16 value 1 even on bodies that omit D052 from DeviceInfo.
        // Identity has already been checked against both the OM-1 Mark II USB ID and PTP strings.
        runCatching {
            setKnownPropertyWhenReady(
                propertyCode = Ptp.PROP_PC_MODE,
                value = PtpScalar(Ptp.TYPE_UINT16, 1L),
            )
        }.onSuccess {
            log.add("PC mode enabled through ${Ptp.hex16(Ptp.PROP_PC_MODE)}")
            delay(120)
        }.onFailure {
            log.add("PC mode initialization was not accepted: ${it.message}")
        }
    }

    private suspend fun initializeLiveViewMode(info: PtpDeviceInfo, forceRestart: Boolean) {
        val descriptor = if (Ptp.PROP_LIVE_VIEW_MODE in info.properties) {
            runCatching { getDescriptor(Ptp.PROP_LIVE_VIEW_MODE) }
                .onFailure { log.add("Live-view descriptor unavailable: ${it.message}") }
                .getOrNull()
        } else {
            log.add("Live-view property ${Ptp.hex16(Ptp.PROP_LIVE_VIEW_MODE)} was not advertised; using verified OM-D initialization value")
            null
        }
        if (descriptor != null && (!descriptor.writable || descriptor.dataType != Ptp.TYPE_UINT32)) {
            log.add("Refusing live-view mode write with descriptor type/access mismatch")
            return
        }
        if (forceRestart) {
            runCatching {
                setKnownPropertyWhenReady(
                    propertyCode = Ptp.PROP_LIVE_VIEW_MODE,
                    value = PtpScalar(Ptp.TYPE_UINT32, 0L),
                )
            }.onSuccess {
                log.add("Live-view property cleared for recovery")
                delay(LIVE_VIEW_RESTART_SETTLE_MS)
            }.onFailure {
                // Some firmware rejects the disabled value while still accepting a fresh
                // enabled value, so retain this as a fallback rather than aborting reconnect.
                log.add("Live-view clear was not accepted: ${it.message}")
            }
        } else if (descriptor?.current?.raw == Ptp.LIVE_VIEW_ENABLED_VALUE) {
            return
        }
        runCatching {
            setKnownPropertyWhenReady(
                propertyCode = Ptp.PROP_LIVE_VIEW_MODE,
                value = PtpScalar(Ptp.TYPE_UINT32, Ptp.LIVE_VIEW_ENABLED_VALUE),
            )
        }.onSuccess {
            log.add("Live-view property set to ${Ptp.hex32(Ptp.LIVE_VIEW_ENABLED_VALUE)}")
            delay(100)
        }.onFailure {
            log.add("Live-view initialization was not accepted: ${it.message}")
        }
    }

    private suspend fun requestPtpDeviceReset(
        activeConnection: UsbDeviceConnection,
        usbInterface: UsbInterface,
    ) {
        val requestType =
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIPIENT_INTERFACE
        val result = activeConnection.controlTransfer(
            requestType,
            PTP_DEVICE_RESET_REQUEST,
            0,
            usbInterface.id,
            null,
            0,
            USB_CONTROL_TIMEOUT_MS,
        )
        if (result < 0) {
            log.add("PTP USB class DeviceReset 0x66 was not accepted; continuing with live-view reset fallback")
        } else {
            log.add("PTP USB class DeviceReset 0x66 completed")
        }
        delay(PTP_RESET_SETTLE_MS)
    }

    private suspend fun setKnownPropertyWhenReady(propertyCode: Int, value: PtpScalar) {
        var lastBusy: PtpException? = null
        repeat(PROPERTY_WRITE_ATTEMPTS) { attempt ->
            try {
                requiredTransport().execute(
                    code = Ptp.SET_DEVICE_PROP_VALUE,
                    parameters = listOf(propertyCode.toLong()),
                    outgoingData = value.encode(),
                )
                return
            } catch (error: PtpException) {
                if (
                    error.responseCode != Ptp.RESPONSE_DEVICE_BUSY ||
                    attempt == PROPERTY_WRITE_ATTEMPTS - 1
                ) {
                    throw error
                }
                lastBusy = error
                val waitMs = PROPERTY_WRITE_RETRY_DELAY_MS * (attempt + 1)
                log.add(
                    "Property ${Ptp.hex16(propertyCode)} busy; " +
                        "retry ${attempt + 2}/$PROPERTY_WRITE_ATTEMPTS in ${waitMs}ms",
                )
                delay(waitMs)
            }
        }
        throw lastBusy ?: PtpException(message = "Property write failed")
    }

    private suspend fun readExposureControls(): List<ExposureControl> {
        val supported = deviceInfo?.properties.orEmpty()
        descriptors.clear()
        return ExposureFormatter.definitions.mapNotNull { definition ->
            definition.candidates
                .sortedByDescending(supported::contains)
                .firstNotNullOfOrNull { code ->
                    val descriptor = runCatching { getDescriptor(code) }
                        .onFailure {
                            if (code in supported) {
                                log.add("Property ${Ptp.hex16(code)} descriptor failed: ${it.message}")
                            }
                        }
                        .getOrNull() ?: return@firstNotNullOfOrNull null
                    descriptors[code] = descriptor
                    ExposureFormatter.toControl(definition.title, descriptor)
                }
        }
    }

    private suspend fun getPropertyValue(code: Int, dataType: Int): PtpScalar {
        val bytes = requiredTransport().execute(
            Ptp.GET_DEVICE_PROP_VALUE,
            parameters = listOf(code.toLong()),
        ).data ?: throw PtpException(message = "Property value ${Ptp.hex16(code)} has no data")
        return PtpCursor(bytes).scalar(dataType)
    }

    private suspend fun getDescriptor(code: Int): PtpPropertyDescriptor {
        val bytes = requiredTransport().execute(
            Ptp.GET_DEVICE_PROP_DESC,
            parameters = listOf(code.toLong()),
        ).data ?: throw PtpException(message = "Property descriptor ${Ptp.hex16(code)} has no data")
        return PtpDatasetParser.propertyDescriptor(bytes).also { descriptor ->
            log.add(
                "Property ${Ptp.hex16(code)} type=${Ptp.hex16(descriptor.dataType)} " +
                    "writable=${descriptor.writable} current=${Ptp.hex32(descriptor.current.raw)} " +
                    "choices=${descriptor.values.size}",
            )
        }
    }

    private fun isAllowedValue(descriptor: PtpPropertyDescriptor, value: PtpScalar): Boolean {
        return when (descriptor.form) {
            Ptp.FORM_ENUMERATION -> descriptor.values.any { it.raw == value.raw }
            Ptp.FORM_RANGE -> {
                val min = descriptor.minimum?.raw ?: return false
                val max = descriptor.maximum?.raw ?: return false
                val step = descriptor.step?.raw ?: return false
                step > 0L && value.raw in min..max && (value.raw - min) % step == 0L
            }
            else -> value.raw == descriptor.current.raw
        }
    }

    private suspend fun getAllObjectHandles(): List<Long> {
        val data = requiredTransport().execute(
            Ptp.GET_OBJECT_HANDLES,
            parameters = listOf(0xFFFF_FFFFL, 0L, 0L),
        ).data ?: throw PtpException(message = "GetObjectHandles returned no data")
        return PtpDatasetParser.objectHandles(data)
    }

    private suspend fun initializeObjectTracking() {
        runCatching { getAllObjectHandles() }
            .onSuccess { handles ->
                objectTracker.initialize(handles)
                log.add("Object baseline initialized across card 1/2: ${handles.size} handles")
            }
            .onFailure {
                // The first successful watcher poll becomes the silent baseline. Explicit
                // interrupt events are still retained if they arrive before then.
                log.add("Object baseline unavailable; watcher will retry: ${it.message}")
            }
    }

    private fun startObjectWatcher() {
        if (objectWatcherJob?.isActive == true) return
        objectWatcherJob = scope.launch {
            while (isActive) {
                delay(OBJECT_WATCH_INTERVAL_MS)
                if (!sessionOpen) continue
                try {
                    val shouldNotify = cameraOperationMutex.withLock {
                        val handles = getAllObjectHandles()
                        objectTracker.observeSnapshot(
                            handles = handles,
                            queueForImport = !appCaptureActive,
                        )
                    }
                    if (shouldNotify) {
                        log.add("New camera-side object found by card 1/2 polling")
                        mutableExternalCaptureEvents.tryEmit(Unit)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (isActive) log.add("Object watcher poll failed: ${error.message}")
                }
            }
        }
        log.add("Camera-side shutter watcher started")
    }

    private suspend fun waitForNewHandles(baseline: Set<Long>?): List<Long> {
        val started = SystemClock.elapsedRealtime()
        var firstSeenAt: Long? = null
        var lastStoragePollAt = 0L
        val found = linkedSetOf<Long>()
        while (SystemClock.elapsedRealtime() - started < CAPTURE_TIMEOUT_MS) {
            delay(120)
            while (true) {
                val handle = objectAddedEvents.tryReceive().getOrNull() ?: break
                if (handle != 0L && handle != 0xFFFF_FFFFL) found += handle
            }
            val now = SystemClock.elapsedRealtime()
            if (baseline != null && now - lastStoragePollAt >= STORAGE_POLL_INTERVAL_MS) {
                lastStoragePollAt = now
                val current = runCatching { getAllObjectHandles() }
                    .onFailure { log.add("Object poll failed: ${it.message}") }
                    .getOrNull()
                if (current != null) found += current.filterNot(baseline::contains)
            }
            if (found.isNotEmpty()) {
                if (firstSeenAt == null) firstSeenAt = SystemClock.elapsedRealtime()
            }
            if (firstSeenAt != null && SystemClock.elapsedRealtime() - firstSeenAt >= COMPANION_OBJECT_WAIT_MS) break
        }
        log.add("New handles: ${found.joinToString { Ptp.hex32(it) }.ifBlank { "none" }}")
        return found.toList()
    }

    private fun startEventReader(endpoint: UsbEndpoint) {
        if (eventReaderJob?.isActive == true) return
        val activeConnection = connection ?: return
        eventReaderJob = scope.launch {
            val request = UsbRequest()
            if (!request.initialize(activeConnection, endpoint)) {
                log.add("Interrupt endpoint could not be initialized; object polling remains active")
                request.close()
                return@launch
            }
            val buffer = ByteBuffer.allocate(128)
            var queued = false
            try {
                while (isActive) {
                    if (!queued) {
                        buffer.clear()
                        queued = request.queue(buffer)
                        if (!queued) {
                            log.add("Interrupt request queue failed")
                            break
                        }
                    }
                    val completed = try {
                        activeConnection.requestWait(500L)
                    } catch (_: TimeoutException) {
                        // A quiet interrupt endpoint normally times out between camera events.
                        continue
                    } catch (error: Throwable) {
                        if (isActive) log.add("Interrupt endpoint stopped: ${error.message}")
                        break
                    }
                    if (completed == null) {
                        log.add("Interrupt endpoint returned an I/O error; object polling remains active")
                        break
                    }
                    if (completed !== request) {
                        log.add("Unexpected UsbRequest completion on event reader")
                        break
                    }
                    queued = false
                    val byteCount = buffer.position()
                    if (byteCount >= 12) {
                        buffer.flip()
                        val bytes = ByteArray(byteCount)
                        buffer.get(bytes)
                        parseEventPackets(bytes)
                    }
                }
            } finally {
                if (queued) runCatching { request.cancel() }
                request.close()
            }
        }
        log.add("Interrupt event reader started at ${Ptp.hex16(endpoint.address)}")
    }

    private fun parseEventPackets(bytes: ByteArray) {
        var offset = 0
        while (bytes.size - offset >= 12) {
            val tail = bytes.copyOfRange(offset, bytes.size)
            val length = runCatching { PtpCodec.declaredLength(tail) }.getOrNull() ?: return
            if (length > tail.size) return
            val event = runCatching { PtpCodec.decode(tail.copyOf(length)) }.getOrNull() ?: return
            if (event.type == Ptp.CONTAINER_EVENT) {
                val parameters = runCatching { PtpCodec.responseParameters(event.payload) }.getOrDefault(emptyList())
                log.add("USB event ${Ptp.hex16(event.code)} params=${parameters.joinToString { Ptp.hex32(it) }}")
                if (event.code in OBJECT_ADDED_EVENT_CODES) {
                    parameters.firstOrNull()?.let { handle ->
                        objectAddedEvents.trySend(handle)
                        if (objectTracker.recordEvent(handle, queueForImport = !appCaptureActive)) {
                            log.add("Camera-side ObjectAdded queued for Android import")
                            if (!externalImportActive) mutableExternalCaptureEvents.tryEmit(Unit)
                        }
                    }
                }
            }
            offset += length
        }
    }

    private fun drainObjectEvents() {
        while (objectAddedEvents.tryReceive().isSuccess) {
            // Drain stale notifications before issuing the next shutter command.
        }
    }

    private suspend fun readObjectCandidate(handle: Long): ObjectCandidate {
        val objectInfo = retryObjectRead("GetObjectInfo ${Ptp.hex32(handle)}") {
            val infoBytes = requiredTransport().execute(
                Ptp.GET_OBJECT_INFO,
                parameters = listOf(handle),
            ).data ?: throw PtpException(message = "GetObjectInfo returned no data")
            PtpDatasetParser.objectInfo(infoBytes)
        }
        log.add(
            "ObjectInfo handle=${Ptp.hex32(handle)} name=${objectInfo.filename} " +
                "storage=${Ptp.hex32(objectInfo.storageId)} format=${Ptp.hex16(objectInfo.format)} " +
                "size=${objectInfo.compressedSize}",
        )
        return ObjectCandidate(handle, objectInfo)
    }

    private suspend fun readObjectThumbnail(candidate: ObjectCandidate): ByteArray? {
        return try {
            retryObjectRead("GetThumb ${Ptp.hex32(candidate.handle)}") {
                requiredTransport().execute(
                    Ptp.GET_THUMB,
                    parameters = listOf(candidate.handle),
                ).data?.let(::extractJpeg)
                    ?: throw PtpException(message = "GetThumb returned no decodable JPEG")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.add("${candidate.displayName} thumbnail unavailable: ${error.message}")
            null
        }
    }

    private suspend fun downloadObject(candidate: ObjectCandidate): DownloadedObject {
        val bytes = retryObjectRead("GetObject ${Ptp.hex32(candidate.handle)}") {
            requiredTransport().execute(
                Ptp.GET_OBJECT,
                parameters = listOf(candidate.handle),
            ).data ?: throw PtpException(message = "GetObject returned no data")
        }
        validateObjectBytes(candidate, bytes)
        val objectInfo = candidate.info
        val filename = objectInfo.filename.ifBlank { fallbackFilename(objectInfo.format) }
        return DownloadedObject(candidate.handle, filename, objectInfo.format, bytes)
    }

    private suspend fun <T> retryObjectRead(label: String, block: suspend () -> T): T {
        var lastError: PtpException? = null
        repeat(OBJECT_READ_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: PtpException) {
                val retryable = error.responseCode == Ptp.RESPONSE_DEVICE_BUSY ||
                    error.responseCode == Ptp.RESPONSE_INVALID_OBJECT_HANDLE
                if (!retryable || attempt == OBJECT_READ_ATTEMPTS - 1) throw error
                lastError = error
                val waitMs = OBJECT_RETRY_BASE_DELAY_MS * (attempt + 1)
                log.add("$label not ready; retry ${attempt + 2}/$OBJECT_READ_ATTEMPTS in ${waitMs}ms")
                delay(waitMs)
            }
        }
        throw lastError ?: PtpException(message = "$label failed")
    }

    private fun validateObjectBytes(candidate: ObjectCandidate, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            throw PtpException(message = "${candidate.displayName} was empty")
        }
        val expected = candidate.info.compressedSize
        if (expected in 1L until 0xFFFF_FFFFL && expected != bytes.size.toLong()) {
            throw PtpException(
                message = "${candidate.displayName} size mismatch: expected $expected B, received ${bytes.size} B",
            )
        }
        if (CaptureSavePolicy.isJpeg(candidate.info) && !hasJpegMagic(bytes)) {
            throw PtpException(message = "${candidate.displayName} did not contain a valid JPEG header")
        }
    }

    private suspend fun getCapturedJpegFallback(): DownloadedObject? {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < FALLBACK_IMAGE_TIMEOUT_MS) {
            try {
                val bytes = requiredTransport().execute(Ptp.OMD_GET_IMAGE).data?.let(::extractJpeg)
                if (bytes != null) {
                    val filename = "OM_CAPTURE_${timestamp()}.JPG"
                    log.add("Using OMD GetImage fallback: ${bytes.size} B")
                    return DownloadedObject(null, filename, CaptureSavePolicy.JPEG_OBJECT_FORMAT, bytes)
                }
            } catch (error: PtpException) {
                if (error.responseCode != Ptp.RESPONSE_DEVICE_BUSY) {
                    log.add("OMD GetImage fallback failed: ${error.message}")
                    return null
                }
            }
            delay(200)
        }
        return null
    }

    private fun verifyIdentity(info: PtpDeviceInfo) {
        val manufacturer = info.manufacturer.uppercase(Locale.US)
        val model = info.model.uppercase(Locale.US)
        if (("OMSYSTEM" !in manufacturer && "OLYMPUS" !in manufacturer) || "OM-1" !in model) {
            throw PtpException(
                message = "USB ID matched, but PTP identity was '${info.manufacturer} ${info.model}'. Control was not enabled.",
            )
        }
    }

    private fun logDeviceInfo(info: PtpDeviceInfo) {
        log.add("Camera identity: ${info.manufacturer} ${info.model}, version=${info.deviceVersion}")
        log.add("Supported operations: ${info.operations.sorted().joinToString { Ptp.hex16(it) }}")
        log.add("Supported properties: ${info.properties.sorted().joinToString { Ptp.hex16(it) }}")
    }

    private fun requiredTransport(): PtpUsbTransport = transport
        ?: throw PtpException(message = "PTP transport is not open")

    @Synchronized
    private fun releaseUsb() {
        val activeConnection = connection
        val activeInterface = cameraInterface
        if (activeConnection != null && activeInterface != null) {
            runCatching { activeConnection.releaseInterface(activeInterface) }
        }
        runCatching { activeConnection?.close() }
        connection = null
        cameraInterface = null
        transport = null
    }

    private fun findPtpEndpoints(device: UsbDevice): EndpointSet? {
        val candidates = (0 until device.interfaceCount)
            .map(device::getInterface)
            .sortedByDescending { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
        return candidates.firstNotNullOfOrNull { usbInterface ->
            val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
            val bulkIn = endpoints.firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
            }
            val bulkOut = endpoints.firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
            }
            val interruptIn = endpoints.firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == UsbConstants.USB_DIR_IN
            }
            if (bulkIn != null && bulkOut != null) EndpointSet(usbInterface, bulkIn, bulkOut, interruptIn) else null
        }
    }

    private fun extractJpeg(bytes: ByteArray): ByteArray? {
        var start = -1
        for (index in 0 until bytes.size - 1) {
            if (bytes[index] == 0xFF.toByte() && bytes[index + 1] == 0xD8.toByte()) {
                start = index
                break
            }
        }
        if (start < 0) return null
        var endExclusive = -1
        for (index in bytes.size - 2 downTo start + 2) {
            if (bytes[index] == 0xFF.toByte() && bytes[index + 1] == 0xD9.toByte()) {
                endExclusive = index + 2
                break
            }
        }
        if (endExclusive <= start) return null
        return if (start == 0 && endExclusive == bytes.size) {
            bytes
        } else {
            bytes.copyOfRange(start, endExclusive)
        }
    }

    private fun hasJpegMagic(bytes: ByteArray): Boolean {
        if (
            bytes.size < 4 ||
            bytes[0] != 0xFF.toByte() ||
            bytes[1] != 0xD8.toByte()
        ) {
            return false
        }
        for (index in bytes.size - 2 downTo 2) {
            if (bytes[index] == 0xFF.toByte() && bytes[index + 1] == 0xD9.toByte()) return true
        }
        return false
    }

    private fun previewFilename(rawFilename: String): String {
        val filename = rawFilename.ifBlank { "OM_CAPTURE_${timestamp()}.ORF" }
        val stem = filename.substringBeforeLast('.', filename)
        return "${stem}_PREVIEW.JPG"
    }

    private fun fallbackFilename(format: Int): String {
        val extension = if (format == CaptureSavePolicy.JPEG_OBJECT_FORMAT) "JPG" else "ORF"
        return "OM_CAPTURE_${timestamp()}.$extension"
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private data class EndpointSet(
        val usbInterface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
        val interruptIn: UsbEndpoint?,
    )

    private data class ObjectCandidate(
        val handle: Long,
        val info: PtpObjectInfo,
    ) {
        val displayName: String
            get() = info.filename.ifBlank { "object ${Ptp.hex32(handle)}" }
    }

    companion object {
        private const val PTP_DEVICE_RESET_REQUEST = 0x66
        private const val USB_RECIPIENT_INTERFACE = 0x01
        private const val USB_CONTROL_TIMEOUT_MS = 2_000
        private const val PTP_RESET_SETTLE_MS = 300L
        private const val LIVE_VIEW_RESTART_SETTLE_MS = 180L
        private const val CAPTURE_TIMEOUT_MS = 12_000L
        private const val COMPANION_OBJECT_WAIT_MS = 2_500L
        private const val STORAGE_POLL_INTERVAL_MS = 700L
        private const val OBJECT_WATCH_INTERVAL_MS = 1_200L
        private const val EXTERNAL_CAPTURE_POLL_INTERVAL_MS = 250L
        private const val EXTERNAL_COMPANION_QUIET_MS = 900L
        private const val EXTERNAL_CAPTURE_MAX_WAIT_MS = 3_000L
        private const val EXTERNAL_CAPTURE_RENOTIFY_DELAY_MS = 300L
        private const val FALLBACK_IMAGE_TIMEOUT_MS = 5_000L
        private const val MAX_OBJECTS_PER_CAPTURE = 8
        private const val OBJECT_READ_ATTEMPTS = 6
        private const val OBJECT_RETRY_BASE_DELAY_MS = 150L
        private const val CAPTURE_COMMAND_ATTEMPTS = 4
        private const val CAPTURE_COMMAND_RETRY_DELAY_MS = 120L
        private const val PROPERTY_WRITE_ATTEMPTS = 4
        private const val PROPERTY_WRITE_RETRY_DELAY_MS = 120L
        private val OBJECT_ADDED_EVENT_CODES = setOf(
            Ptp.EVENT_OBJECT_ADDED,
            Ptp.EVENT_OLYMPUS_OBJECT_ADDED,
            Ptp.EVENT_OM_OBJECT_ADDED_NEW,
        )
    }
}
