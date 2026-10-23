package one.woodbury.drone.bridge.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpBridgeSenderMetricsTest {

    @Test
    fun defaultMetrics_isAcceptingSubscribersByDefault() {
        val metrics = UdpBridgeSenderMetrics()
        assertTrue(metrics.isAcceptingSubscribers)
        assertTrue(metrics.activeSubscribers.isEmpty())
        assertEquals("Stopped", metrics.socketStatus)
    }

    @Test
    fun customMetrics_retainsAcceptingSubscribersState() {
        val paused = UdpBridgeSenderMetrics(isAcceptingSubscribers = false)
        assertFalse(paused.isAcceptingSubscribers)
    }
}
