package one.woodbury.drone.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorReceiverStateTest {

    @Test
    fun defaultState_hasZeroTransportMetrics() {
        val state = MirrorReceiverState()
        assertEquals(0L, state.udpPacketsReceived)
        assertEquals(0L, state.udpBytesReceived)
        assertEquals(0L, state.udpSequenceDrops)
        assertEquals(0L, state.dumlFramesReceived)
        assertEquals(0L, state.heartbeatsSent)
        assertNull(state.phoneBridgeIp)
        assertFalse(state.isListening)
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
}
