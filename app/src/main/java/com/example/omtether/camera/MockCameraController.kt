package com.example.omtether.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MockCameraController : CameraController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
    override val frames: Flow<ByteArray> = mutableFrames.asSharedFlow()
    override val externalCaptureEvents: Flow<Unit> = emptyFlow()
    private val log = DiagnosticLog()
    private var liveViewJob: Job? = null
    private var frameNumber = 0
    private var controls = demoControls()

    override suspend fun connect(): CameraSession {
        log.add("Demo controller connected; no USB commands are sent")
        return CameraSession(
            identity = CameraIdentity("DEMO", "OM‑1 Mark II simulator", "", 0, 0),
            exposureControls = controls,
        )
    }

    override suspend fun startLiveView() {
        if (liveViewJob?.isActive == true) return
        liveViewJob = scope.launch {
            while (isActive) {
                mutableFrames.emit(renderJpeg(frameNumber++, captured = false))
                delay(120)
            }
        }
        log.add("Demo live view started")
    }

    override suspend fun stopLiveView() {
        liveViewJob?.cancel()
        liveViewJob = null
        log.add("Demo live view stopped")
    }

    override suspend fun capture(
        phoneSaveFormat: PhoneSaveFormat,
        onPreview: suspend (ByteArray) -> Unit,
        onObject: suspend (DownloadedObject) -> Unit,
    ): CaptureReport {
        val bytes = renderJpeg(frameNumber++, captured = true)
        val filename = "DEMO_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.JPG"
        log.add("Demo capture created: $filename (${bytes.size} B)")
        val downloaded = DownloadedObject(null, filename, 0x3801, bytes)
        onPreview(bytes)
        onObject(downloaded)
        return CaptureReport(
            objects = listOf(CaptureObjectSummary(null, filename, 0x3801, bytes.size)),
            warnings = if (phoneSaveFormat == PhoneSaveFormat.RAW) {
                listOf("デモモードは実機RAWを生成できないため、JPEGサンプルを保存しました")
            } else {
                emptyList()
            },
        )
    }

    override suspend fun importExternalCapture(
        phoneSaveFormat: PhoneSaveFormat,
        onPreview: suspend (ByteArray) -> Unit,
        onObject: suspend (DownloadedObject) -> Unit,
    ): CaptureReport = throw PtpException(message = "Demo camera has no physical shutter events")

    override suspend fun refreshExposureControls(): List<ExposureControl> = controls

    override suspend fun setExposure(propertyCode: Int, value: PtpScalar): List<ExposureControl> {
        controls = controls.map { control ->
            if (control.propertyCode == propertyCode && control.options.any { it.value == value }) {
                control.copy(current = value)
            } else {
                control
            }
        }
        log.add("Demo property ${Ptp.hex16(propertyCode)} = ${Ptp.hex32(value.raw)}")
        return controls
    }

    override suspend fun disconnect() {
        stopLiveView()
        scope.cancel()
    }

    override fun forceClose() {
        scope.cancel()
    }

    override fun diagnosticsText(): String = buildString {
        appendLine("Mode: DEMO (camera and USB are not used)")
        append(log.text())
    }

    private fun renderJpeg(sequence: Int, captured: Boolean): ByteArray {
        val width = 1280
        val height = 853
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val phase = (sequence % 100) / 100f
        val background = Paint().apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(
                    Color.rgb(12, 25, 45),
                    Color.rgb((80 + phase * 90).toInt(), 58, 38),
                    Color.rgb(235, 175, 80),
                ),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

        val grid = Paint().apply {
            color = Color.argb(70, 255, 255, 255)
            strokeWidth = 2f
        }
        repeat(7) { x -> canvas.drawLine(width * x / 6f, 0f, width * x / 6f, height.toFloat(), grid) }
        repeat(5) { y -> canvas.drawLine(0f, height * y / 4f, width.toFloat(), height * y / 4f, grid) }

        val moving = Paint().apply { color = Color.rgb(255, 176, 0) }
        canvas.drawCircle(width * (0.2f + phase * 0.55f), height * 0.56f, 105f, moving)
        val highlight = Paint().apply { color = Color.WHITE }
        canvas.drawRect(width * 0.78f, height * 0.12f, width * 0.96f, height * 0.33f, highlight)

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 46f
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        canvas.drawText(if (captured) "DEMO CAPTURE" else "OM TETHER · DEMO", 42f, 72f, label)
        label.textSize = 28f
        canvas.drawText("Pinch to zoom · histogram · highlight warning", 44f, 112f, label)

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, if (captured) 95 else 82, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun demoControls(): List<ExposureControl> {
        fun control(code: Int, title: String, values: List<Pair<Long, String>>, currentIndex: Int) = ExposureControl(
            propertyCode = code,
            title = title,
            current = PtpScalar(Ptp.TYPE_UINT32, values[currentIndex].first),
            options = values.map { ExposureOption(PtpScalar(Ptp.TYPE_UINT32, it.first), it.second) },
            writable = true,
        )
        return listOf(
            control(Ptp.PROP_APERTURE, "絞り", listOf(280L to "f/2.8", 400L to "f/4.0", 560L to "f/5.6", 800L to "f/8.0"), 2),
            control(Ptp.PROP_SHUTTER_SPEED, "シャッター", listOf(1L to "1/60", 2L to "1/125", 3L to "1/250", 4L to "1/500"), 2),
            control(Ptp.PROP_ISO, "ISO", listOf(200L to "ISO 200", 400L to "ISO 400", 800L to "ISO 800", 1600L to "ISO 1600"), 1),
            control(Ptp.PROP_EXPOSURE_COMPENSATION, "露出補正", listOf(-1000L to "-1.0 EV", 0L to "0.0 EV", 1000L to "+1.0 EV"), 1),
            control(Ptp.PROP_WHITE_BALANCE, "WB", listOf(2L to "Auto", 4L to "Daylight", 0x8002L to "Cloudy"), 0),
        )
    }
}
