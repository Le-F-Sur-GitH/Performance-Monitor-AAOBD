package com.lef.pmaaobd.stats.transport

/** Recognises common OBD adapters by their Bluetooth name. */
object AdapterNames {
    /** Upper case fragments, compared without spaces or dashes. */
    private val HINTS = listOf(
        "OBD", "ELM", "VLINK", "VGATE", "ICAR", "KONNWEI", "VEEPEAK", "SCAN", "CARLY", "CARISTA",
    )

    fun looksLikeObd(name: String?): Boolean {
        val n = name?.uppercase()?.replace(" ", "")?.replace("-", "") ?: return false
        return HINTS.any { n.contains(it) }
    }
}
