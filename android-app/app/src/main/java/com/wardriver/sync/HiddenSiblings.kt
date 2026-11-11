package com.wardriver.sync

object HiddenSiblings {

    data class Result(

        val unnamed: Int,

        val withVisibleSibling: Int,

        val locallyAdministered: Int,
    )

    fun analyze(all: List<NetworkRecord>): Result {
        val namedKeys = HashSet<String>()
        for (net in all) {
            if (NetworkSearch.isUnnamed(net)) continue
            val octets = octets(net.bssid) ?: continue
            namedKeys.addAll(keys(octets))
        }

        var unnamed = 0
        var withSibling = 0
        var localAdmin = 0
        for (net in all) {
            if (!NetworkSearch.isUnnamed(net)) continue
            unnamed++
            val octets = octets(net.bssid) ?: continue
            if ((octets[0] and 0x02) != 0) localAdmin++
            if (keys(octets).any { it in namedKeys }) withSibling++
        }
        return Result(unnamed, withSibling, localAdmin)
    }

    private fun octets(bssid: String): IntArray? {
        val parts = bssid.split(":")
        if (parts.size != 6) return null
        val out = IntArray(6)
        for (i in 0 until 6) out[i] = parts[i].toIntOrNull(16) ?: return null
        return out
    }

    private fun keys(o: IntArray): List<String> {
        val oui = "%02X%02X%02X".format(o[0] and 0xFD, o[1], o[2])
        val a = "%02X".format(o[3])
        val b = "%02X".format(o[4])
        val c = "%02X".format(o[5])
        return listOf("$oui|$a|$b|*", "$oui|$a|*|$c", "$oui|*|$b|$c")
    }
}
