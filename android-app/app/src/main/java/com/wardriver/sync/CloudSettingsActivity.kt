package com.wardriver.sync

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wardriver.sync.databinding.ActivityCloudSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudSettingsBinding
    private val prefs by lazy { CloudPrefs(this) }
    private var selectedInterval = CloudPrefs.DEFAULT_INTERVAL_MIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloudSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.cloud_settings_title)

        val labels = CloudPrefs.INTERVAL_OPTIONS.map { "$it min" }
        binding.spinnerInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedInterval = CloudPrefs.INTERVAL_OPTIONS[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.switchAuto.isChecked = prefs.autoEnabled
        binding.switchCloud.isChecked = prefs.cloudEnabled
        binding.editUrl.setText(prefs.url)
        val saved = CloudPrefs.INTERVAL_OPTIONS.indexOf(prefs.intervalMin)
        selectedInterval = prefs.intervalMin
        binding.spinnerInterval.setSelection(if (saved >= 0) saved else CloudPrefs.INTERVAL_OPTIONS.indexOf(CloudPrefs.DEFAULT_INTERVAL_MIN))
        if (prefs.hasToken) binding.layoutToken.hint = getString(R.string.cloud_token_saved_hint)

        binding.buttonTest.setOnClickListener { testConnection() }
        binding.buttonResendAll.setOnClickListener { confirmResendAll() }
        binding.buttonSave.setOnClickListener { save() }
    }

    private fun validatedEndpoint(): CloudUrl.Endpoint? {
        return when (val p = CloudUrl.parse(binding.editUrl.text?.toString().orEmpty())) {
            is CloudUrl.Parsed.Ok -> { binding.layoutUrl.error = null; p.endpoint }
            is CloudUrl.Parsed.Error -> { binding.layoutUrl.error = p.message; null }
        }
    }

    private fun effectiveToken(): String? =
        binding.editToken.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: prefs.token

    private fun testConnection() {
        val endpoint = validatedEndpoint() ?: return
        val token = effectiveToken()
        if (token.isNullOrEmpty()) {
            binding.layoutToken.error = "Informe o token"
            return
        }
        binding.layoutToken.error = null
        binding.buttonTest.isEnabled = false
        binding.textResult.text = "Testando…"
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { CloudUploader(HttpUrlPoster(connectTimeoutMs = 8_000, readTimeoutMs = 10_000)).ping(endpoint, token) }
            binding.textResult.text = if (error == null) "Conexão ok: o servidor aceitou o token." else "Falhou: $error"
            binding.buttonTest.isEnabled = true
        }
    }

    private fun confirmResendAll() {
        AlertDialog.Builder(this)
            .setMessage(R.string.cloud_resend_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.resetWatermark()
                Toast.makeText(this, "Tudo será reenviado na próxima sincronização", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        val wantsCloud = binding.switchCloud.isChecked
        val typedToken = binding.editToken.text?.toString()?.trim().orEmpty()
        val rawUrl = binding.editUrl.text?.toString().orEmpty()

        val endpoint = if (wantsCloud || rawUrl.isNotBlank()) validatedEndpoint() ?: return else null
        if (wantsCloud && typedToken.isEmpty() && !prefs.hasToken) {
            binding.layoutToken.error = "Informe o token"
            return
        }
        binding.layoutToken.error = null

        fun commit() {
            prefs.autoEnabled = binding.switchAuto.isChecked
            prefs.intervalMin = selectedInterval
            prefs.cloudEnabled = wantsCloud
            if (endpoint != null) prefs.url = endpoint.base
            if (typedToken.isNotEmpty()) prefs.token = typedToken
            Toast.makeText(this, R.string.cloud_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        if (wantsCloud && !prefs.consentAccepted) {
            AlertDialog.Builder(this)
                .setTitle(R.string.cloud_consent_title)
                .setMessage(getString(R.string.cloud_consent_body, endpoint?.base.orEmpty()))
                .setPositiveButton(R.string.cloud_consent_yes) { _, _ -> prefs.consentAccepted = true; commit() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            commit()
        }
    }
}
