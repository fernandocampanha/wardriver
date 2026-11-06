package com.wardriver.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger


data class NameChange(val bssid: String, val ssid: String, val previousSsid: String?) {

    val wasHidden: Boolean get() = previousSsid == null
}


data class ApplyResult(val nameChanges: List<NameChange>, val added: Int, val updated: Int) {
    val namesRevealed: Int get() = nameChanges.count { it.wasHidden }

    companion object { val EMPTY = ApplyResult(emptyList(), 0, 0) }
}















class NetworkStore(private val dao: NetworkDao) {

    private val map = ConcurrentHashMap<String, NetworkRecord>()
    private val mutex = Mutex()
    private val revealed = AtomicInteger(0)

    val values: Collection<NetworkRecord> get() = map.values
    val size: Int get() = map.size


    val namesRevealed: Int get() = revealed.get()


    suspend fun load() {
        mutex.withLock {
            for (record in dao.getAll()) map.putIfAbsent(record.bssid, record)
        }
    }


    suspend fun applyCaptures(records: Collection<NetworkRecord>): ApplyResult = apply(records, live = true)


    suspend fun applyImport(records: Collection<NetworkRecord>): ApplyResult = apply(records, live = false)

    private suspend fun apply(records: Collection<NetworkRecord>, live: Boolean): ApplyResult {
        if (records.isEmpty()) return ApplyResult.EMPTY
        return mutex.withLock {
            val pending = LinkedHashMap<String, NetworkRecord>()
            val changes = ArrayList<NameChange>()
            var added = 0
            var updated = 0

            for (incoming in records) {
                val before = pending[incoming.bssid] ?: map[incoming.bssid]
                val merged = if (live) NetworkMerge.mergeCapture(before, incoming)
                             else NetworkMerge.mergeObservations(before, incoming)
                if (before == null) {
                    added++
                } else {
                    val hadName = NetworkMerge.hasName(before)
                    val hasName = NetworkMerge.hasName(merged)
                    if (!hadName && hasName) {
                        changes.add(NameChange(merged.bssid, merged.ssid, null))
                    } else if (hadName && hasName && before.ssid != merged.ssid) {
                        changes.add(NameChange(merged.bssid, merged.ssid, before.ssid))
                    }
                    if (merged.copy(lastSeenAtMs = before.lastSeenAtMs, lastSessionId = before.lastSessionId) != before) {
                        updated++
                    }
                }
                pending[incoming.bssid] = merged
            }


            for (chunk in pending.values.chunked(500)) dao.upsertAll(chunk)
            map.putAll(pending)

            val result = ApplyResult(changes, added, updated)
            revealed.addAndGet(result.namesRevealed)
            result
        }
    }
}
