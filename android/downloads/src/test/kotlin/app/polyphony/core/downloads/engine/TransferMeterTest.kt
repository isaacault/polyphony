package app.polyphony.core.downloads.engine

import app.polyphony.core.domain.repository.TransferRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferMeterTest {
    private var now = 0L
    private val meter = TransferMeter { now }

    @Test fun `nothing until a second has been measured`() {
        assertNull(meter.sample(0f))
        now = 500; meter.add(500_000)
        assertNull(meter.sample(0.05f))
    }

    @Test fun `speed and time left from bytes and progress`() {
        meter.sample(0f)
        now = 2_000; meter.add(4_000_000)
        // 2 MB/s; a fifth done in 2 s, so 8 s to go.
        assertEquals(TransferRate(2_000_000, 8), meter.sample(0.2f))
    }

    @Test fun `smoothed, so one slow second doesn't swing it`() {
        meter.sample(0f)
        now = 1_000; meter.add(2_000_000); meter.sample(0.1f)
        now = 2_000; meter.add(0)
        val rate = meter.sample(0.1f)!!
        assertEquals(1_400_000, rate.bytesPerSecond)
    }

    @Test fun `no time left while progress stands still`() {
        meter.sample(0.5f)
        now = 1_000; meter.add(1_000)
        assertNull(meter.sample(0.5f)!!.secondsLeft)
    }

    @Test fun `described for the notification`() {
        assertEquals("1.5 MB/s · 3 min left", TransferMeter.describe(TransferRate(1_500_000, 170)))
        assertEquals("800 KB/s · under a minute left", TransferMeter.describe(TransferRate(800_000, 40)))
        assertEquals("1 h 5 min left", TransferMeter.describe(TransferRate(2_000_000, 3_900)).substringAfter(" · "))
        assertEquals("2.0 MB/s", TransferMeter.describe(TransferRate(2_000_000, null)))
    }
}
