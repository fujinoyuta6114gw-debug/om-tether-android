#!/usr/bin/env python3
"""Dependency-free structural checks for the OM Tether source tree."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
APP_SOURCE = ROOT / "app" / "src" / "main"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    required_files = [
        ROOT / "settings.gradle.kts",
        ROOT / "app" / "build.gradle.kts",
        APP_SOURCE / "AndroidManifest.xml",
        APP_SOURCE / "res" / "xml" / "device_filter.xml",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "MainViewModel.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "SetupGuidePolicy.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "UsbCableAssessment.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "camera" / "OmUsbCameraController.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "history" / "CaptureHistory.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "history" / "PhotoMetadataExtractor.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "focus" / "FocusMaskAnalysis.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "focus" / "FocusViewportMath.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "focus" / "FaceRegionDetector.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "storage" / "CapturePathPolicy.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "ui" / "OmTetherApp.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "ui" / "FocusReviewDialog.kt",
        ROOT / "app" / "src" / "test" / "java" / "com" / "example" / "omtether" / "SetupGuidePolicyTest.kt",
        ROOT / "app" / "src" / "test" / "java" / "com" / "example" / "omtether" / "camera" / "CaptureSavePolicyTest.kt",
        ROOT / "app" / "src" / "test" / "java" / "com" / "example" / "omtether" / "camera" / "CameraObjectTrackerTest.kt",
        ROOT / "app" / "src" / "test" / "java" / "com" / "example" / "omtether" / "history" / "CameraStorageSlotTest.kt",
        ROOT / "app" / "src" / "test" / "java" / "com" / "example" / "omtether" / "history" / "PhotoMetadataFormatterTest.kt",
        ROOT / "app" / "src" / "test" / "java" / "com" / "example" / "omtether" / "focus" / "FocusMaskAnalysisTest.kt",
        ROOT / "app" / "src" / "test" / "java" / "com" / "example" / "omtether" / "focus" / "FocusViewportMathTest.kt",
        ROOT / "app" / "src" / "test" / "java" / "com" / "example" / "omtether" / "storage" / "CapturePathPolicyTest.kt",
    ]
    for path in required_files:
        require(path.is_file(), f"missing required file: {path.relative_to(ROOT)}")

    xml_files = sorted(APP_SOURCE.rglob("*.xml"))
    for path in xml_files:
        ET.parse(path)

    device_filter = (APP_SOURCE / "res" / "xml" / "device_filter.xml").read_text(encoding="utf-8")
    require('vendor-id="13218"' in device_filter, "OM-1 Mark II USB vendor ID is missing")
    require('product-id="310"' in device_filter, "OM-1 Mark II USB product ID is missing")

    kotlin_source = "\n".join(path.read_text(encoding="utf-8") for path in APP_SOURCE.rglob("*.kt"))
    camera_source = (
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "camera" / "OmUsbCameraController.kt"
    ).read_text(encoding="utf-8")
    view_model_source = (
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "MainViewModel.kt"
    ).read_text(encoding="utf-8")
    connect_source = camera_source.split("override suspend fun connect(): CameraSession", 1)[1].split(
        "override suspend fun startLiveView()", 1
    )[0]
    setup_guide_source = (
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "ui" / "SetupGuide.kt"
    ).read_text(encoding="utf-8")
    required_markers = {
        "live-view operation": "OMD_GET_LIVE_VIEW_IMAGE",
        "capture operation": "OMD_CAPTURE",
        "JPEG/ORF storage": "Pictures/OM Tether",
        "histogram": "histogram",
        "highlight overlay": "highlightOverlay",
        "new OM object event": "EVENT_OM_OBJECT_ADDED_NEW",
        "operation serialization": "cameraOperationMutex",
        "camera object retry": "retryObjectRead",
        "streamed per-object save": "storage.saveOne",
        "live-view frame throttling": "LIVE_FRAME_INTERVAL_MS",
        "quiet USB event timeout": "catch (_: TimeoutException)",
        "guided pre-tether setup": "撮影前ガイド",
        "neutral patch analysis": "neutralPatchFromPixels",
        "preview-only display calibration": "previewColorFilter",
        "persisted display profile": "KEY_SETUP_COMPLETE",
        "real-camera setup gate": "phase == ConnectionPhase.CONNECTED",
        "explicit WB confirmation": "setupWbConfirmed",
        "setup calibration rollback": "savedDisplayCalibration()",
        "non-cancelling latest-frame buffer": "frames.conflate().collect",
        "USB zero-length packet recovery": "MAX_ZERO_LENGTH_READS",
        "stale PTP response recovery": "! stale",
        "live-view stall recovery": "LIVE_VIEW_STALL_TIMEOUT_MS",
        "explicit RAW/Control connection guide": "0 RAW/Controlを選択済み・接続",
        "setup safe-area handling": "systemBarsPadding",
        "setup back handling": "BackHandler",
        "phone JPEG or RAW selection": "PhoneSaveFormat",
        "dual-card object selection": "CaptureSavePolicy",
        "all-storage object scan": "listOf(0xFFFF_FFFFL, 0L, 0L)",
        "preview JPEG quality warning": "解像度・画質に制限があります",
        "persisted phone save choice": "KEY_PHONE_SAVE_FORMAT",
        "full PTP reconnect": "USB/PTPセッションを作り直しています",
        "bounded stale-controller shutdown": "CONTROLLER_SHUTDOWN_TIMEOUT_MS",
        "camera exposure polling": "refreshExposureControls",
        "periodic exposure synchronization": "EXPOSURE_SYNC_INTERVAL_MS",
        "dedicated capture folder": "CapturePathPolicy.relativePath",
        "neutral gray theme": "0xFFB8BEC7",
        "PTP USB class device reset": "PTP_DEVICE_RESET_REQUEST = 0x66",
        "forced live-view restart": "Live-view property cleared for recovery",
        "camera-side shutter event flow": "externalCaptureEvents",
        "camera-side shutter import": "importExternalCapture",
        "dual-card new-object watcher": "Camera-side shutter safety polling started after first live-view frame",
        "bounded initial USB connection": "PTP_CONNECTION_TIMEOUT_MS",
        "bounded first live-view frame": "FIRST_FRAME_TIMEOUT_MS",
        "first decoded frame connection gate": "firstFrameReady.await()",
        "deferred PC and live-view setup": "PC/live-view, exposure, and card initialization deferred",
        "bounded card safety polling": "OBJECT_WATCH_TRANSFER_TIMEOUT_MS",
        "pre-baseline companion grouping": "coherentCaptureBatch",
        "retained failure diagnostics": "lastDiagnosticsText",
        "USB cable speed assessment": "assessUsbCable",
        "USB cable guide notice": "UsbCableNotice",
        "USB event timeout remains armed": "if (completed == null) continue",
        "cancellation-safe USB suspends": "runCatchingNonCancellation",
        "retryable exposure initialization": "will retry after live view starts",
        "build-synchronized diagnostics": "BuildConfig.VERSION_NAME",
        "multi-shot capture partitioning": "partitionCaptureBatches",
        "bounded queue drain": "MAX_HANDLES_PER_QUEUE_DRAIN",
        "capture queue UI": "CaptureQueueIndicator",
        "off-main capture transfer": "viewModelScope.launch(Dispatchers.IO)",
        "actual file EXIF extraction": "ExifInterface(ByteArrayInputStream(item.bytes))",
        "bounded capture history": "MAX_CAPTURE_HISTORY_ITEMS = 20",
        "capture history strip": "CaptureHistoryStrip",
        "camera card source tracking": "CameraStorageSlot.fromStorageId",
        "history save failure state": "SmartphoneSaveState.FAILED",
        "post-capture focus review": "FocusReviewDialog",
        "one image pixel display geometry": "oneToOneZoom",
        "remembered normalized focus position": "前回位置",
        "bounded focus mask analysis": "MAX_ANALYSIS_DIMENSION = 1_024",
        "offline face jump detection": "FaceRegionDetector.detect",
        "two-image focus comparison": "MAX_COMPARISON_IMAGES = 2",
        "bounded high-resolution focus history": "MAX_FOCUS_REVIEW_IMAGES = 2",
    }
    for label, marker in required_markers.items():
        require(marker in kotlin_source, f"missing implementation marker: {label}")

    prohibited_camera_markers = {
        "0X100B": "PTP object deletion opcode",
        "0X100F": "PTP storage formatting opcode",
        "DELETE_OBJECT": "camera object deletion constant",
        "FORMAT_STORE": "camera storage formatting constant",
    }
    for marker, label in prohibited_camera_markers.items():
        require(marker not in kotlin_source.upper(), f"prohibited {label} found: {marker}")

    require(
        "setOf(ConnectionPhase.CONNECTED, ConnectionPhase.DEMO)" not in setup_guide_source,
        "demo mode must not satisfy the real-camera setup gate",
    )
    require(
        "frames.collectLatest" not in kotlin_source,
        "live-view analysis must not be cancelled by every newer frame",
    )
    require(
        "GET_OBJECT_HANDLES" not in connect_source and "initializeObjectTracking" not in connect_source,
        "connect() must not scan card 1/2 before the initial live view can start",
    )
    require(
        "readExposureControls()" not in connect_source,
        "connect() must not read exposure descriptors before the initial live view can start",
    )
    history_source = (
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "history" / "PhotoMetadataExtractor.kt"
    ).read_text(encoding="utf-8")
    require(
        "ExposureControl" not in history_source and "exposureControls" not in history_source,
        "capture history must not substitute the camera's current exposure controls for file EXIF",
    )
    require(
        "initializePcMode(" not in connect_source,
        "connect() must not wait for PC-mode property setup before confirming the PTP session",
    )
    activate_source = view_model_source.split(
        "private suspend fun activateController", 1
    )[1].split("private suspend fun updateFrame", 1)[0]
    require(
        activate_source.index("phase = if (demo) ConnectionPhase.DEMO else ConnectionPhase.CONNECTED")
        < activate_source.index("firstFrameReady.await()"),
        "the setup guide must receive PTP-connected state before waiting for the first live-view frame",
    )

    print(f"PASS: {len(xml_files)} XML files parsed")
    print("PASS: OM-1 Mark II USB filter verified")
    print("PASS: selected feature markers found")
    print("PASS: no camera delete/format opcode is present")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, ET.ParseError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
