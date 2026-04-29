package com.unityaura.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    state: MainState,
    onAction: (MainScreenActions) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ConcurrentEventTracker",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Events in DB: ${state.eventCount}")
                Text(
                    "Upload Status: ${
                        if (state.isUploading) "Uploading..." else state.lastUploadResult
                    }"
                )
                if (state.lastFlushTime > 0) {
                    val formatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(Date(state.lastFlushTime))
                    Text("Last Flush: $formatted")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = state.eventNameInput,
            onValueChange = { onAction(MainScreenActions.UpdateEventName(it)) },
            label = { Text("Event Name") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isShutdown
        )

        Button(
            onClick = { onAction(MainScreenActions.TrackEvent(state.eventNameInput)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.eventNameInput.isNotBlank() && !state.isShutdown
        ) {
            Text("Track Event")
        }

        Button(
            onClick = { onAction(MainScreenActions.UploadEvents) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isUploading && !state.isShutdown
        ) {
            Text("Manual Upload")
        }

        Button(
            onClick = { onAction(MainScreenActions.Shutdown) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isShutdown,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Shutdown")
        }

        if (state.isShutdown) {
            Text(
                text = "Tracker has been shut down",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
