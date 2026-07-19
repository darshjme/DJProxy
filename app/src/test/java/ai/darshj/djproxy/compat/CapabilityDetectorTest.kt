package ai.darshj.djproxy.compat

import ai.darshj.djproxy.compat.CapabilityDetector.Fingerprints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityDetectorTest {

    private fun fp(
        fingerprint: String = "samsung/q6acomm/q6acomm:16/BP2A.250605.031.A2/S928BXXU9BYE1:user/release-keys",
        hardware: String = "qcom",
        product: String = "q6acomm",
        model: String = "SM-S928B",
        manufacturer: String = "samsung",
        brand: String = "samsung",
        device: String = "q6a",
    ) = Fingerprints(fingerprint, hardware, product, model, manufacturer, brand, device)

    // ---- emulator vendor detection ---------------------------------------------------------------

    @Test
    fun `real Galaxy device is not an emulator`() {
        val f = fp()
        assertNull(CapabilityDetector.emulatorName(f))
    }

    @Test
    fun `Android Studio AVD via goldfish hardware is detected`() {
        val f = fp(
            fingerprint = "google/sdk_gphone64_x86_64/emulator64_x86_64:14/UE1A.230829.036.A1/test-keys",
            hardware = "ranchu",
            product = "sdk_gphone64_x86_64",
            model = "sdk_gphone64_x86_64",
            manufacturer = "Google",
            brand = "google",
            device = "emulator64_x86_64",
        )
        assertEquals("Android Studio AVD", CapabilityDetector.emulatorName(f))
    }

    @Test
    fun `legacy goldfish AVD is detected`() {
        val f = fp(hardware = "goldfish", product = "sdk", brand = "generic", device = "generic")
        assertEquals("Android Studio AVD", CapabilityDetector.emulatorName(f))
    }

    @Test
    fun `LDPlayer is detected and takes priority over generic tokens`() {
        val f = fp(
            manufacturer = "LDPlayer",
            model = "LDPlayer9",
            product = "ldplayer64_x86_64",
            hardware = "ranchu",
        )
        assertEquals("LDPlayer", CapabilityDetector.emulatorName(f))
    }

    @Test
    fun `BlueStacks is detected`() {
        val f = fp(manufacturer = "Bluestacks", model = "BlueStacks 5", product = "bst_x86")
        assertEquals("BlueStacks", CapabilityDetector.emulatorName(f))
    }

    @Test
    fun `Nox Player is detected`() {
        val f = fp(product = "NoxPlayer64", model = "NOX")
        assertEquals("Nox Player", CapabilityDetector.emulatorName(f))
    }

    @Test
    fun `Genymotion is detected`() {
        val f = fp(manufacturer = "Genymotion", hardware = "vbox86", product = "vbox86p")
        assertEquals("Genymotion", CapabilityDetector.emulatorName(f))
    }

    @Test
    fun `bare vbox hardware without genymotion token still reports a vendor`() {
        val f = fp(manufacturer = "unknown", hardware = "vbox86", product = "vbox86p")
        assertEquals("Genymotion/VirtualBox", CapabilityDetector.emulatorName(f))
    }

    @Test
    fun `isEmulator mirrors emulatorName null-ness`() {
        assertFalse(CapabilityDetector.emulatorName(fp()) != null)
    }

    // ---- root detection ---------------------------------------------------------------------------

    @Test
    fun `no su path and no test-keys is not rooted`() {
        assertFalse(
            CapabilityDetector.isRooted(
                listOf("/definitely/not/a/real/path/su"),
                "release-keys",
            ),
        )
    }

    @Test
    fun `test-keys build tag is treated as rooted`() {
        assertTrue(CapabilityDetector.isRooted(emptyList(), "test-keys"))
    }

    @Test
    fun `an existing su-like path is treated as rooted`() {
        val tmp = java.io.File.createTempFile("su_", null)
        try {
            assertTrue(CapabilityDetector.isRooted(listOf(tmp.absolutePath), "release-keys"))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `null build tags do not throw and are not rooted alone`() {
        assertFalse(CapabilityDetector.isRooted(listOf("/nope"), null))
    }

    // ---- VPN-bypass heuristic -----------------------------------------------------------------------

    @Test
    fun `bypass suspected only when control net works but in-tun probe fails`() {
        assertTrue(CapabilityDetector.suspectVpnBypass(inTunOk = false, controlNetOk = true))
        assertFalse(CapabilityDetector.suspectVpnBypass(inTunOk = true, controlNetOk = true))
        assertFalse(CapabilityDetector.suspectVpnBypass(inTunOk = false, controlNetOk = false))
        assertFalse(CapabilityDetector.suspectVpnBypass(inTunOk = true, controlNetOk = false))
    }

    // ---- version --------------------------------------------------------------------------------

    @Test
    fun `apiLevel never throws`() {
        // Under plain JUnit (no Robolectric) android.os.Build.VERSION.SDK_INT is stubbed to 0 by
        // isReturnDefaultValues; this test only guards against the call throwing.
        CapabilityDetector.apiLevel()
    }
}
