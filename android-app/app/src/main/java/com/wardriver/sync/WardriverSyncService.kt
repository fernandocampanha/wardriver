package com.wardriver.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, DEVICE_NOT_PAIRED, ERROR }


data class ImportSummary(
    val layout: String,
    val rowsRead: Int,
    val rowsSkipped: Int,

    val networks: Int,

    val added: Int,

    val updated: Int,

    val withLocation: Int,

    val namesFilled: Int = 0,
)

interface SyncListener {
    fun onStatusChanged(status: ConnectionStatus)
    fun onNetworksUpdated(networks: Collection<NetworkRecord>)


    fun onNamesRevealed(total: Int) {}


    fun onCloudStatus(text: String) {}
    fun onLog(message: String)
}















































class WardriverSyncService : Service() {

    companion object {
        private const val TAG = "WardriverSync"
        private const val DEVICE_NAME = "WARDRIVER"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val NOTIFICATION_CHANNEL_ID = "wardriver_sync"
        private const val NOTIFICATION_ID = 1
        private val BEGIN_REGEX = Regex("""^--- BEGIN (\S+) bytes=(-?\d+) ---$""")
        private val CHECKSUM_REGEX = Regex("""^--- CHECKSUM (\S+) crc32=([0-9a-fA-F]+) bytes=(\d+) ---$""")
        private val SESSION_REGEX = Regex("""^--- SESSION (\S*) ---$""")
        private const val INCOMING_BATCH_MAX = 200




        private const val BLOB_STALL_TIMEOUT_MS = 15_000L



        private const val BANNER_TIMEOUT_MS = 15_000L
        private const val WATCHDOG_PERIOD_MS = 3_000L



        private const val MAX_CHECKSUM_RETRIES = 3


        private const val AUTO_TICK_MS = 5_000L
    }

    private enum class ReadState { LINE, RAW_BLOB, RAW_RECORD }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var socket: BluetoothSocket? = null
    private var readThread: Thread? = null
    @Volatile private var listener: SyncListener? = null
    @Volatile private var status: ConnectionStatus = ConnectionStatus.DISCONNECTED


    @Volatile private var lastRxMs = 0L
    @Volatile private var connectedAtMs = 0L
    @Volatile private var bannerSeen = false
    @Volatile private var inBlobTransfer = false
    private var watchdogRunnable: Runnable? = null
    private var invalidInBlob = 0

    private val checksumFailures = HashMap<String, Int>()




    @Volatile private var userInitiatedDisconnect = false
    private var reconnectRunnable: Runnable? = null
    private val reconnectDelayMs = 4000L







    @Volatile private var inForeground = false





