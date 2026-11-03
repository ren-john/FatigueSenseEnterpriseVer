package com.example.fatiguesense.enterprise

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.fatiguesense.enterprise.ml.EyeClosurePopup
import com.example.fatiguesense.enterprise.ui.components.EyeClosureAlertDialog
import com.example.fatiguesense.enterprise.ui.theme.FatigueSenseTheme
import org.junit.Rule
import org.junit.Test

class EyeClosurePopupInstrumentedTest {
    @get:Rule val ui = createComposeRule()

    @Test fun popupShowsReachedLimitsAndAcknowledgementDismissesIt() {
        ui.setContent {
            var alert by remember { mutableStateOf<EyeClosurePopup?>(EyeClosurePopup(1, 4, 45)) }
            FatigueSenseTheme {
                alert?.let { EyeClosureAlertDialog(it, onDismiss = { alert = null }) }
            }
        }
        ui.onNodeWithText("Repeated eye closures").assertIsDisplayed()
        ui.onNodeWithText("4 eye closures detected within 45 seconds.").assertIsDisplayed()
        ui.onNodeWithText("Acknowledge").performClick()
        ui.onNodeWithText("Repeated eye closures").assertDoesNotExist()
    }
}
