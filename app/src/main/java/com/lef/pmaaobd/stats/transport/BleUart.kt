package com.lef.pmaaobd.stats.transport

import java.util.UUID

/** Serial-over-BLE layouts used by common OBD adapters. */
object BleUart {
    data class Layout(val service: UUID, val notify: UUID, val write: UUID)

    private fun uuid16(v: String): UUID = UUID.fromString("0000$v-0000-1000-8000-00805f9b34fb")

    val known: List<Layout> = listOf(
        // OBDLink CX, Vgate iCar Pro BLE, Veepeak OBDCheck BLE, many ELM327 BLE clones
        Layout(uuid16("fff0"), uuid16("fff1"), uuid16("fff2")),
        // HM-10 based clones (Konnwei, generic "OBDII" BLE)
        Layout(uuid16("ffe0"), uuid16("ffe1"), uuid16("ffe1")),
        // Vgate vLinker BLE family
        Layout(uuid16("18f0"), uuid16("2af0"), uuid16("2af1")),
        // Nordic UART service
        Layout(
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
        ),
    )

    val knownServices: Set<UUID> = known.map { it.service }.toSet()

    /** Standard GATT services that never carry the serial link. */
    val ignoredServices: Set<UUID> = setOf("1800", "1801", "180a", "180f", "1805").map { uuid16(it) }.toSet()

    val CCCD: UUID = uuid16("2902")
}
