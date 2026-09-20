package aio.app.mdmclient.dpc

import android.os.Build
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import aio.app.mdmclient.dpc.input.MdmAccessibilityService

/**
 * The adb-issued grants that lift the agent past what a Device Owner can give itself, with no
 * root: tools/enroll-adb.sh runs `pm grant` for READ_LOGS / WRITE_SECURE_SETTINGS and
 * `appops set` for PROJECT_MEDIA / GET_USAGE_STATS. Each check here is what the agent actually
 * holds, so capabilities stay honest on a device enrolled by QR (no adb, no grants).
 */
object Grants {

    private const val TAG = "Grants"
    private const val OP_PROJECT_MEDIA = "android:project_media"

    fun readLogs(ctx: Context): Boolean = granted(ctx, android.Manifest.permission.READ_LOGS)

    fun writeSecureSettings(ctx: Context): Boolean =
        granted(ctx, android.Manifest.permission.WRITE_SECURE_SETTINGS)

    /** PROJECT_MEDIA allowed = the system skips the per-session screen capture consent. */
    fun projectMediaAllowed(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // unsafeCheckOpNoThrow is API 29; minSdk is 28. Without the branch, API 28 took a
        // NoSuchMethodError into runCatching and reported "not allowed" for a device where
        // the appop may well be granted — the deprecated call is the correct answer there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(OP_PROJECT_MEDIA, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(OP_PROJECT_MEDIA, Process.myUid(), ctx.packageName)
        } == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun accessibilityEnabled(ctx: Context): Boolean =
        enabledServices(ctx).any { ComponentName.unflattenFromString(it) == a11yComponent(ctx) }

    /**
     * Turn our accessibility service on (remote input) when we hold WRITE_SECURE_SETTINGS.
     * Other enabled services are kept. Re-run at every service start: a settings reset or
     * an update that briefly unbinds the service would otherwise leave input dead.
     */
    fun ensureAccessibility(ctx: Context) {
        if (!writeSecureSettings(ctx) || accessibilityEnabled(ctx)) return
        runCatching {
            val list = enabledServices(ctx) + a11yComponent(ctx).flattenToString()
            val cr = ctx.contentResolver
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "accessibility input service enabled")
        }.onFailure { Log.w(TAG, "enable accessibility failed: ${it.message}") }
    }

    private fun enabledServices(ctx: Context): List<String> =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }

    private fun a11yComponent(ctx: Context) = ComponentName(ctx, MdmAccessibilityService::class.java)

    private fun granted(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
}
