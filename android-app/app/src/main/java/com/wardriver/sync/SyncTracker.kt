package com.wardriver.sync












class SyncTracker(
    private val quietMs: Long = 3_000L,
    private val timeoutMs: Long = 120_000L,
) {
    sealed class Outcome {
        object Pending : Outcome()

        data class Done(val blocks: Int, val problems: Int) : Outcome()
        data class Failed(val reason: String) : Outcome()
        object TimedOut : Outcome()
    }

    private var active = false
    private var startedAt = 0L
    private var lastEventAt = 0L
    private var sessionSeen = false
    private var openBlocks = 0
    private var blocks = 0
    private var problems = 0
    private var failure: String? = null

    @Synchronized
    fun begin(nowMs: Long) {
        active = true
        startedAt = nowMs
        lastEventAt = nowMs
        sessionSeen = false
        openBlocks = 0
        blocks = 0
        problems = 0
        failure = null
    }

    @Synchronized fun onSession(nowMs: Long) { if (active) { sessionSeen = true; lastEventAt = nowMs } }

    @Synchronized fun onBlockBegin(nowMs: Long) { if (active) { openBlocks++; lastEventAt = nowMs } }


    @Synchronized
    fun onBlockEnd(nowMs: Long, confirmed: Boolean) {
        if (!active) return
        openBlocks = maxOf(0, openBlocks - 1)
        blocks++
        if (!confirmed) problems++
        lastEventAt = nowMs
    }

    @Synchronized
    fun onDeviceError(line: String) {
        if (active && failure == null) failure = "o dispositivo respondeu: ${line.take(120)}"
    }

    @Synchronized
    fun onDisconnected() {
        if (active && failure == null) failure = "a conexão com o dispositivo caiu durante a sincronização"
    }

    @Synchronized
    fun evaluate(nowMs: Long): Outcome {
        if (!active) return Outcome.Failed("sincronização não iniciada")
        failure?.let { active = false; return Outcome.Failed(it) }
        if (sessionSeen && openBlocks == 0 && nowMs - lastEventAt >= quietMs) {
            active = false
            return Outcome.Done(blocks, problems)
        }
        if (nowMs - startedAt >= timeoutMs) {
            active = false
            return Outcome.TimedOut
        }
        return Outcome.Pending
    }

    @Synchronized fun cancel() { active = false }
}
