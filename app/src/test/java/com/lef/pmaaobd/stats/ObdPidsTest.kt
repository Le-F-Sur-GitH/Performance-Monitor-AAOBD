package com.lef.pmaaobd.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPidsTest {

    @Test
    fun legacyIdsAreConverted() {
        assertEquals("010C", ObdPids.normalize("0c,0"))
        assertEquals("010D", ObdPids.normalize("0d,0"))
        assertEquals("0111", ObdPids.normalize("11,0"))
        assertEquals("0105", ObdPids.normalize("5,0"))
    }

    @Test
    fun currentIdsAreUntouched() {
        assertEquals("010C", ObdPids.normalize("010C"))
        assertEquals(ObdPids.BOOST, ObdPids.normalize(ObdPids.BOOST))
        assertEquals(ObdPids.ADAPTER_VOLTAGE, ObdPids.normalize(ObdPids.ADAPTER_VOLTAGE))
    }

    @Test
    fun savedQueriesAreConverted() {
        assertEquals("obd_010C", ObdPids.normalizeQuery("obd_0c,0"))
        assertEquals("obd_010C", ObdPids.normalizeQuery("obd_010C"))
        assertEquals("", ObdPids.normalizeQuery(""))
        assertTrue(ObdPids.isLegacyQuery("obd_11,0"))
        assertFalse(ObdPids.isLegacyQuery("obd_0111"))
    }

    @Test
    fun everyPidHasCoherentBounds() {
        ObdPids.all.forEach { assertTrue(it.id, it.max > it.min) }
    }

    @Test
    fun infoFormatHasFiveFields() {
        ObdPids.all.forEach { assertEquals(it.id, 5, it.info.split(",").size) }
        assertEquals(5, ObdPids.byId.getValue("010C").info("Régime, moteur", "RPM").split(",").size)
    }
}
