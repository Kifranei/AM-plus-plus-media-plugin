package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class OplusRomDetectorTest {
    private fun oplus(manufacturer: String? = null, brand: String? = null, vararg props: Pair<String, String?>) =
        OplusRomDetector.isOplus(manufacturer, brand, mapOf(*props))

    @Test fun `vendor names match case-insensitively on manufacturer or brand`() {
        assertTrue(oplus("OPPO", "oppo"))
        assertTrue(oplus("realme", null))
        assertTrue(oplus("Xiaomi", "REALME"))
        assertTrue(oplus("OnePlus", "OnePlus"))
        assertTrue(oplus("ONEPLUS"))
        assertTrue(oplus(" One Plus "))
    }
    @Test fun `other vendors are not affected`() {
        assertFalse(oplus("Xiaomi", "Redmi"))
        assertFalse(oplus("samsung", "samsung", "ro.build.display.id" to "UP1A.231005"))
        assertFalse(oplus("Google", "google", "ro.rom.version" to "", "ro.build.version.opporom" to " "))
        assertFalse(oplus(null, null))
        assertFalse(oplus("OPPOSITE", "realmes"))
    }
    @Test fun `rom properties identify rebranded devices`() {
        assertTrue(oplus("unknown", "unknown", "ro.build.version.opporom" to "V13.0"))
        assertTrue(oplus("x", "y", "ro.build.version.oplusrom" to "V14.0.0"))
        assertTrue(oplus("x", "y", "ro.build.version.realmeui" to "V5.0"))
        assertTrue(oplus("x", "y", "ro.build.version.realme" to "5.0"))
        assertTrue(oplus("x", "y", "ro.oxygen.version" to "14.0"))
        assertTrue(oplus("x", "y", "ro.build.ota.versionname" to "OxygenOS 14.0"))
        assertTrue(oplus("x", "y", "ro.rom.version" to "ColorOS V13"))
        assertTrue(oplus("x", "y", "ro.rom.version" to "realme UI 4.0"))
        assertFalse(oplus("x", "y", "ro.build.ota.versionname" to "HyperOS 1.0", "ro.rom.version" to "MIUI 14"))
    }
}
