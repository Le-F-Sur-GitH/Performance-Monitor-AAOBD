package com.lef.pmaaobd.stats

import android.content.Context

/** Localised names for [ObdPids]. Falls back to the English defaults of the table. */
object ObdPidLabels {
    private val labels: Map<String, Pair<Int, Int>> = mapOf(
        "0104" to (R.string.pid_0104 to R.string.pid_0104_short),
        "0105" to (R.string.pid_0105 to R.string.pid_0105_short),
        "0106" to (R.string.pid_0106 to R.string.pid_0106_short),
        "0107" to (R.string.pid_0107 to R.string.pid_0107_short),
        "010B" to (R.string.pid_010b to R.string.pid_010b_short),
        "010C" to (R.string.pid_010c to R.string.pid_010c_short),
        "010D" to (R.string.pid_010d to R.string.pid_010d_short),
        "010E" to (R.string.pid_010e to R.string.pid_010e_short),
        "010F" to (R.string.pid_010f to R.string.pid_010f_short),
        "0110" to (R.string.pid_0110 to R.string.pid_0110_short),
        "0111" to (R.string.pid_0111 to R.string.pid_0111_short),
        "011F" to (R.string.pid_011f to R.string.pid_011f_short),
        "0121" to (R.string.pid_0121 to R.string.pid_0121_short),
        "012F" to (R.string.pid_012f to R.string.pid_012f_short),
        "0133" to (R.string.pid_0133 to R.string.pid_0133_short),
        "0142" to (R.string.pid_0142 to R.string.pid_0142_short),
        "0143" to (R.string.pid_0143 to R.string.pid_0143_short),
        "0144" to (R.string.pid_0144 to R.string.pid_0144_short),
        "0145" to (R.string.pid_0145 to R.string.pid_0145_short),
        "0146" to (R.string.pid_0146 to R.string.pid_0146_short),
        "0149" to (R.string.pid_0149 to R.string.pid_0149_short),
        "015C" to (R.string.pid_015c to R.string.pid_015c_short),
        "015E" to (R.string.pid_015e to R.string.pid_015e_short),
        ObdPids.BOOST to (R.string.pid_boost to R.string.pid_boost_short),
        ObdPids.ADAPTER_VOLTAGE to (R.string.pid_atrv to R.string.pid_atrv_short),
    )

    fun info(context: Context, id: String): String {
        val pid = ObdPids.byId[id] ?: return "$id,$id,,0,0"
        val res = labels[id] ?: return pid.info
        return pid.info(context.getString(res.first), context.getString(res.second))
    }
}
