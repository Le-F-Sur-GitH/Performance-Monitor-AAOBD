package com.lef.pmaaobd.stats

/** Pure (Android-free) decoding of ELM327 mode 01 answers. Covered by unit tests. */
object ObdParser {

    /** Cleans a raw answer: upper case, no spaces, no echo or SEARCHING line. Null on error. */
    fun clean(raw: String?, cmd: String): String? {
        if (raw == null) return null
        val c = raw.uppercase()
            .split('\r', '\n')
            .map { it.replace(" ", "").trim() }
            .filter {
                it.isNotEmpty() && it != cmd.replace(" ", "") &&
                        !it.startsWith("SEARCHING") && !it.startsWith("BUSINIT")
            }
            .joinToString("\n")
        if (c.isEmpty() || c.contains("NODATA") || c.contains("UNABLETOCONNECT") ||
            c.contains("ERROR") || c.contains("STOPPED") || c == "?"
        ) return null
        return c
    }

    /**
     * Single (410C1AF8) or grouped (41 0C 1AF8 0D 3C...) answer,
     * including CAN multi-frame answers (length line "00E" then "0:...", "1:...").
     */
    fun parse(resp: String?, requested: List<String>): Map<String, IntArray> {
        if (resp == null) return emptyMap()
        var total = -1
        val hex = StringBuilder()
        for (l in resp.lines()) {
            when {
                l.length == 3 && total < 0 -> total = l.toIntOrNull(16) ?: -1
                l.length > 2 && l[1] == ':' -> hex.append(l.substring(2))
                else -> hex.append(l)
            }
        }
        var bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (total > 0 && bytes.size > total) bytes = bytes.take(total)

        val out = HashMap<String, IntArray>()
        val start = bytes.indexOf(0x41)
        if (start < 0) return out
        var i = start + 1
        while (i < bytes.size) {
            val id = "01%02X".format(bytes[i])
            if (id !in requested || out.containsKey(id)) {
                val next = bytes.subList(i, bytes.size).indexOf(0x41)
                if (next < 0) break
                i += next + 1
                continue
            }
            val len = ObdPids.length(id)
            if (i + 1 + len > bytes.size) break
            out[id] = bytes.subList(i + 1, i + 1 + len).toIntArray()
            i += 1 + len
        }
        return out
    }

    /** Physical value of a PID from its data bytes. */
    fun value(id: String, bytes: IntArray): Double? =
        try { ObdPids.byId[id]?.calc?.invoke(bytes) } catch (e: IndexOutOfBoundsException) { null }
}
