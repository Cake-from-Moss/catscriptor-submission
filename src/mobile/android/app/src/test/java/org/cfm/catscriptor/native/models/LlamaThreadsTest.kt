package org.cfm.catscriptor.native.models

import org.junit.Assert.assertEquals
import org.junit.Test

class LlamaThreadsTest {
    @Test
    fun `threads never exceed available cores`() {
        assertEquals(1, defaultLlamaThreads(1))
        assertEquals(2, defaultLlamaThreads(2))
        assertEquals(4, defaultLlamaThreads(4))
    }

    @Test
    fun `threads are capped on many-core devices`() {
        assertEquals(4, defaultLlamaThreads(8))
    }

    @Test
    fun `invalid core count falls back to one thread`() {
        assertEquals(1, defaultLlamaThreads(0))
    }
}