    private var locationManager: LocationManager? = null
    @Volatile private var currentOutput: OutputStream? = null
    private val writeLock = Any()
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val out = currentOutput ?: return
            sendLine(out, "LOC %.6f %.6f %.1f".format(Locale.US, location.latitude, location.longitude, location.accuracy))
        }
    }




    private val db by lazy { WardriverDatabase.getInstance(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)






    private val incoming = Channel<NetworkRecord>(Channel.UNLIMITED)


    private val store by lazy { NetworkStore(db.networkDao()) }


    private val cloudPrefs by lazy { CloudPrefs(this) }
    private val syncTracker = SyncTracker()
    private val cycleMutex = Mutex()
    @Volatile private var autoJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): WardriverSyncService = this@WardriverSyncService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()




        serviceScope.launch {
            try {
                store.load()
                if (store.size > 0) {
                    mainHandler.post { listener?.onNetworksUpdated(store.values) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "falha ao carregar redes salvas: ${e.message}", e)
            }
            processIncoming()
        }
    }





    suspend fun exportCsv(out: OutputStream): Int {
        val all = db.networkDao().getAll()
        out.bufferedWriter().use { writer ->
            writer.write("bssid,ssid,ssid_hidden,channel,rssi,encryption,times_seen,wps_active,lat,lon,last_session_id,last_seen_at_ms")
            writer.newLine()
            for (r in all) {
                val fields = listOf(
                    r.bssid,
                    csvEscape(r.ssid),
                    r.ssidHidden.toString(),
                    r.channel.toString(),
                    r.rssi.toString(),
                    r.encryption,
                    r.timesSeen.toString(),
                    r.wpsActive.toString(),
                    r.lat?.toString() ?: "",
                    r.lon?.toString() ?: "",
                    r.lastSessionId,
                    r.lastSeenAtMs.toString(),
                )
                writer.write(fields.joinToString(","))
                writer.newLine()
            }
        }
        return all.size
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }






    suspend fun importCsv(input: InputStream): ImportSummary {
        val parsed = withContext(Dispatchers.IO) {
            input.reader(Charsets.UTF_8).use { CsvImporter.parse(it) }
        }

        val result = store.applyImport(parsed.records)
        notifyStoreChanged(result, "${store.size} rede(s) unica(s)")
        listener?.onLog(
            "CSV importado (${parsed.layout}): ${parsed.records.size} rede(s), ${result.added} nova(s), ${result.updated} atualizada(s), " +
                "${result.namesRevealed} nome(s) preenchido(s), ${parsed.rowsSkipped} linha(s) ignorada(s)"
        )
        return ImportSummary(
            layout = parsed.layout,
            rowsRead = parsed.rowsRead,
            rowsSkipped = parsed.rowsSkipped,
            networks = parsed.records.size,
            added = result.added,
            updated = result.updated,
            withLocation = parsed.records.count { NetworkMerge.hasLocation(it) },
            namesFilled = result.namesRevealed,
        )
    }

    fun setListener(l: SyncListener?) {
        listener = l
        l?.onStatusChanged(status)
        l?.onNetworksUpdated(store.values)
        l?.onNamesRevealed(store.namesRevealed)
        l?.onCloudStatus(cloudStatusText())
    }

    fun currentNetworks(): Collection<NetworkRecord> = store.values



    private fun notifyStoreChanged(result: ApplyResult, notificationText: String) {
        for (c in result.nameChanges) {
            val what = if (c.wasHidden) "SSID revelado" else "rede renomeada (era \"${c.previousSsid}\")"
            Log.i(TAG, "$what: \"${c.ssid}\" (${c.bssid})")
            listener?.onLog("$what: ${c.ssid} (${c.bssid})")
        }
        mainHandler.post {
            listener?.onNetworksUpdated(store.values)
            if (result.nameChanges.isNotEmpty()) listener?.onNamesRevealed(store.namesRevealed)
            updateNotification(
                if (store.namesRevealed > 0) "$notificationText · ${store.namesRevealed} nome(s) revelado(s)" else notificationText
            )
        }
    }


    fun connect() {
        if (status == ConnectionStatus.CONNECTING || status == ConnectionStatus.CONNECTED) return

        userInitiatedDisconnect = false
        cancelPendingReconnect()

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus(ConnectionStatus.ERROR)
            listener?.onLog("Bluetooth desligado ou indisponivel neste aparelho")
            return
        }

        val device = try {
            adapter.bondedDevices.find { it.name == DEVICE_NAME }
        } catch (e: SecurityException) {
            listener?.onLog("Sem permissao BLUETOOTH_CONNECT")
            null
        }

        if (device == null) {
            setStatus(ConnectionStatus.DEVICE_NOT_PAIRED)
            return
        }

        setStatus(ConnectionStatus.CONNECTING)
        if (inForeground) {
            updateNotification("Conectando...")
        } else {
            startForegroundCompat(buildNotification("Conectando..."))
        }

        readThread = Thread({
            try {






                val sock = try {
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    m.invoke(device, 1) as BluetoothSocket
                } catch (e: Exception) {
                    Log.w(TAG, "fallback por reflection indisponivel, usando SDP padrao: ${e.message}")
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                }
                socket = sock



                sock.connect()

                setStatus(ConnectionStatus.CONNECTED)


                startAutoSync()
                currentOutput = sock.outputStream
                connectedAtMs = SystemClock.elapsedRealtime()
                lastRxMs = connectedAtMs
                bannerSeen = false
                inBlobTransfer = false
                mainHandler.post {
                    startTransferWatchdog()
                    startLocationUpdates()
                    updateNotification("Conectado — recebendo dados")
                }

                readLoop(sock.inputStream, sock.outputStream)
            } catch (e: IOException) {
                Log.w(TAG, "conexao falhou/encerrada: ${e.javaClass.simpleName}: ${e.message}", e)
                listener?.onLog("Conexao encerrada: ${e.message}")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException ao conectar: ${e.message}", e)
                listener?.onLog("Sem permissao para conectar")
            } finally {
                closeSocketQuietly()
                currentOutput = null
                syncTracker.onDisconnected()
                stopTransferWatchdog()
                mainHandler.post { stopLocationUpdates() }
                setStatus(ConnectionStatus.DISCONNECTED)
                if (userInitiatedDisconnect) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    inForeground = false
                } else {

                    if (inForeground) updateNotification("Desconectado — reconectando...")
                    scheduleReconnect()
                }
            }
        }, "bt-sync-read").also { it.start() }
    }








    private fun scheduleReconnect() {
        cancelPendingReconnect()
        val runnable = Runnable {
            reconnectRunnable = null
            if (!userInitiatedDisconnect && status == ConnectionStatus.DISCONNECTED) {
                listener?.onLog("Reconectando automaticamente...")
                connect()
            }
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, reconnectDelayMs)
    }

    private fun cancelPendingReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        cancelPendingReconnect()
        stopAutoSync()
        closeSocketQuietly()

        if (cloudPrefs.cloudReady) {
            serviceScope.launch { runCycle("fim da coleta", syncHardware = false) }
        }
    }





    fun requestSync() {
        val out = currentOutput
        if (out == null) {
            listener?.onLog("Sem conexao ativa — nao e possivel sincronizar agora")
            return
        }
        sendLine(out, "SYNC")
        listener?.onLog("Sincronizacao solicitada...")
    }








    @Synchronized
    private fun startAutoSync() {
        if (autoJob?.isActive == true) return
        autoJob = serviceScope.launch {
            var lastRun = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(AUTO_TICK_MS)
                if (!cloudPrefs.autoEnabled) { lastRun = SystemClock.elapsedRealtime(); continue }
                val now = SystemClock.elapsedRealtime()
                if (now - lastRun < cloudPrefs.intervalMin * 60_000L) continue
                lastRun = now
                try {
                    runCycle("automático")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "ciclo automatico falhou: ${e.message}", e)
                }
            }
        }
    }

    @Synchronized
    private fun stopAutoSync() {
        autoJob?.cancel()
        autoJob = null
        syncTracker.cancel()
    }


    fun runCycleNow() {
        serviceScope.launch { runCycle("manual") }
    }

    fun cloudStatusText(): String {
        val ms = cloudPrefs.lastCycleMs
        if (ms == 0L) return ""
        return java.text.SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(java.util.Date(ms)) + " — " + cloudPrefs.lastCycleSummary
    }

    private suspend fun runCycle(reason: String, syncHardware: Boolean = true) {
        if (!cycleMutex.tryLock()) {
            listener?.onLog("Ciclo ($reason) ignorado: outro ja esta em andamento")
            return
        }
        try {
            reportCycle("Sincronizando ($reason)…", persist = false)
            val result = CycleRunner(
                syncHardware = { if (syncHardware) syncWithDevice() else HardwareOutcome.Skipped("coleta encerrada") },
                upload = { uploadPending() },
            ).run()
            val summary = result.summary()
            Log.i(TAG, "ciclo ($reason): $summary")
            listener?.onLog("Ciclo ($reason): $summary")
            reportCycle(summary, persist = true)
        } finally {
            cycleMutex.unlock()
        }
    }

    private fun reportCycle(text: String, persist: Boolean) {
        if (persist) cloudPrefs.recordCycle(System.currentTimeMillis(), text)
        val shown = if (persist) cloudStatusText() else text
        mainHandler.post {
            listener?.onCloudStatus(shown)
            if (inForeground) updateNotification(text)
        }
    }


    private suspend fun syncWithDevice(): HardwareOutcome {
        if (status != ConnectionStatus.CONNECTED || currentOutput == null) {
            return HardwareOutcome.Skipped("sem conexão com o dispositivo")
        }
        syncTracker.begin(SystemClock.elapsedRealtime())
        try {
            requestSync()
            while (true) {
                delay(250)
                when (val o = syncTracker.evaluate(SystemClock.elapsedRealtime())) {
                    SyncTracker.Outcome.Pending -> continue
                    is SyncTracker.Outcome.Done -> return HardwareOutcome.Synced(o.blocks, o.problems)
                    is SyncTracker.Outcome.Failed -> return HardwareOutcome.Failed(o.reason)
                    SyncTracker.Outcome.TimedOut -> return HardwareOutcome.Failed("tempo esgotado esperando o dispositivo")
                }
            }
        } finally {
            syncTracker.cancel()
        }
    }


    private suspend fun uploadPending(): UploadResult? {
        if (!cloudPrefs.cloudReady) return null
        val endpoint = cloudPrefs.endpoint() ?: return null
        val token = cloudPrefs.token ?: return null
        val source = PendingSource { since, until -> db.networkDao().getSeenBetween(since, until) }
        return CloudUploader(HttpUrlPoster()).uploadPending(
            endpoint, token, cloudPrefs.deviceId, source, cloudPrefs, untilMs = System.currentTimeMillis(),
        )
    }







    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (hasLocationPermission()) {
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
        } catch (e: RuntimeException) {





            Log.w(TAG, "startForeground negado pelo sistema: ${e.javaClass.simpleName}: ${e.message}")
            listener?.onLog("Sem modo foreground agora (${e.javaClass.simpleName}) — abra o app e toque em Conectar para manter a coleta em segundo plano")
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (e: IOException) {

        }
        socket = null
    }

    private fun setStatus(s: ConnectionStatus) {
        status = s
        mainHandler.post { listener?.onStatusChanged(s) }
    }




    private fun sendLine(out: OutputStream, text: String) {
        synchronized(writeLock) {
            try {
                out.write((text + "\r\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: IOException) {
                Log.w(TAG, "falha ao enviar \"$text\": ${e.message}")
            }
        }
    }




    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            listener?.onLog("Sem permissao de localizacao — usando GPS do modulo do dispositivo")
            return
        }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager = lm
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, locationListener, Looper.getMainLooper())
            listener?.onLog("GPS do celular ativado — coordenadas tem prioridade sobre o modulo GPS do dispositivo")
        } catch (e: Exception) {
            Log.w(TAG, "falha ao iniciar atualizacoes de localizacao: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        locationManager?.removeUpdates(locationListener)
        locationManager = null
    }




    private fun startTransferWatchdog() {
        stopTransferWatchdog()
        val runnable = object : Runnable {
            override fun run() {
                if (status != ConnectionStatus.CONNECTED) return
                val now = SystemClock.elapsedRealtime()
                val stalled = inBlobTransfer && now - lastRxMs > BLOB_STALL_TIMEOUT_MS
                val noBanner = !bannerSeen && now - connectedAtMs > BANNER_TIMEOUT_MS
                if (stalled || noBanner) {
                    val why = if (stalled) "transferencia de arquivo parada ha ${(now - lastRxMs) / 1000}s"
                              else "banner do dispositivo nao chegou em ${BANNER_TIMEOUT_MS / 1000}s"
                    listener?.onLog("Conexao com problema ($why) — reconectando")
                    Log.w(TAG, "watchdog: $why")
                    closeSocketQuietly()
                    return
                }
                mainHandler.postDelayed(this, WATCHDOG_PERIOD_MS)
            }
        }
        watchdogRunnable = runnable
        mainHandler.postDelayed(runnable, WATCHDOG_PERIOD_MS)
    }

    private fun stopTransferWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }






    private fun persistBlob(records: Collection<NetworkRecord>) {
        if (records.isEmpty()) return
        val result = runBlocking { store.applyCaptures(records) }
        notifyStoreChanged(result, "${store.size} rede(s) unica(s) recebidas")
    }



    private fun collectBlobRecord(bytes: ByteArray, sessionId: String, into: MutableMap<String, NetworkRecord>) {
        try {
            val record = WardriverRecordCodec.decode(bytes, sessionId)



            into[record.bssid] = NetworkMerge.mergeCapture(into[record.bssid], record)
        } catch (e: IllegalArgumentException) {
            invalidInBlob++
            if (invalidInBlob <= 3) Log.w(TAG, "registro binario invalido descartado: ${e.message}")
        }
    }

    private fun readLoop(input: java.io.InputStream, output: OutputStream) {
        val lineBuf = ByteArrayOutputStream()
        var crc = CRC32()
        var crcBytes = 0L
        var state = ReadState.LINE


        var blobRemaining = 0
        var blobFilename = ""


        val recordBuf = ByteArray(WardriverRecordCodec.SIZE)
        var recordBufPos = 0



        var liveSessionId = ""

        val buf = ByteArray(1024)


        val blobRecords = LinkedHashMap<String, NetworkRecord>()

        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            lastRxMs = SystemClock.elapsedRealtime()

            for (i in 0 until n) {
                val b = buf[i]

                when (state) {
                    ReadState.RAW_BLOB -> {
                        recordBuf[recordBufPos++] = b
                        crc.update(b.toInt() and 0xFF)
                        crcBytes++
                        blobRemaining--
                        if (recordBufPos == WardriverRecordCodec.SIZE) {
                            collectBlobRecord(recordBuf, blobFilename.removeSuffix(".wdr"), blobRecords)
                            recordBufPos = 0
                        }
                        if (blobRemaining <= 0) {
                            recordBufPos = 0
                            inBlobTransfer = false
                            state = ReadState.LINE
                        }
                    }
                    ReadState.RAW_RECORD -> {
                        recordBuf[recordBufPos++] = b
                        if (recordBufPos == WardriverRecordCodec.SIZE) {
                            decodeAndStore(recordBuf, liveSessionId)
                            recordBufPos = 0
                            state = ReadState.LINE
                        }
                    }
                    ReadState.LINE -> {
                        if (lineBuf.size() == 0 && b == WardriverRecordCodec.LIVE_RECORD_MARKER) {
                            state = ReadState.RAW_RECORD
                            recordBufPos = 0
                            continue
                        }
                        if (b != '\n'.code.toByte()) {
                            lineBuf.write(b.toInt())
                            continue
                        }

                        var lineBytes = lineBuf.toByteArray()
                        if (lineBytes.isNotEmpty() && lineBytes.last() == '\r'.code.toByte()) {
                            lineBytes = lineBytes.copyOf(lineBytes.size - 1)
                        }
                        val lineText = String(lineBytes, Charsets.UTF_8)

                        when {
                            lineText.startsWith("--- BEGIN ") -> {
                                val m = BEGIN_REGEX.find(lineText)
                                val fileBytes = m?.groupValues?.get(2)?.toIntOrNull() ?: -1
                                if (m != null && fileBytes >= 0) {
                                    crc = CRC32()
                                    crcBytes = 0L
                                    blobFilename = m.groupValues[1]
                                    blobRemaining = fileBytes
                                    blobRecords.clear()
                                    invalidInBlob = 0
                                    inBlobTransfer = fileBytes > 0
                                    state = if (fileBytes > 0) ReadState.RAW_BLOB else ReadState.LINE
                                    syncTracker.onBlockBegin(SystemClock.elapsedRealtime())
                                } else {
                                    listener?.onLog("BEGIN invalido ignorado: $lineText")
                                }
                            }
                            lineText.startsWith("--- CHECKSUM ") -> {
                                val m = CHECKSUM_REGEX.find(lineText)
                                if (m != null) {
                                    val (file, hex, bytesStr) = m.destructured
                                    val announcedCrc = hex.toLong(16)
                                    val announcedBytes = bytesStr.toLongOrNull() ?: -1L
                                    if (invalidInBlob > 0) {
                                        listener?.onLog("$file: $invalidInBlob registro(s) invalido(s) descartado(s)")
                                    }
                                    if (crc.value == announcedCrc && crcBytes == announcedBytes) {

                                        val stored = try {
                                            persistBlob(blobRecords.values)
                                            true
                                        } catch (e: Exception) {
                                            Log.e(TAG, "falha ao gravar $file no banco: ${e.message}", e)
                                            false
                                        }
                                        if (stored) {
                                            checksumFailures.remove(file)
                                            sendLine(output, "ACK $hex")
                                            listener?.onLog("verificado e confirmado: $file (crc32=$hex, $announcedBytes bytes, ${blobRecords.size} rede(s))")
                                        } else {
                                            listener?.onLog("$file: CRC ok mas falhou ao gravar no banco — NAO confirmado, sera reenviado")
                                        }
                                        syncTracker.onBlockEnd(SystemClock.elapsedRealtime(), confirmed = stored)
                                    } else {
                                        blobRecords.clear()
                                        syncTracker.onBlockEnd(SystemClock.elapsedRealtime(), confirmed = false)
                                        val failures = (checksumFailures[file] ?: 0) + 1
                                        checksumFailures[file] = failures
                                        listener?.onLog(
                                            "checksum NAO bate em $file — local=${crc.value.toString(16)}/$crcBytes " +
                                                "anunciado=$hex/$announcedBytes — NAO confirmado (falha $failures)"
                                        )
                                        if (failures <= MAX_CHECKSUM_RETRIES) {
                                            listener?.onLog("Reconectando para o dispositivo reenviar $file (tentativa $failures de $MAX_CHECKSUM_RETRIES)")
                                            closeSocketQuietly()
                                            return
                                        }
                                        listener?.onLog("Desistindo de $file por ora ($MAX_CHECKSUM_RETRIES falhas seguidas) — continua guardado no dispositivo")
                                    }
                                }
                                blobRecords.clear()
                                inBlobTransfer = false
                                crc = CRC32()
                                crcBytes = 0L
                            }
                            lineText.startsWith("--- SESSION ") -> {
                                val m = SESSION_REGEX.find(lineText)
                                if (m != null) {
                                    liveSessionId = m.groupValues[1]
                                    syncTracker.onSession(SystemClock.elapsedRealtime())
                                }
                            }
                            lineText.startsWith("=== FIM DO BACKLOG") -> {
                                listener?.onLog("backlog recebido — streaming ao vivo iniciado")
                            }
                            lineText.startsWith("STATS ") -> {


                                FirmwareStatsLog.append(this, lineText)
                            }
                            lineText.isNotBlank() -> {
                                if (lineText.startsWith("=== WARDRIVER")) bannerSeen = true
                                if (lineText.startsWith("ERRO ")) syncTracker.onDeviceError(lineText)
                                listener?.onLog(lineText)
                            }
                        }
                        lineBuf.reset()
                    }
                }
            }
        }
    }




    private fun decodeAndStore(bytes: ByteArray, sessionId: String) {
        try {
            storeRecord(WardriverRecordCodec.decode(bytes, sessionId))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "registro binario invalido descartado: ${e.message}")
        }
    }

    private fun storeRecord(record: NetworkRecord) {
        incoming.trySend(record)
    }





    private suspend fun processIncoming() {
        val batch = ArrayList<NetworkRecord>(INCOMING_BATCH_MAX)
        for (first in incoming) {
            batch.clear()
            batch.add(first)
            while (batch.size < INCOMING_BATCH_MAX) {
                batch.add(incoming.tryReceive().getOrNull() ?: break)
            }
            try {
                val result = store.applyCaptures(batch)
                notifyStoreChanged(result, "${store.size} rede(s) unica(s) recebidas")
            } catch (e: Exception) {


                Log.e(TAG, "falha ao gravar lote de ${batch.size} registro(s): ${e.message}", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        userInitiatedDisconnect = true
        cancelPendingReconnect()
        stopAutoSync()
        stopTransferWatchdog()
        closeSocketQuietly()
        incoming.close()
        serviceScope.cancel()
        super.onDestroy()
    }
}
