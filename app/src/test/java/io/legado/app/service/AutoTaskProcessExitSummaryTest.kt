package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTaskProcessExitSummaryTest {

    @Test
    fun formatsStartMetadataAndSanitizesDeviceFields() {
        val summary = AutoTaskProcessExitSummary.formatStart(
            sdk = 36,
            manufacturer = "vivo\nV2507A",
            model = "x".repeat(100),
            version = "3.0",
            pid = 31706,
        )

        assertTrue(summary.contains("sdk=36"))
        assertTrue(summary.contains("manufacturer=vivo V2507A"))
        assertTrue(summary.contains("pid=31706"))
        assertEquals(80, summary.substringAfter("model=").substringBefore(" version=").length)
    }

    @Test
    fun formatsExitReasonAndRuntimeStatsOnly() {
        val summary = AutoTaskProcessExitSummary.formatExit(
            timestamp = 1_798_000_000_000L,
            pid = 24691,
            reason = 3,
            reasonName = "low_memory",
            status = 0,
            importance = 100,
            pssKb = 123_456L,
            rssKb = 234_567L,
            sinceExitMs = 900_000L,
        )

        assertEquals(
            "timestamp=1798000000000 pid=24691 reason=3 reasonName=low_memory " +
                "status=0 importance=100 pssKb=123456 rssKb=234567 sinceExitMs=900000",
            summary,
        )
    }
}
