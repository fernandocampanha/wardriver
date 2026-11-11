package com.wardriver.sync

import java.text.Normalizer
import java.util.Locale

object NetworkSearch {

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(text: String): String =
        DIACRITICS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
            .lowercase(Locale.ROOT)

    fun isUnnamed(net: NetworkRecord): Boolean = net.ssidHidden || net.ssid.isEmpty()

    class Query private constructor(private val tokens: List<String>) {
        val isEmpty: Boolean get() = tokens.isEmpty()

        fun matches(net: NetworkRecord): Boolean {
            if (tokens.isEmpty()) return true
            val bssid = net.bssid.lowercase(Locale.ROOT)
            val haystack = buildString {
                append(normalize(net.ssid))
                append(' ')
                append(bssid)
                append(' ')
                append(bssid.replace(":", ""))
                if (isUnnamed(net)) append(" oculta oculto hidden")
            }
            return tokens.all { haystack.contains(it) }
        }

        companion object {
            fun parse(raw: String): Query {
                val tokens = normalize(raw.trim()).split(WHITESPACE).filter { it.isNotEmpty() }
                return Query(tokens)
            }
        }
    }
}
