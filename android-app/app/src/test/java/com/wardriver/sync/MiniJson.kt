package com.wardriver.sync


object MiniJson {
    fun parse(text: String): Any? {
        val p = P(text)
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "lixo depois do JSON na posicao ${p.i}" }
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "fim inesperado" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("caractere inesperado '$c' em $i")
            }
        }
        fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)) { "literal invalido em $i" }; i += word.length; return v }
        fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "string sem fim" }
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> error("escape invalido \\$e")
                        }
                    }
                    c.code < 0x20 -> error("caractere de controle sem escape (${c.code}) em ${i - 1}")
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']') { "esperava ] em $i" }; i++; return out
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = str(); ws()
                require(s[i++] == ':') { "esperava : em ${i - 1}" }
                out[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}') { "esperava } em $i" }; i++; return out
            }
        }
    }
}
