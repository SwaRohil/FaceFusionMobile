package com.swapstudio.ai.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "download on Wi-Fi only" switch guards a ~1 GB transfer, so the decision table is pinned
 * exhaustively: four combinations of (connected, unmetered) times the switch being on and off.
 */
class DownloadPolicyTest {

    @Test
    fun `no network is offline regardless of the switch`() {
        assertEquals(DownloadPolicy.Verdict.Offline, DownloadPolicy.evaluate(wifiOnly = true, connected = false, unmetered = false))
        assertEquals(DownloadPolicy.Verdict.Offline, DownloadPolicy.evaluate(wifiOnly = false, connected = false, unmetered = false))
        assertEquals(DownloadPolicy.Verdict.Offline, DownloadPolicy.evaluate(wifiOnly = false, connected = false, unmetered = true))
    }

    @Test
    fun `metered network is blocked only when the switch is on`() {
        assertEquals(DownloadPolicy.Verdict.Metered, DownloadPolicy.evaluate(wifiOnly = true, connected = true, unmetered = false))
        assertEquals(DownloadPolicy.Verdict.Allowed, DownloadPolicy.evaluate(wifiOnly = false, connected = true, unmetered = false))
    }

    @Test
    fun `unmetered network is always allowed`() {
        assertEquals(DownloadPolicy.Verdict.Allowed, DownloadPolicy.evaluate(wifiOnly = true, connected = true, unmetered = true))
        assertEquals(DownloadPolicy.Verdict.Allowed, DownloadPolicy.evaluate(wifiOnly = false, connected = true, unmetered = true))
    }

    @Test
    fun `every refusal carries an explanation for the UI`() {
        assertNotNull(DownloadPolicy.Verdict.Metered.message)
        assertNotNull(DownloadPolicy.Verdict.Offline.message)
        assertNull(DownloadPolicy.Verdict.Allowed.message)
        assertTrue(DownloadPolicy.Verdict.Metered.message!!.contains("Wi-Fi"))
    }
}
