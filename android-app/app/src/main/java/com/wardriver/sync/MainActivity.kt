package com.wardriver.sync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.wardriver.sync.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SyncListener {

    private lateinit var binding: ActivityMainBinding
    private val adapter = NetworkAdapter()
    private var service: WardriverSyncService? = null
    private var bound = false

    private var allNetworks: Collection<NetworkRecord> = emptyList()
    private var searchQuery: String = ""
    private var encryptionFilter: String = ENCRYPTION_FILTER_ALL
    private var hiddenOnly: Boolean = false

    private var namesRevealed: Int = 0

    companion object {
        private const val ENCRYPTION_FILTER_ALL = "TODAS"
        private val ENCRYPTION_FILTER_OPTIONS =
            listOf(ENCRYPTION_FILTER_ALL, "OPEN", "WEP", "WPA", "WPA2", "WPA3")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as WardriverSyncService.LocalBinder).getService()
            service?.setListener(this@MainActivity)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->

        val btGranted = results
            .filterKeys { it != android.Manifest.permission.ACCESS_FINE_LOCATION }
            .values.all { it }
        if (btGranted) {
            service?.connect()
        } else {
            Toast.makeText(this, "Permissao de Bluetooth necessaria para conectar", Toast.LENGTH_LONG).show()
        }
        if (results[android.Manifest.permission.ACCESS_FINE_LOCATION] == false) {
            Toast.makeText(this, "Sem permissao de localizacao — usando GPS do modulo do dispositivo", Toast.LENGTH_LONG).show()
        }
    }

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) writeCsvTo(uri) }

    private val importCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importCsvFrom(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerNetworks.layoutManager = LinearLayoutManager(this)
        binding.recyclerNetworks.adapter = adapter

        binding.buttonConnect.setOnClickListener {
            if (service == null) return@setOnClickListener
            if (binding.buttonConnect.text == getString(R.string.disconnect)) {
                service?.disconnect()
            } else {
                requestPermissionsAndConnect()
            }
        }

        binding.buttonSync.setOnClickListener {
            service?.requestSync()
            Toast.makeText(this, getString(R.string.sync_requested), Toast.LENGTH_SHORT).show()
        }

        binding.buttonCloudSettings.setOnClickListener {
            startActivity(Intent(this, CloudSettingsActivity::class.java))
        }

        binding.buttonCycleNow.setOnClickListener {

            service?.runCycleNow()
            Toast.makeText(this, getString(R.string.sync_requested), Toast.LENGTH_SHORT).show()
        }

        binding.buttonExportCsv.setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            exportCsvLauncher.launch("wardriver_$stamp.csv")
        }

        binding.buttonImportCsv.setOnClickListener {

            importCsvLauncher.launch(
                arrayOf(
                    "text/csv", "text/comma-separated-values", "application/csv",
                    "text/plain", "application/vnd.ms-excel", "application/octet-stream",
                )
            )
        }

        binding.buttonViewMap.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }

        binding.buttonAnalysis.setOnClickListener {
            startActivity(Intent(this, AnalysisActivity::class.java))
        }

        setupFilters()

        startService(Intent(this, WardriverSyncService::class.java))
    }

    private fun setupFilters() {
        val encryptionLabels = listOf(getString(R.string.filter_all_encryptions), "OPEN", "WEP", "WPA", "WPA2", "WPA3")
        binding.spinnerEncryption.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, encryptionLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                applyFilters()
            }
        })

        binding.editSearch.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                true
            } else {
                false
            }
        }

        binding.spinnerEncryption.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                encryptionFilter = ENCRYPTION_FILTER_OPTIONS[position]
                applyFilters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.checkboxHiddenOnly.setOnCheckedChangeListener { _, checked ->
            hiddenOnly = checked
            applyFilters()
        }
    }

    private fun applyFilters() {

        val query = NetworkSearch.Query.parse(searchQuery)
        val filtered = allNetworks.filter { net ->
            val matchesEncryption = encryptionFilter == ENCRYPTION_FILTER_ALL || net.encryption == encryptionFilter
            val matchesHidden = !hiddenOnly || NetworkSearch.isUnnamed(net)
            query.matches(net) && matchesEncryption && matchesHidden
        }
        adapter.submitList(filtered)
        val revealedSuffix = if (namesRevealed > 0) " · $namesRevealed nome(s) revelado(s)" else ""
        binding.textCount.text = if (filtered.size == allNetworks.size) {
            "${allNetworks.size} redes unicas$revealedSuffix"
        } else {
            "${filtered.size} de ${allNetworks.size} redes unicas$revealedSuffix"
        }
    }

    private fun writeCsvTo(uri: Uri) {
        val svc = service ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val count = contentResolver.openOutputStream(uri)?.use { out -> svc.exportCsv(out) } ?: 0
                runOnUiThread {
                    Toast.makeText(this@MainActivity, getString(R.string.export_csv_success, count), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, getString(R.string.export_csv_failure, e.message ?: "?"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importCsvFrom(uri: Uri) {
        val svc = service
        if (svc == null) {
            Toast.makeText(this, getString(R.string.import_csv_failure, "servico indisponivel, tente de novo"), Toast.LENGTH_LONG).show()
            return
        }
        binding.buttonImportCsv.isEnabled = false
        Toast.makeText(this, getString(R.string.import_csv_running), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {

                val summary = withContext(Dispatchers.IO + NonCancellable) {
                    contentResolver.openInputStream(uri)?.use { svc.importCsv(it) }
                        ?: throw IOException("nao foi possivel abrir o arquivo")
                }
                val message = buildString {
                    append("Formato: ${summary.layout}\n\n")
                    append("${summary.networks} rede(s) no arquivo\n")
                    append("  ${summary.added} nova(s)\n")
                    append("  ${summary.updated} ja existiam e foram atualizadas\n")
                    if (summary.namesFilled > 0) append("  ${summary.namesFilled} nome(s) de redes ocultas preenchido(s)\n")
                    append("  ${summary.withLocation} com posicao (aparecem no mapa)\n")
                    if (summary.rowsSkipped > 0) {
                        append("\n${summary.rowsSkipped} linha(s) ignorada(s) por nao terem um BSSID valido")
                    }
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.import_csv_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity, getString(R.string.import_csv_failure, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.buttonImportCsv.isEnabled = true
            }
        }
    }

    private fun requestPermissionsAndConnect() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        needed.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.isEmpty()) {
            service?.connect()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, WardriverSyncService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.setListener(null)
            unbindService(connection)
            bound = false
        }
    }

    override fun onStatusChanged(status: ConnectionStatus) {
        runOnUiThread {
            binding.textStatus.text = when (status) {
                ConnectionStatus.DISCONNECTED -> getString(R.string.status_disconnected)
                ConnectionStatus.CONNECTING -> getString(R.string.status_connecting)
                ConnectionStatus.CONNECTED -> getString(R.string.status_connected)
                ConnectionStatus.DEVICE_NOT_PAIRED -> getString(R.string.status_not_paired)
                ConnectionStatus.ERROR -> "Erro de conexao"
            }
            binding.buttonConnect.text = if (status == ConnectionStatus.CONNECTED) {
                getString(R.string.disconnect)
            } else {
                getString(R.string.connect)
            }
            binding.buttonSync.isEnabled = (status == ConnectionStatus.CONNECTED)
        }
    }

    override fun onNamesRevealed(total: Int) {
        runOnUiThread {
            namesRevealed = total
            applyFilters()
        }
    }

    override fun onNetworksUpdated(networks: Collection<NetworkRecord>) {
        runOnUiThread {
            allNetworks = networks
            applyFilters()
        }
    }

    override fun onCloudStatus(text: String) {
        runOnUiThread {
            binding.textCloud.text = if (text.isBlank()) getString(R.string.cloud_status_none) else "Nuvem: $text"
        }
    }

    override fun onLog(message: String) {
        android.util.Log.i("WardriverSync", message)
    }
}
