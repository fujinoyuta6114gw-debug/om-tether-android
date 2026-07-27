package com.example.omtether

enum class UsbCableGrade {
    RECOMMENDED,
    LIMITED,
    UNKNOWN,
}

data class UsbCableAssessment(
    val grade: UsbCableGrade,
    val title: String,
    val detail: String,
    val observedMaxPacketSize: Int? = null,
)

/**
 * Android does not expose the negotiated cable rating directly. Bulk endpoint
 * packet size is therefore used as a conservative indication of the active USB
 * path: 1024-byte bulk endpoints are typical of USB 3.x, while 512-byte
 * endpoints indicate a USB 2.0-class path on this camera.
 */
fun assessUsbCable(maxPacketSizes: List<Int>): UsbCableAssessment {
    val observed = maxPacketSizes.filter { it > 0 }.maxOrNull()
    return when {
        observed == null -> UsbCableAssessment(
            grade = UsbCableGrade.UNKNOWN,
            title = "接続速度を判定できません",
            detail = "USB-Cデータケーブル（USB 3.x相当を推奨）を使用してください。ケーブル単体の表記はAndroidから確認できません。",
        )
        observed >= 1024 -> UsbCableAssessment(
            grade = UsbCableGrade.RECOMMENDED,
            title = "USB 3.x相当の接続を検出",
            detail = "カメラ側の高速バルク転送エンドポイントを検出しました。USB 3.x対応データケーブルの経路と考えられます。",
            observedMaxPacketSize = observed,
        )
        observed >= 512 -> UsbCableAssessment(
            grade = UsbCableGrade.LIMITED,
            title = "USB 2.0相当の接続を検出",
            detail = "接続はできますが、ケーブル・変換アダプター・端末側ポートのいずれかがUSB 2.0相当の可能性があります。USB 3.x対応データケーブルでの直結を推奨します。",
            observedMaxPacketSize = observed,
        )
        else -> UsbCableAssessment(
            grade = UsbCableGrade.LIMITED,
            title = "低速USB接続を検出",
            detail = "接続速度が低い可能性があります。USB 3.x対応データケーブルで端末とカメラを直結してください。",
            observedMaxPacketSize = observed,
        )
    }
}
