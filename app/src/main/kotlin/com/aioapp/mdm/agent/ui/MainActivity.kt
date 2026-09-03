package com.aioapp.mdm.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aioapp.mdm.agent.AgentConfig
import com.aioapp.mdm.agent.DeviceIdentity
import com.aioapp.mdm.agent.DeviceOwner
import com.aioapp.mdm.agent.MdmService
import com.aioapp.mdm.agent.R
import com.aioapp.mdm.agent.databinding.ActivityMainBinding

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
        binding.buttonTest.setOnClickListener {
            // TODO(Phase 1): real health check against /api/v1/checkin.
            refreshStatus()
        }

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
