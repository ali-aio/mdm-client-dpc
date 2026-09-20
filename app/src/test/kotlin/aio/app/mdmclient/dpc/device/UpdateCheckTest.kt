package aio.app.mdmclient.dpc.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent replaces itself on the strength of this decision, and a wrong "yes" is the
 * expensive kind: the install succeeds and the device is left running someone else's
 * build, or an older one with the bug you were fixing. Each case below is a way that
 * could happen.
 */
class UpdateCheckTest {

    private val ours = "aio.app.mdmclient.dpc"
    private val key = setOf("aa11")

    private fun reject(
        expectedSha: String = "",
        actualSha: String = "",
        pkg: String? = ours,
        apkCode: Long = 9,
        installedCode: Long = 8,
        apkSigners: Set<String> = key,
        installedSigners: Set<String> = key,
    ) = UpdateCheck.reject(
        expectedSha256 = expectedSha,
        actualSha256 = actualSha,
        apkPackage = pkg,
        ourPackage = ours,
        apkVersionCode = apkCode,
        apkVersionName = "0.2.2",
        installedVersionCode = installedCode,
        installedVersionName = "0.2.1",
        apkSigners = apkSigners,
        installedSigners = installedSigners,
    )

    @Test
    fun `a newer build of the same app signed with the same key is accepted`() {
        assertNull(reject())
    }

    @Test
    fun `a digest that does not match the server is refused`() {
        assertEquals("checksum mismatch", reject(expectedSha = "abc", actualSha = "def"))
    }

    @Test
    fun `the digest comparison ignores case`() {
        assertNull(reject(expectedSha = "ABCDEF", actualSha = "abcdef"))
    }

    @Test
    fun `no digest from the server means the other checks still decide`() {
        assertNull(reject(expectedSha = "", actualSha = ""))
        assertTrue(reject(expectedSha = "", actualSha = "", apkCode = 8)!!.contains("not newer"))
    }

    @Test
    fun `a file that does not parse as an APK is refused`() {
        assertEquals("not a valid APK", reject(pkg = null))
        assertEquals("not a valid APK", reject(pkg = ""))
    }

    @Test
    fun `another app is refused, and the message names it`() {
        val why = reject(pkg = "com.example.other")
        assertTrue(why!!.contains("com.example.other"))
        assertTrue(why.contains(ours))
    }

    @Test
    fun `the same version is not an upgrade`() {
        assertTrue(reject(apkCode = 8, installedCode = 8)!!.contains("not newer"))
    }

    @Test
    fun `a downgrade is refused`() {
        assertTrue(reject(apkCode = 7, installedCode = 8)!!.contains("not newer"))
    }

    @Test
    fun `a build signed with a different key is refused`() {
        assertEquals(
            "APK is signed with a different key",
            reject(apkSigners = setOf("bb22"), installedSigners = setOf("aa11")),
        )
    }

    @Test
    fun `a key that appears anywhere in the signing history is accepted`() {
        // Signing certificates rotate; the new APK need only share one with the installed
        // app, which is what Android itself accepts.
        assertNull(reject(apkSigners = setOf("bb22", "aa11"), installedSigners = setOf("aa11")))
    }

    @Test
    fun `an unsigned APK is refused rather than treated as matching`() {
        assertEquals(
            "APK is signed with a different key",
            reject(apkSigners = emptySet(), installedSigners = key),
        )
    }

    @Test
    fun `the wrong package is refused before the version is considered`() {
        // Order matters: "not newer" about a different app would send whoever reads it
        // looking for the wrong problem.
        val why = reject(pkg = "com.example.other", apkCode = 1, installedCode = 8)
        assertTrue(why!!.contains("com.example.other"))
    }
}
