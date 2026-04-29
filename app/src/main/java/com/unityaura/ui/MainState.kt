package com.unityaura.ui

import androidx.compose.runtime.Stable

@Stable
data class MainState(
    val eventCount: Int = 0,
    val isUploading: Boolean = false,
    val lastFlushTime: Long = 0L,
    val lastUploadResult: String = "Not attempted",
    val eventNameInput: String = "",
    val isShutdown: Boolean = false
)
