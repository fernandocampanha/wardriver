package com.wardriver.sync

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale











object FirmwareStatsLog {

    const val FILE_NAME = "firmware_stats.log"
    private const val MAX_BYTES = 512 * 1024L
    private val lock = Any()
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }


    fun append(context: Context, line: String, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            try {
                val f = file(context)
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, "$FILE_NAME.1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText("${STAMP.format(Date(nowMs))} $line\n", Charsets.UTF_8)
            } catch (e: Exception) {

                android.util.Log.w("WardriverSync", "falha ao gravar firmware_stats.log: ${e.message}")
            }
        }
    }


    fun last(context: Context, count: Int): List<Entry> = synchronized(lock) {
        try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            f.readLines(Charsets.UTF_8).takeLast(count).mapNotNull { parseLine(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class Entry(val stamp: String, val values: Map<String, String>)


    fun parseLine(line: String): Entry? {
        val m = Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) STATS (.*)$").find(line.trim()) ?: return null
        val values = LinkedHashMap<String, String>()
        for (token in m.groupValues[2].split(' ')) {
            val eq = token.indexOf('=')
            if (eq > 0) values[token.substring(0, eq)] = token.substring(eq + 1)
        }
        return Entry(m.groupValues[1], values)
    }
}
