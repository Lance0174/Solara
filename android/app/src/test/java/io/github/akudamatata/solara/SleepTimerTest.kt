package io.github.akudamatata.solara

import io.github.akudamatata.solara.playback.SleepTimer
import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    private var elapsed = 1000L
    private val timer = SleepTimer { elapsed }

    @Test fun allRequestedPresetsStartFromTheCurrentTime() {
        assertEquals(listOf(10, 20, 30, 45, 60, 90), SleepTimer.PRESET_MINUTES)
        SleepTimer.PRESET_MINUTES.forEach { minutes ->
            elapsed += 1000
            timer.start(minutes)
            assertEquals(minutes * 60_000L, timer.remainingMillis())
        }
    }

    @Test fun customBoundariesAreAcceptedAndInvalidInputKeepsExistingTimer() {
        timer.start(1)
        assertEquals(60_000L, timer.remainingMillis())
        timer.start(1440)
        assertEquals(86_400_000L, timer.remainingMillis())
        listOf(-1, 0, 1441, Int.MAX_VALUE).forEach { minutes ->
            assertThrows(IllegalArgumentException::class.java) { timer.start(minutes) }
            assertEquals(86_400_000L, timer.remainingMillis())
        }
    }

    @Test fun expiryIsNotEarlyAndCanOnlyBeConsumedOnce() {
        timer.start(1)
        elapsed += 59_999
        assertEquals(1L, timer.remainingMillis())
        assertFalse(timer.expireIfDue())
        elapsed += 1
        assertTrue(timer.expireIfDue())
        assertEquals(0L, timer.remainingMillis())
        assertFalse(timer.expireIfDue())
        elapsed += 60_000
        assertFalse(timer.expireIfDue())
    }

    @Test fun selectingAgainReplacesTheOldDeadline() {
        timer.start(10)
        elapsed += 5 * 60_000
        timer.start(20)
        elapsed += 5 * 60_000
        assertFalse(timer.expireIfDue())
        assertEquals(15 * 60_000L, timer.remainingMillis())
        elapsed += 15 * 60_000
        assertTrue(timer.expireIfDue())
    }

    @Test fun cancelPreventsTheOldTimerFromPausingLaterPlayback() {
        timer.start(10)
        timer.cancel()
        elapsed += 30 * 60_000
        assertEquals(0L, timer.remainingMillis())
        assertFalse(timer.expireIfDue())
        timer.start(1)
        assertEquals(60_000L, timer.remainingMillis())
    }

    @Test fun lateChecksConsumeExpiryAndNewSessionsStartWithoutATimer() {
        assertFalse(timer.expireIfDue())
        timer.start(10)
        elapsed += 11 * 60_000
        assertEquals(0L, timer.remainingMillis())
        assertTrue(timer.expireIfDue())
        timer.start(30)
        val restarted = SleepTimer { elapsed }
        assertEquals(0L, restarted.remainingMillis())
        assertFalse(restarted.expireIfDue())
    }
}
