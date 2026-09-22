package com.lef.pmaaobd.stats

import com.lef.pmaaobd.stats.transport.AdapterNames
import com.lef.pmaaobd.stats.transport.BleUart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterNamesTest {

    @Test
    fun commonAdaptersAreRecognised() {
        listOf(
            "OBDLink CX", "OBDLink MX+", "OBDII", "OBD2", "V-LINK", "Android-Vlink", "vLinker MC-Android",
            "Vgate iCar Pro", "IOS-Vlink", "KONNWEI", "Veepeak", "ELM327 v1.5", "OBDBLE",
        ).forEach { assertTrue(it, AdapterNames.looksLikeObd(it)) }
    }

    @Test
    fun otherDevicesAreIgnored() {
        listOf("JBL Flip 5", "Galaxy Buds", "Mi Band 7", null).forEach {
            assertFalse(AdapterNames.looksLikeObd(it))
        }
    }

    @Test
    fun knownBleLayoutsAreDistinct() {
        assertEquals(BleUart.known.size, BleUart.knownServices.size)
        assertTrue(BleUart.knownServices.none { it in BleUart.ignoredServices })
    }
}
