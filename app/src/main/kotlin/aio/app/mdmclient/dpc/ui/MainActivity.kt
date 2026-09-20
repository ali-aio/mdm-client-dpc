package aio.app.mdmclient.dpc.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.DeviceIdentity
import aio.app.mdmclient.dpc.DeviceOwner
import aio.app.mdmclient.dpc.MdmService
import aio.app.mdmclient.dpc.R
import aio.app.mdmclient.dpc.databinding.ActivityMainBinding
import aio.app.mdmclient.dpc.net.ApiClient
import aio.app.mdmclient.dpc.net.Telemetry
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
        applyLaunchExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyLaunchExtras(intent)
    }

    /**
     * Hands-free enrollment over adb (camera-less kiosks, bulk provisioning):
     *   adb shell am start -n aio.app.mdmclient.dpc/.ui.MainActivity \
     *       --es server_url https://mdm.example.com --es enroll_token enr_…
     * Seeds the config from the extras and starts the agent, which exchanges the token
     * for a device key. Nothing to type on the device.
     */
    private fun applyLaunchExtras(intent: Intent?) {
        val server = intent?.getStringExtra("server_url")?.trim().orEmpty()
        val token = intent?.getStringExtra("enroll_token")?.trim().orEmpty()
        val key = intent?.getStringExtra("api_key")?.trim().orEmpty()
        if (server.isEmpty() && token.isEmpty() && key.isEmpty()) return
        if (server.isNotEmpty()) config.serverUrl = server
        if (token.isNotEmpty() && !(config.apiKey.isNotBlank() && token == config.lastEnrollToken)) {
            config.enrollToken = token
            config.lastEnrollToken = token
            config.apiKey = ""
        }
        if (key.isNotEmpty()) config.apiKey = key
        binding.inputServerUrl.setText(config.serverUrl)
        binding.inputApiKey.setText(if (token.isNotEmpty()) token else config.apiKey)
        MdmService.start(this)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun save() {
        config.serverUrl = binding.inputServerUrl.text?.toString().orEmpty()
        applyCredentialField()
        MdmService.start(this)
        refreshStatus()
    }

    /**
     * The key field accepts either a device API key or an enrollment token (server issues
     * tokens with an "enr_" prefix). A token is stashed for MdmService to exchange for a
     * per-device key at /api/v1/enroll; anything else is used as the API key directly.
     */
    private fun applyCredentialField() {
        val cred = binding.inputApiKey.text?.toString().orEmpty().trim()
        if (cred.startsWith("enr_")) {
            // The field keeps showing the enrollment token after a successful enrollment,
            // so re-applying it blindly threw away the device key the server had issued —
            // every later check-in then came back 401 and the device went quiet. A token
            // that has already been spent is not a reason to un-enroll.
            if (config.apiKey.isNotBlank() && cred == config.lastEnrollToken) return
            config.enrollToken = cred
            config.lastEnrollToken = cred
            config.apiKey = ""
        } else {
            config.apiKey = cred
        }
    }

    private fun testConnection() {
        // Persist current field values first so we test what's on screen.
        config.serverUrl = binding.inputServerUrl.text?.toString().orEmpty()
        applyCredentialField()
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
        val summary = config.enrollSummary
        binding.statusEnrollment.visibility = if (summary.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.statusEnrollment.text = getString(R.string.status_enrollment) + ": " + summary
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
