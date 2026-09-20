package aio.app.mdmclient.dpc.device

/**
 * Whether a downloaded APK may replace the running agent — the decision on its own,
 * with no Android types in sight.
 *
 * [AgentUpdater] reads the facts off the device (package names, version codes, signing
 * certificates, the digest of the file) and this decides what they mean. Split out
 * because this is the part that must never be wrong: accepting a downgrade, a different
 * app, or a build signed with someone else's key is how an agent bricks itself, and none
 * of that is observable from a passing install. Keeping it free of PackageManager makes
 * it testable as plain JVM code.
 */
internal object UpdateCheck {

    /** null = safe to install; otherwise the reason, as the operator will read it. */
    fun reject(
        expectedSha256: String,
        actualSha256: String,
        apkPackage: String?,
        ourPackage: String,
        apkVersionCode: Long,
        apkVersionName: String?,
        installedVersionCode: Long,
        installedVersionName: String?,
        apkSigners: Set<String>,
        installedSigners: Set<String>,
    ): String? {
        if (expectedSha256.isNotBlank() && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
            return "checksum mismatch"
        }
        if (apkPackage.isNullOrBlank()) return "not a valid APK"
        if (apkPackage != ourPackage) return "APK is $apkPackage, not this agent ($ourPackage)"
        if (apkVersionCode <= installedVersionCode) {
            return "APK ${apkVersionName.orEmpty()} ($apkVersionCode) is not newer than " +
                "the installed ${installedVersionName.orEmpty()} ($installedVersionCode)"
        }
        // Android refuses a mismatched signature anyway; this fails early and says why.
        if (apkSigners.intersect(installedSigners).isEmpty()) {
            return "APK is signed with a different key"
        }
        return null
    }
}
