package com.wardriver.sync


sealed class HardwareOutcome {
    data class Synced(val blocks: Int, val problems: Int) : HardwareOutcome()
    data class Skipped(val reason: String) : HardwareOutcome()
    data class Failed(val reason: String) : HardwareOutcome()
}

data class CycleResult(
    val hardware: HardwareOutcome,

    val upload: UploadResult?,
) {

    fun summary(): String {
        val hw = when (val h = hardware) {
            is HardwareOutcome.Synced -> "ESP32: sincronizado" + if (h.problems > 0) " (${h.problems} bloco(s) sem confirmação)" else ""
            is HardwareOutcome.Skipped -> "ESP32: não sincronizado (${h.reason})"
            is HardwareOutcome.Failed -> "ESP32: falhou (${h.reason})"
        }
        val cloud = when {
            upload == null -> "nuvem: desligada"
            !upload.ok -> "nuvem: FALHOU (${upload.error}); tento de novo no próximo ciclo"
            upload.nothingToSend -> "nuvem: nada novo"
            else -> "nuvem: ${upload.sent} rede(s) enviada(s)" + if (upload.rejected > 0) ", ${upload.rejected} recusada(s)" else ""
        }
        return "$hw · $cloud"
    }
}







class CycleRunner(
    private val syncHardware: suspend () -> HardwareOutcome,
    private val upload: suspend () -> UploadResult?,
) {
    suspend fun run(): CycleResult {
        val hw = try {
            syncHardware()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            HardwareOutcome.Failed(e.javaClass.simpleName)
        }
        val up = try {
            upload()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            UploadResult(0, 0, 0, ok = false, error = "erro inesperado: ${e.javaClass.simpleName}")
        }
        return CycleResult(hw, up)
    }
}
