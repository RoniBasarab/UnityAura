package com.unityaura.ui

sealed interface MainScreenActions {
    data class TrackEvent(val name: String) : MainScreenActions
    data object UploadEvents : MainScreenActions
    data object Shutdown : MainScreenActions
    data class UpdateEventName(val name: String) : MainScreenActions
}
