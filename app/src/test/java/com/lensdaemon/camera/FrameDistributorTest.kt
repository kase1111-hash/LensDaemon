package com.lensdaemon.camera

import com.lensdaemon.encoder.EncodedFrame
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [FrameDistributor].
 */
class FrameDistributorTest {

    private val frame = EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x65), 0L, 1)

    @Test
    fun `an output registered twice still gets each frame once`() {
        val distributor = FrameDistributor<EncodedFrame>()
        var received = 0
        val listener: (EncodedFrame) -> Unit = { received++ }

        distributor.addListener(listener)
        distributor.addListener(listener)
        distributor.dispatch(frame)

        assertEquals(1, distributor.listenerCount())
        assertEquals(1, received)
    }

    @Test
    fun `one failing listener does not starve the others`() {
        val distributor = FrameDistributor<EncodedFrame>()
        var received = 0
        distributor.addListener { throw IllegalStateException("boom") }
        distributor.addListener { received++ }

        distributor.dispatch(frame)

        assertEquals(1, received)
    }
}
