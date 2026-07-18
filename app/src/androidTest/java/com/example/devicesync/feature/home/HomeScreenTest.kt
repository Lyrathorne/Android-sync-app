package com.example.devicesync.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.devicesync.core.transfer.TransferHistoryEntry
import com.example.devicesync.ui.designsystem.DeviceSyncStatus
import com.example.devicesync.ui.theme.DeviceSyncTheme
import com.example.devicesync.R
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectedHome_showsComputerAndTransferHistory() {
        composeRule.setContent {
            DeviceSyncTheme {
                HomeScreen(
                    uiState = HomeUiState(
                        connection = HomeConnectionUi(DeviceSyncStatus.Connected, R.string.status_connected_title, R.string.status_connected_detail, "Work laptop"),
                        transfers = listOf(TransferHistoryEntry("1", "outgoing", "report.pdf", "completed")),
                    ),
                    backgroundIssue = null,
                    onComputersClick = {}, onSendFileClick = {}, onShareTextClick = {},
                    onClipboardClick = {}, onSettingsClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("home_connection_card").assertIsDisplayed()
        composeRule.onNodeWithText("Work laptop").assertIsDisplayed()
        composeRule.onNodeWithText("report.pdf").assertIsDisplayed()
    }

    @Test
    fun backgroundProblem_isOnlyShownWhenActionable() {
        composeRule.setContent {
            DeviceSyncTheme {
                HomeScreen(HomeUiState(), R.string.background_battery_optimized, {}, {}, {}, {}, {})
            }
        }
        composeRule.onNodeWithTag("home_background_issue").assertIsDisplayed()
    }

    @Test
    fun compactScreenAtDoubleFontScale_keepsAllQuickActionsVisible() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                DeviceSyncTheme {
                    HomeScreen(
                        HomeUiState(), null, {}, {}, {}, {}, {},
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("quick_send_file").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_send_text").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_clipboard").assertIsDisplayed()
    }
}
