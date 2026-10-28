package one.woodbury.drone.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import one.woodbury.drone.video.VideoDecoderMetrics

class MirrorReceiverStateTest {

    @Test
    fun defaultState_hasZeroTransportMetrics() {
        val state = MirrorReceiverState()
        assertEquals(0L, state.udpPacketsReceived)
        assertEquals(0L, state.udpBytesReceived)
        assertEquals(0L, state.udpSequenceDrops)
        assertEquals(0L, state.dumlFramesReceived)
        assertEquals(0L, state.heartbeatsSent)
        assertNull(state.bridgeIp)
        assertFalse(state.isListening)
        assertFalse(state.isConnected)
        assertFalse(state.isReconnecting)
    }

    @Test
    fun updatedTransportMetrics_reflectedInCopy() {
        val initialState = MirrorReceiverState()
        val updatedState = initialState.copy(
            udpPacketsReceived = 1500L,
            udpBytesReceived = 2_000_000L,
            udpSequenceDrops = 3L,
            dumlFramesReceived = 50L,
        )
        assertEquals(1500L, updatedState.udpPacketsReceived)
        assertEquals(2_000_000L, updatedState.udpBytesReceived)
        assertEquals(3L, updatedState.udpSequenceDrops)
        assertEquals(50L, updatedState.dumlFramesReceived)
    }

    @Test
    fun connectionStatusProperties_reflectBridgeState() {
        val connectedState = MirrorReceiverState(
            bridgeIp = "192.168.1.50",
            videoDecoderMetrics = VideoDecoderMetrics(framesDecoded = 120L),
        )
        assertTrue(connectedState.isConnected)
        assertFalse(connectedState.isReconnecting)

        val reconnectingState = connectedState.copy(bridgeIp = null)
        assertFalse(reconnectingState.isConnected)
        assertTrue(reconnectingState.isReconnecting)

        val restoredState = reconnectingState.copy(bridgeIp = "192.168.1.50")
        assertTrue(restoredState.isConnected)
        assertFalse(restoredState.isReconnecting)
    }

    @Test
    fun initialSearchingState_isNotReconnectingBeforeFramesDecoded() {
        val initialSearchingState = MirrorReceiverState(
            bridgeIp = null,
            videoDecoderMetrics = VideoDecoderMetrics(framesDecoded = 0L),
        )
        assertFalse(initialSearchingState.isConnected)
        assertFalse(initialSearchingState.isReconnecting)
    }
}
