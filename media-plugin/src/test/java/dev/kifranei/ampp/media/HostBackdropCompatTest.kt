package dev.kifranei.ampp.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HostBackdropCompatTest {
    // Kotlin `$default` masks index declared parameters; the extension receiver is not counted.
    @Test fun newHostMaskKeepsHighlightShadowDefaultsAndExplicitSurface() {
        // 15 params: Modifier, backdrop, shape, effects, highlight, shadow, innerShadow, layerBlock, clip,
        // exported, minLayerSize, onDrawBehind, onDrawBackdrop, onDrawSurface(13), onDrawFront
        val mask = HostBackdropCompat.defaultMask(15, 13)
        assertEquals(0x2FF8, mask)
        assertEquals(8, mask and 8)            // highlight uses the host default
        assertEquals(0, mask and (1 shl 12))   // onDrawSurface is passed explicitly
        assertEquals(0, mask and 7)            // backdrop/shape/effects explicit
    }

    @Test fun noHighlightMaskOnlyClearsHighlightBit() {
        assertEquals(0x2FF8 and 8.inv(), HostBackdropCompat.defaultMask(15, 13, hostHighlight = false))
        assertEquals(0x17F8 and 8.inv(), HostBackdropCompat.defaultMask(14, 12, hostHighlight = false))
        assertEquals(0x2FF8, HostBackdropCompat.defaultMask(15, 13, hostHighlight = true))
    }

    @Test fun oldHostMask() = assertEquals(0x17F8, HostBackdropCompat.defaultMask(14, 12))

    @Test fun bundledHostJarSignatureMatchesMask() {
        val owner = Class.forName("com.kyant.backdrop.DrawBackdropModifierKt")
        val m = owner.declaredMethods.first { it.name.startsWith("drawBackdrop-") && it.name.endsWith("\$default") }
        assertNotNull(m)
        val size = m.parameterTypes.size - 2
        // The surface lambda is the second-to-last Function1 before onDrawFront.
        val surface = size - 2
        assertEquals(m.parameterTypes[surface], kotlin.jvm.functions.Function1::class.java)
        assertEquals(0, HostBackdropCompat.defaultMask(size, surface) and (1 shl (surface - 1)))
    }
}
