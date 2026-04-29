package com.unityaura.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unityaura.UnityAuraApplication
import com.unityaura.model.Event
import com.unityaura.tracker.IEventTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val eventTracker: IEventTracker = UnityAuraApplication.injector.eventTracker

    private val mutableState = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = mutableState.asStateFlow()

    init {
        observeTrackerState()
    }

    fun onAction(action: MainScreenActions) {
        when (action) {
            is MainScreenActions.TrackEvent -> {
                if (action.name.isNotBlank()) {
                    eventTracker.trackEvent(
                        Event(
                            name = action.name,
                            metadata = mapOf("source" to "ui", "screen" to "main")
                        )
                    )
                    mutableState.update { it.copy(eventNameInput = "") }
                }
            }
            is MainScreenActions.UploadEvents -> {
                viewModelScope.launch {
                    eventTracker.uploadFlushedEvents()
                }
            }
            is MainScreenActions.Shutdown -> {
                eventTracker.shutdown()
                mutableState.update { it.copy(isShutdown = true) }
            }
            is MainScreenActions.UpdateEventName -> {
                mutableState.update { it.copy(eventNameInput = action.name) }
            }
        }
    }

    private fun observeTrackerState() {
        viewModelScope.launch {
            eventTracker.eventCount.collect { count ->
                mutableState.update { it.copy(eventCount = count) }
            }
        }
        viewModelScope.launch {
            eventTracker.isUploading.collect { uploading ->
                mutableState.update { it.copy(isUploading = uploading) }
            }
        }
        viewModelScope.launch {
            eventTracker.lastFlushTime.collect { time ->
                mutableState.update { it.copy(lastFlushTime = time) }
            }
        }
        viewModelScope.launch {
            eventTracker.lastUploadResult.collect { result ->
                mutableState.update { it.copy(lastUploadResult = result) }
            }
        }
    }
}
