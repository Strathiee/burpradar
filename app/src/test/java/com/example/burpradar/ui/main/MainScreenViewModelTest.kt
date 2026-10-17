package com.example.burpradar.ui.main

import com.example.burpradar.db.BurpEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class BurpRadarUnitTest {
    @Test
    fun testBurpEventCreation() {
        val event = BurpEvent(
            id = 1,
            timestamp = 1000L,
            videoPath = "/path/to/video.mp4",
            durationMs = 5000L,
            confidence = 0.85f
        )
        assertEquals(1, event.id)
        assertEquals(1000L, event.timestamp)
        assertEquals("/path/to/video.mp4", event.videoPath)
        assertEquals(5000L, event.durationMs)
        assertEquals(0.85f, event.confidence)
    }
}
