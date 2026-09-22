package com.lef.pmaaobd.stats

/**
 * Standard OBD-II PIDs (mode 01) plus virtual PIDs computed by the app.
 * [id] is the command sent to the adapter (e.g. "010C").
 * Names here are the English defaults; the UI localises them (see ObdPidLabels).
 */
data class ObdPid(
    val id: String,
    val name: String,
    val shortName: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val calc: (IntArray) -> Double,
) {
    /** Legacy wire format kept for the settings screens: "name,shortName,unit,max,min". */
    val info: String get() = info(name, shortName)

    fun info(localName: String, localShort: String): String =
        "${localName.replace(',', ' ')},${localShort.replace(',', ' ')},$unit,$max,$min"
}

object ObdPids {
    const val BOOST = "BOOST"
    const val ADAPTER_VOLTAGE = "ATRV"

    /** Prefix used in the saved dashboards ("obd_010C"). */
    const val QUERY_PREFIX = "obd_"

    private fun a(b: IntArray) = b[0].toDouble()
    private fun ab(b: IntArray) = (b[0] * 256 + b[1]).toDouble()

    val all: List<ObdPid> = listOf(
        ObdPid("0104", "Engine load", "Load", "%", 0.0, 100.0) { a(it) * 100 / 255 },
        ObdPid("0105", "Coolant temperature", "Coolant", "°C", -40.0, 215.0) { a(it) - 40 },
        ObdPid("0106", "Short term fuel trim B1", "STFT", "%", -100.0, 100.0) { (a(it) - 128) * 100 / 128 },
        ObdPid("0107", "Long term fuel trim B1", "LTFT", "%", -100.0, 100.0) { (a(it) - 128) * 100 / 128 },
        ObdPid("010B", "Intake manifold pressure", "MAP", "kPa", 0.0, 255.0) { a(it) },
        ObdPid("010C", "Engine speed", "RPM", "rpm", 0.0, 8000.0) { ab(it) / 4 },
        ObdPid("010D", "Vehicle speed", "Speed", "km/h", 0.0, 255.0) { a(it) },
        ObdPid("010E", "Timing advance", "Timing", "°", -64.0, 64.0) { a(it) / 2 - 64 },
        ObdPid("010F", "Intake air temperature", "IAT", "°C", -40.0, 215.0) { a(it) - 40 },
        ObdPid("0110", "Mass air flow", "MAF", "g/s", 0.0, 655.0) { ab(it) / 100 },
        ObdPid("0111", "Throttle position", "Throttle", "%", 0.0, 100.0) { a(it) * 100 / 255 },
        ObdPid("011F", "Run time since engine start", "Run time", "s", 0.0, 65535.0) { ab(it) },
        ObdPid("0121", "Distance with MIL on", "MIL dist", "km", 0.0, 65535.0) { ab(it) },
        ObdPid("012F", "Fuel level", "Fuel", "%", 0.0, 100.0) { a(it) * 100 / 255 },
        ObdPid("0133", "Barometric pressure", "Baro", "kPa", 0.0, 255.0) { a(it) },
        ObdPid("0142", "Control module voltage", "ECU V", "V", 0.0, 20.0) { ab(it) / 1000 },
        ObdPid("0143", "Absolute load", "Abs load", "%", 0.0, 100.0) { ab(it) * 100 / 255 },
        ObdPid("0144", "Commanded lambda", "Lambda", "", 0.0, 2.0) { ab(it) * 2 / 65536 },
        ObdPid("0145", "Relative throttle position", "Rel thr", "%", 0.0, 100.0) { a(it) * 100 / 255 },
        ObdPid("0146", "Ambient air temperature", "Ambient", "°C", -40.0, 215.0) { a(it) - 40 },
        ObdPid("0149", "Accelerator pedal position D", "Pedal", "%", 0.0, 100.0) { a(it) * 100 / 255 },
        ObdPid("015C", "Engine oil temperature", "Oil", "°C", -40.0, 210.0) { a(it) - 40 },
        ObdPid("015E", "Engine fuel rate", "Fuel rate", "L/h", 0.0, 50.0) { ab(it) / 20 },
        // Virtual PIDs (never sent as is, computed in ElmAdapter)
        ObdPid(BOOST, "Boost pressure (MAP - Baro)", "Boost", "bar", -1.0, 2.5) { 0.0 },
        ObdPid(ADAPTER_VOLTAGE, "Battery voltage (adapter)", "Battery", "V", 0.0, 16.0) { 0.0 },
    )

    val byId: Map<String, ObdPid> = all.associateBy { it.id }

    /** Data bytes returned per PID (default 1). */
    private val twoBytes = setOf("010C", "0110", "011F", "0121", "0142", "0143", "0144", "015E")
    fun length(id: String) = if (id in twoBytes) 2 else 1

    /** PIDs polled continuously. Others every 2 s, barometric pressure every 60 s. */
    private val fast = setOf("010C", "010D", "010B", "0110", "0111", "0145", "0149", "0104", "010E")
    fun intervalMs(id: String): Long = when (id) {
        in fast -> 0L
        "0133" -> 60_000L
        else -> 2_000L
    }

    /** PIDs actually requested from the car to produce a displayed value. */
    fun sources(id: String): List<String> = when (id) {
        BOOST -> listOf("010B", "0133")
        else -> listOf(id)
    }

    private val legacyId = Regex("^([0-9A-Fa-f]{1,2}),0$")

    /**
     * Converts identifiers saved by 1.x builds (Torque style "0c,0") to adapter commands ("010C").
     * Returns [id] unchanged when it is already in the current format.
     */
    fun normalize(id: String): String {
        val m = legacyId.matchEntire(id) ?: return id
        return "01" + m.groupValues[1].uppercase().padStart(2, '0')
    }

    /** Same as [normalize] for a saved dashboard value ("obd_0c,0" -> "obd_010C"). */
    fun normalizeQuery(query: String): String =
        if (query.startsWith(QUERY_PREFIX)) QUERY_PREFIX + normalize(query.removePrefix(QUERY_PREFIX)) else query

    fun isLegacyQuery(query: String): Boolean = normalizeQuery(query) != query
}
