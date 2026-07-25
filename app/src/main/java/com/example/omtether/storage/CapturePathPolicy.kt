package com.example.omtether.storage

object CapturePathPolicy {
    const val ROOT_DIRECTORY = "Pictures/OM Tether"

    fun relativePath(date: String): String {
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            "Capture folder date must use yyyy-MM-dd"
        }
        return "$ROOT_DIRECTORY/$date/"
    }

    const val DISPLAY_PATH = "$ROOT_DIRECTORY/日付別フォルダ"
}
