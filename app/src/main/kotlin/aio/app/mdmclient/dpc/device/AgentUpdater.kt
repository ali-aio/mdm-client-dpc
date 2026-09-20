package aio.app.mdmclient.dpc.device

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.Log
import aio.app.mdmclient.dpc.AgentConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * OTA for the agent itself: install a newer build of this app over the running one.
 *
 * As Device Owner the install is always silent — no prompt, no "install unknown apps"
 * grant, no adb (that is the difference from MDM-lite's updater, which has to fall back
 * to Android's prompt). The APK is checked before anything is committed: same package,
 * same signing key, a higher version, and the expected SHA-256 when the server sends one.
 *
 * Committing the session kills this process, so the command cannot be acknowledged here.
 * The pending update is written down first and settled by the *new* version at its next
 * start ([settlePending]), which compares the running version with the target.
 */
object AgentUpdater {

    private const val TAG = "AioMdmDpc"

    /** Reports a terminal status for the update command. */
    fun interface Ack {
        fun send(status: String, output: String)
    }

    /**
     * Download, verify and install. Runs on an IO thread. A failure is reported straight
     * away; success is reported by the version that replaces this one.
     */
    fun run(
        ctx: Context,
        installer: ApkInstaller,
        id: String,
        apkUrl: String,
        payload: JSONObject,
        onProgress: (Int) -> Unit,
        ack: Ack,
    ) {
        if (apkUrl.isBlank()) return ack.send("failed", "missing apk_url")
        val apk = File(ctx.cacheDir, "agent_update.apk")
        try {
            installer.downloadTo(apkUrl, apk, onProgress)
        } catch (e: Exception) {
            apk.delete()
            return ack.send("failed", "download failed: ${e.message}")
        }
        verify(ctx, apk, payload.optString("sha256"))?.let {
            apk.delete()
            return ack.send("failed", it)
        }

        val info = archiveInfo(ctx, apk)!!
        val config = AgentConfig.get(ctx)
        config.pendingUpdate = "$id|${info.longVersionCode}|${info.versionName.orEmpty()}"
        Log.i(TAG, "self-update $id -> ${info.versionName} (${info.longVersionCode})")
        try {
            // Succeeds by never returning: the install replaces this process.
            val result = installer.commitInstall(apk)
            if (!result.success) {
                config.pendingUpdate = ""
                ack.send("failed", "install failed: ${result.message}")
            }
        } catch (e: Exception) {
            config.pendingUpdate = ""
            ack.send("failed", "install failed: ${e.message}")
        } finally {
            apk.delete()
        }
    }

    /**
     * At startup: settle an update this agent asked for before it was replaced. Running the
     * target version (or newer) means the install went in. Anything else means it did not,
     * and the operator gets told rather than watching the command hang.
     */
    fun settlePending(ctx: Context, ack: (id: String, status: String, output: String) -> Unit) {
        val config = AgentConfig.get(ctx)
        val pending = config.pendingUpdate.takeIf { it.isNotBlank() } ?: return
        val parts = pending.split("|")
        val id = parts.firstOrNull().orEmpty()
        if (id.isBlank()) {
            config.pendingUpdate = ""
            return
        }
        val target = parts.getOrNull(1)?.toLongOrNull() ?: 0
        val name = parts.getOrNull(2).orEmpty()
        val now = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        }.getOrDefault(0)
        config.pendingUpdate = ""
        File(ctx.cacheDir, "agent_update.apk").delete()
        if (now >= target) {
            ack(id, "completed", "updated to ${name.ifBlank { target.toString() }} ($now)")
        } else {
            ack(id, "failed", "still running $now after installing $target")
        }
    }

    /** True when [pkg] is this app — an install of it is a self-update, not an app install. */
    fun isSelf(ctx: Context, pkg: String?): Boolean = !pkg.isNullOrBlank() && pkg == ctx.packageName

    /** null = good to install, else the reason it was rejected. */
    private fun verify(ctx: Context, apk: File, sha256: String): String? {
        if (sha256.isNotBlank()) {
            val got = MessageDigest.getInstance("SHA-256").digest(apk.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (!got.equals(sha256, ignoreCase = true)) return "checksum mismatch"
        }
        val info = archiveInfo(ctx, apk) ?: return "not a valid APK"
        if (info.packageName != ctx.packageName) {
            return "APK is ${info.packageName}, not this agent (${ctx.packageName})"
        }
        val cur = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (info.longVersionCode <= cur.longVersionCode) {
            return "APK ${info.versionName} (${info.longVersionCode}) is not newer than " +
                "the installed ${cur.versionName} (${cur.longVersionCode})"
        }
        // Android refuses a mismatched signature anyway; this fails early and says why.
        if (signers(info).intersect(signers(cur)).isEmpty()) return "APK is signed with a different key"
        return null
    }

    private fun archiveInfo(ctx: Context, apk: File): PackageInfo? =
        ctx.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)

    /** Every certificate the package is signed with, including its rotation lineage. */
    private fun signers(p: PackageInfo): Set<String> {
        val si = p.signingInfo ?: return emptySet()
        val all: List<Signature> =
            si.apkContentsSigners?.toList().orEmpty() + si.signingCertificateHistory?.toList().orEmpty()
        return all.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
