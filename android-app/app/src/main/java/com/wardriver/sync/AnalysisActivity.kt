package com.wardriver.sync

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wardriver.sync.databinding.ActivityAnalysisBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalysisBinding
    private val db by lazy { WardriverDatabase.getInstance(this) }

    private val defaultNamePattern = Regex(
        "(?i)^(TP-LINK|VIVO|CLARO|OI-|OI_|SKY|NET_|NET-|TENDA|D-LINK|DLINK|HUAWEI|MULTILASER|" +
            "INTELBRAS|ARRIS|WIFI[0-9]|GVT-|ZTE)[ _-]?[0-9A-F]*$"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.analysis_title)

        binding.textAnalysisReport.text = getString(R.string.analysis_title)

        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { db.networkDao().getAll() }
            binding.textAnalysisReport.text = buildReport(all)
        }
    }

    private fun buildReport(list: List<NetworkRecord>): String {
        if (list.isEmpty()) return "Nenhuma rede coletada ainda."

        val total = list.size
        val byEncryption = list.groupingBy { it.encryption }.eachCount()
        val hiddenCount = list.count { NetworkSearch.isUnnamed(it) }
        val hiddenOrigin = HiddenSiblings.analyze(list)
        val wpsCount = list.count { it.wpsActive }
        val weakCount = (byEncryption["OPEN"] ?: 0) + (byEncryption["WEP"] ?: 0)
        val defaultNameCount = list.count {
            it.ssid.isNotEmpty() && defaultNamePattern.containsMatchIn(it.ssid)
        }
        val topSsids = list.filter { it.ssid.isNotEmpty() }
            .groupingBy { it.ssid }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(5)
        val topChannels = list.groupingBy { it.channel }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(5)

        fun pct(n: Int) = if (total > 0) n * 100 / total else 0

        val sb = StringBuilder()
        sb.appendLine("Total de redes unicas: $total")
        sb.appendLine()
        sb.appendLine("Por criptografia:")
        for (enc in listOf("OPEN", "WEP", "WPA", "WPA2", "WPA3")) {
            val c = byEncryption[enc] ?: 0
            sb.appendLine("  $enc: $c (${pct(c)}%)")
        }
        sb.appendLine()
        sb.appendLine("SSID oculto: $hiddenCount (${pct(hiddenCount)}%)")
        if (hiddenCount > 0) {
            fun pctHidden(n: Int) = n * 100 / hiddenCount
            sb.appendLine("  com rede visivel da mesma origem: ${hiddenOrigin.withVisibleSibling} (${pctHidden(hiddenOrigin.withVisibleSibling)}% das ocultas)")
            sb.appendLine("  BSSID de interface virtual (bit local): ${hiddenOrigin.locallyAdministered} (${pctHidden(hiddenOrigin.locallyAdministered)}% das ocultas)")
            sb.appendLine("  Roteadores de operadora/mesh publicam interfaces extras (visitante, operadora,")
            sb.appendLine("  backhaul) sem divulgar o nome — contam como ocultas sem ninguem ter escondido nada.")
        }
        sb.appendLine("WPS ativo: $wpsCount (${pct(wpsCount)}%)")
        sb.appendLine()
        sb.appendLine("Risco — sem criptografia forte (OPEN+WEP): $weakCount (${pct(weakCount)}%)")
        sb.appendLine("Nome de fabrica nao alterado: $defaultNameCount (${pct(defaultNameCount)}%)")
        sb.appendLine()
        sb.appendLine("SSIDs mais frequentes:")
        if (topSsids.isEmpty()) {
            sb.appendLine("  (nenhum SSID visivel coletado)")
        } else {
            for ((ssid, count) in topSsids) sb.appendLine("  \"$ssid\": ${count}x")
        }
        sb.appendLine()
        sb.appendLine("Canais mais usados:")
        for ((channel, count) in topChannels) sb.appendLine("  Canal $channel: $count rede(s)")

        appendFirmwareSection(sb)
        return sb.toString()
    }

    private fun appendFirmwareSection(sb: StringBuilder) {
        val entries = FirmwareStatsLog.last(this, 400)
        sb.appendLine()
        sb.appendLine("Diagnostico do firmware (enviado pelo dispositivo):")
        if (entries.isEmpty()) {
            sb.appendLine("  nenhuma leitura ainda — conecte ao dispositivo com o firmware novo")
            return
        }
        val last = entries.last()

        var firstOfBoot = last
        for (e in entries.asReversed()) {
            val tE = e.values["t"]?.toLongOrNull() ?: break
            val tLast = firstOfBoot.values["t"]?.toLongOrNull() ?: break
            if (tE > tLast) break
            firstOfBoot = e
        }
        fun v(e: FirmwareStatsLog.Entry, k: String) = e.values[k]?.toLongOrNull() ?: 0L
        fun d(k: String) = v(last, k) - v(firstOfBoot, k)
        sb.appendLine("  ultima leitura: ${last.stamp} (uptime ${v(last, "t")} s)")
        sb.appendLine("  redes na sessao: ${v(last, "redes")} | na tabela: ${v(last, "tabela")}, sem nome: ${v(last, "sem_nome")}")
        sb.appendLine("  frames: beacon ${v(last, "beacon")}, probe response ${v(last, "probe")}")
        sb.appendLine("  SSID em branco: tamanho 0 = ${v(last, "tam0")}, so zeros = ${v(last, "zeros")}")
        sb.appendLine("  nomes revelados: por probe ${v(last, "rev_probe")}, por beacon ${v(last, "rev_beacon")}; preservados ${v(last, "preservado")}")
        if (firstOfBoot !== last) {
            sb.appendLine("  desde ${firstOfBoot.stamp}: +${d("beacon")} beacons, +${d("probe")} probe resp, +${d("rev_probe")} revelados por probe")
        }
        val fix = if (last.values["mod_fix"] == "1") "COM fix" else "sem fix"
        sb.appendLine("  GPS: fonte em uso = ${last.values["gps"] ?: "?"}; modulo $fix, " +
            "${v(last, "mod_sats")} sat. no fix, ${v(last, "mod_trk")} rastreados de ${v(last, "mod_view")} visiveis")
        if ("hops" in last.values) {
            sb.appendLine("  association requests (so p/ achar nomes; o MAC do cliente e descartado no dispositivo): " +
                "${v(last, "assoc")} vistos, ${v(last, "rev_assoc")} nomes descobertos")
            sb.appendLine("  channel hopping: ${v(last, "hops")} trocas de canal (${v(last, "hopfail")} recusadas)")
            val perChannel = last.values["ch"].orEmpty().split(',').mapNotNull { pair ->
                val (ch, n) = pair.split(':').let { it.getOrNull(0) to it.getOrNull(1) }
                if (ch != null && n != null) "c$ch=$n" else null
            }
            if (perChannel.isNotEmpty()) sb.appendLine("  quadros por canal: ${perChannel.joinToString("  ")}")
        }
        if ("oled_up" in last.values) {
            val up = if (last.values["oled_up"] == "1") "respondendo" else "SEM RESPOSTA"
            sb.appendLine("  OLED: $up; quadros ok ${v(last, "oled_ok")}, falhas de I2C ${v(last, "oled_fail")}, " +
                "recuperacoes de barramento ${v(last, "oled_rec")}, reinicios a frio ${v(last, "oled_reinit")}")
        }
        val fixes = entries.count { it.values["mod_fix"] == "1" }
        sb.appendLine("  modulo GPS com fix em $fixes de ${entries.size} leituras")
        sb.appendLine("  (arquivo completo: Android/data/com.wardriver.sync/files/${FirmwareStatsLog.FILE_NAME})")
    }
}
