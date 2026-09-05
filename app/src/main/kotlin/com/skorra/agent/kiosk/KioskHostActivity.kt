package com.skorra.agent.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.skorra.agent.AgentConfig
import com.skorra.agent.ui.MainActivity

/**
 * The heart of a real, exit-resistant app lock.
 *
 * Set as the device Home by [com.skorra.agent.device.KioskManager], so the Home button and every
 * boot land here. On resume it enters **lock-task mode** (`startLockTask` — screen pinning, which
 * disables the nav bar's Home/Recents/Back), then launches the locked app (allow-listed, so it
 * stays pinned). A third-party app can't be pinned from outside on its own, so we pin *our* Home
 * task and host the target on top of it — Home returns here and re-launches the target; the user
 * can't reach a launcher.
 *
 * When the admin turns the lock off (config.kioskEnabled=false) this exits lock task and hands
 * back to the normal onboarding screen.
 */
class KioskHostActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No UI of its own — it immediately pins + launches the target in onResume.
    }

    override fun onResume() {
        super.onResume()
        val cfg = AgentConfig.get(this)
        val browser = cfg.kioskMode == "browser"
        val apps = (listOf(cfg.kioskPackage) + cfg.kioskExtraPackages())
            .filter { it.isNotBlank() && it != packageName }.distinct()
        val active = cfg.kioskEnabled && (if (browser) cfg.kioskUrl.isNotBlank() else apps.isNotEmpty())
        if (!active) {
            releaseAndLeave()
            return
        }
        if (!isInLockTask()) {
            runCatching { startLockTask() }.onFailure { Log.w(TAG, "startLockTask failed: ${it.message}") }
        }
        when {
            browser -> startActivity(
                Intent(this, KioskBrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            apps.size == 1 -> launchTarget(apps[0])
            else -> showAppGrid(apps) // multi-app: Home shows a launcher grid of the allowed apps
        }
    }

    /** Minimal launcher for multi-app kiosk: one button per allowed app, nothing else reachable. */
    private fun showAppGrid(packages: List<String>) {
        val pm = packageManager
        val root = android.widget.ScrollView(this).apply { setBackgroundColor(0xFF16181C.toInt()) }
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad * 2, pad, pad)
        }
        for (pkg in packages) {
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            col.addView(android.widget.Button(this).apply {
                text = label
                textSize = 18f
                isAllCaps = false
                setOnClickListener {
                    runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() })
        }
        root.addView(col)
        setContentView(root)
    }

    private fun launchTarget(pkg: String) {
        if (pkg == packageName) return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Log.w(TAG, "no launcher for $pkg"); return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "launch $pkg failed: ${it.message}") }
    }

    private fun releaseAndLeave() {
        if (isInLockTask()) runCatching { stopLockTask() }
        runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    private fun isInLockTask(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION") am.isInLockTaskMode
        }
    }

    companion object {
        private const val TAG = "KioskHost"
    }
}
