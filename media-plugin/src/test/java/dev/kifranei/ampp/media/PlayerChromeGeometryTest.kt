package dev.kifranei.ampp.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerChromeGeometryTest {
    @Test fun `mini player and invalid progress do not show a handle`() {
        for (progress in listOf(-1f, 0f, .5f, .75f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(0, PlayerChromeGeometry.handleAlpha(progress))
        }
    }
    @Test fun `handle fades reversibly near the expanded page`() {
        assertEquals(75, PlayerChromeGeometry.handleAlpha(.875f))
        assertEquals(150, PlayerChromeGeometry.handleAlpha(1f))
        assertEquals(150, PlayerChromeGeometry.handleAlpha(2f))
        assertEquals(75, PlayerChromeGeometry.handleAlpha(.875f))
        assertEquals(0, PlayerChromeGeometry.handleAlpha(0f))
    }

    @Test fun `fullscreen cover suppresses the handle until normal cover returns`() {
        assertEquals(150, PlayerChromeGeometry.handleAlpha(1f, false))
        for (progress in listOf(0f, .875f, 1f)) {
            assertEquals(0, PlayerChromeGeometry.handleAlpha(progress, true))
        }
        assertEquals(150, PlayerChromeGeometry.handleAlpha(1f, false))
    }

    @Test fun `native corner interpolation preserves mini radius in both directions`() {
        assertEquals(26f, PlayerChromeGeometry.motionRadius(156f, 26f, 1f), 0f)
        assertEquals(91f, PlayerChromeGeometry.motionRadius(156f, 26f, .5f), 0f)
        assertEquals(156f, PlayerChromeGeometry.motionRadius(156f, 26f, 0f), 0f)
        assertEquals(91f, PlayerChromeGeometry.motionRadius(156f, 26f, .5f), 0f)
        assertEquals(26f, PlayerChromeGeometry.motionRadius(156f, 26f, 1f), 0f)
    }
}
