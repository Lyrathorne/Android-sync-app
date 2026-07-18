package com.example.devicesync.core.notifications

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationForwardingPolicyTest {
    private val normal = NotificationPolicyInput(
        packageName = "org.example.chat",
        isSecret = false,
        isPrivate = false,
        isOngoing = false,
        isGroupSummary = false,
        category = Notification.CATEGORY_MESSAGE,
        hasProgress = false,
        hasText = true,
    )

    @Test
    fun ordinaryMessageIsForwarded() {
        assertTrue(NotificationForwardingPolicy.shouldForward(normal, includePrivate = false))
    }

    @Test
    fun secretPrivateSummaryProgressAndMediaNoiseAreFiltered() {
        assertFalse(NotificationForwardingPolicy.shouldForward(normal.copy(isSecret = true), false))
        assertFalse(NotificationForwardingPolicy.shouldForward(normal.copy(isPrivate = true), false))
        assertFalse(NotificationForwardingPolicy.shouldForward(normal.copy(isGroupSummary = true), false))
        assertFalse(NotificationForwardingPolicy.shouldForward(normal.copy(hasProgress = true), false))
        assertFalse(
            NotificationForwardingPolicy.shouldForward(
                normal.copy(category = Notification.CATEGORY_TRANSPORT),
                false,
            ),
        )
    }

    @Test
    fun privateNotificationRequiresExplicitOptIn() {
        assertTrue(NotificationForwardingPolicy.shouldForward(normal.copy(isPrivate = true), true))
    }

    @Test
    fun ongoingCallsRemainUsefulButForegroundServiceNoiseDoesNot() {
        assertTrue(
            NotificationForwardingPolicy.shouldForward(
                normal.copy(isOngoing = true, category = Notification.CATEGORY_CALL),
                false,
            ),
        )
        assertFalse(
            NotificationForwardingPolicy.shouldForward(
                normal.copy(isOngoing = true, category = Notification.CATEGORY_SERVICE),
                false,
            ),
        )
    }
}
