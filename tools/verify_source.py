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
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "camera" / "OmUsbCameraController.kt",
        APP_SOURCE / "java" / "com" / "example" / "omtether" / "ui" / "OmTetherApp.kt",
        ROOT / "app" / "src" / "test" / "java" / "com" / "example" / "omtether" / "SetupGuidePolicyTest.kt",
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
