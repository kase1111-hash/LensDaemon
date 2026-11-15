package com.lensdaemon.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [countsAsInFocus]: what the director's take scores count as
 * in focus, from the focus mode in use and the camera's autofocus state.
 */
class CountsAsInFocusTest {

    @Test
    fun `continuous autofocus counts once the camera reports it has focused`() {
        for (mode in listOf(FocusMode.CONTINUOUS_VIDEO, FocusMode.CONTINUOUS_PICTURE)) {
            assertTrue(countsAsInFocus(mode, FocusState.FOCUSED))
            assertFalse(countsAsInFocus(mode, FocusState.SCANNING))
            assertFalse(countsAsInFocus(mode, FocusState.FAILED))
            assertFalse(countsAsInFocus(mode, FocusState.INACTIVE))
        }
    }

    @Test
    fun `focus that autofocus does not move counts whatever the camera reports`() {
        // A fixed-focus lens or a set distance (MANUAL), a held focus (AUTO without a trigger)
        for (mode in listOf(FocusMode.MANUAL, FocusMode.AUTO, FocusMode.MACRO)) {
            for (state in FocusState.entries) {
                assertTrue("$mode with $state", countsAsInFocus(mode, state))
            }
        }
    }
}
