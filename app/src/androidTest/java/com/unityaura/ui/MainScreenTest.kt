package com.unityaura.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.unityaura.MainActivity
import org.junit.Rule
import org.junit.Test

class MainScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun initialStateShowsZeroEventsAndNotAttempted() {
        composeTestRule.onNodeWithText("Events in DB: 0").assertExists()
        composeTestRule.onNodeWithText("Upload Status: Not attempted").assertExists()
    }

    @Test
    fun trackEventButtonIsDisabledWhenInputIsEmpty() {
        composeTestRule.onNodeWithText("Track Event").assertIsNotEnabled()
    }

    @Test
    fun typingEventNameEnablesTrackButton() {
        composeTestRule.onNodeWithText("Event Name").performTextInput("test_event")
        composeTestRule.onNodeWithText("Track Event").assertIsEnabled()
    }

    @Test
    fun trackEventClearsInputField() {
        composeTestRule.onNodeWithText("Event Name").performTextInput("test_event")
        composeTestRule.onNodeWithText("Track Event").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("test_event").assertDoesNotExist()
    }

    @Test
    fun shutdownDisablesAllButtons() {
        composeTestRule.onNodeWithText("Shutdown").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Track Event").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Manual Upload").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Shutdown").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Tracker has been shut down").assertExists()
    }

    @Test
    fun manualUploadButtonIsEnabled() {
        composeTestRule.onNodeWithText("Manual Upload").assertIsEnabled()
    }
}
