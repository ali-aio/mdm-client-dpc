package aio.app.mdmclient.dpc.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import aio.app.mdmclient.dpc.AgentConfig
import aio.app.mdmclient.dpc.DeviceIdentity
import aio.app.mdmclient.dpc.net.ApiClient
import org.json.JSONObject
import java.io.File

/**
 * Save to library (0.11.0, pull_apk): upload an installed app's APK into the MDM's app
 * library, by hand from the device page or when the server sees this device run a newer
 * build of one of our apps than the library holds.
 *
 * The APK is the app's own sourceDir, which any app can read. The server hands out a
 * presigned S3 URL for this one command, the file goes there directly, and the server
 * then reads it and adds it to the library (it checks the package and that the APK is
 * signed like the versions it already has). An app installed as split APKs (an app
 * bundle) can't be saved this way: the library holds single APKs.
 */
class ApkPuller(private val ctx: Context, config: AgentConfig) {

    class Result(val ok: Boolean, val message: String)

    private val api = ApiClient(config)

    fun pull(commandId: String, pkg: String, onProgress: (Int) -> Unit): Result {
        if (pkg.isBlank()) return Result(false, "no package named")
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") ctx.packageManager.getApplicationInfo(pkg, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return Result(false, "$pkg is not installed")
        }
        if (!info.splitSourceDirs.isNullOrEmpty()) {
            return Result(false, "$pkg is installed as split APKs (an app bundle); only a single APK can be saved")
        }
        val file = File(info.sourceDir ?: "")
        if (!file.canRead()) return Result(false, "can't read the APK of $pkg")

        val serial = DeviceIdentity.serial(ctx)
        val ask = JSONObject().put("serial_number", serial).put("command_id", commandId)
        val url = api.postForAnswer("/api/v1/apk-pull/url", ask)
        val target = url.body ?: return Result(false, "no upload URL: ${url.error}")
        val put = api.putFile(target.optString("url"), file,
            target.optString("content_type").ifBlank { "application/vnd.android.package-archive" }, onProgress)
        if (put.isNotEmpty()) return Result(false, put)
        // Reading and checking the upload takes the server a moment: wait longer than usual.
        val done = api.postForAnswer("/api/v1/apk-pull/done", ask.put("key", target.optString("key")), readSeconds = 180)
        val body = done.body ?: return Result(false, done.error)
        return Result(true, body.optString("result").ifBlank { "saved" })
    }
}
