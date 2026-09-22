package com.lef.pmaaobd.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {

    @Test
    fun singleAnswerEngineSpeed() {
        val r = ObdParser.parse("410C1AF8", listOf("010C"))
        assertEquals(1726.0, ObdParser.value("010C", r["010C"]!!)!!, 0.01)
    }

    @Test
    fun groupedMultiFrameAnswer() {
        val raw = "00A\n0:410C1AF80D3C\n1:057B11400000"
        val r = ObdParser.parse(raw, listOf("010C", "010D", "0105", "0111"))
        assertEquals(60.0, ObdParser.value("010D", r["010D"]!!)!!, 0.01)
        assertEquals(83.0, ObdParser.value("0105", r["0105"]!!)!!, 0.01)
        assertEquals(4, r.size)
    }

    @Test
    fun cleaningAndErrors() {
        assertEquals("410D3C", ObdParser.clean("SEARCHING...\r41 0D 3C\r", "010D1"))
        assertNull(ObdParser.clean("NO DATA\r", "010C"))
        assertNull(ObdParser.clean("UNABLE TO CONNECT", "0100"))
        assertNull(ObdParser.clean("?", "XYZ"))
    }

    @Test
    fun unrequestedPidIsIgnored() {
        val r = ObdParser.parse("410C1AF8", listOf("010D"))
        assertTrue(r.isEmpty())
    }

    @Test
    fun formulas() {
        assertEquals(-40.0, ObdParser.value("0105", intArrayOf(0))!!, 0.01)
        assertEquals(100.0, ObdParser.value("0104", intArrayOf(255))!!, 0.01)
        assertEquals(12.5, ObdParser.value("0142", intArrayOf(0x30, 0xD4))!!, 0.01)
    }
}
