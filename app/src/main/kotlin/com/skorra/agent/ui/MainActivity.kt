package com.skorra.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.skorra.agent.AgentConfig
import com.skorra.agent.DeviceIdentity
import com.skorra.agent.DeviceOwner
import com.skorra.agent.MdmService
import com.skorra.agent.R
import com.skorra.agent.databinding.ActivityMainBinding
import com.skorra.agent.net.ApiClient
import com.skorra.agent.net.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Onboarding + status screen. Shows Device Owner state and serial, lets the operator set the
 * server URL + device API key, and starts the agent service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AgentConfig
    private lateinit var deviceOwner: DeviceOwner

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = AgentConfig.get(this)
        deviceOwner = DeviceOwner(this)

        binding.inputServerUrl.setText(config.serverUrl)
        binding.inputApiKey.setText(config.apiKey)

        binding.buttonSave.setOnClickListener { save() }
        binding.buttonTest.setOnClickListener { testConnection() }

        maybeRequestNotifPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun save() {
        config.serverUrl = binding.inputServerUrl.text?.toString().orEmpty()
        config.apiKey = binding.inputApiKey.text?.toString().orEmpty()
        MdmService.start(this)
        refreshStatus()
    }

    private fun testConnection() {
        // Persist current field values first so we test what's on screen.
        config.serverUrl = binding.inputServerUrl.text?.toString().orEmpty()
        config.apiKey = binding.inputApiKey.text?.toString().orEmpty()
        binding.statusConnection.text = getString(R.string.status_connection) + ": testing…"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ApiClient(config).checkin(Telemetry.buildCheckin(this@MainActivity, includeApps = false)) != null
                }.getOrDefault(false)
            }
            binding.statusConnection.text = getString(R.string.status_connection) + ": " +
                if (ok) "connected ✓" else "failed ✗"
        }
    }

    private fun refreshStatus() {
        val serial = DeviceIdentity.serial(this)
        binding.statusDeviceOwner.text = getString(R.string.status_device_owner) + ": " +
            if (deviceOwner.isDeviceOwner) getString(R.string.do_active) else getString(R.string.do_inactive)
        binding.statusSerial.text = getString(R.string.status_serial) + ": " + serial
        binding.statusConnection.text = getString(R.string.status_server) + ": " +
            (config.serverUrl.ifBlank { "—" })
    }

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
