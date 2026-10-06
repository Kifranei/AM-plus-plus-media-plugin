package dev.kifranei.ampp.media

import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class PlayerVolumePanePreparationTest {
    private fun rows(transport: Int, action: Int = 28) = listOf(
        PlayerVolumePanePreparation.Row(1, -1, -2, 0, 0, -1),
        PlayerVolumePanePreparation.Row(2, 66, transport, 11, 11, 30),
        PlayerVolumePanePreparation.Row(3, -2, -2, 16, 16, action)
    )

    @Test fun `phone shared targets have their final bounds on the first incoming layout across DPI and panes`() {
        data class Capture(val density: Float, val progress: Int, val transport: Int, val action: Int, val bottom: Int)
        listOf(Capture(2.75f, 139, 182, 165, 39), Capture(3.3125f, 163, 219, 199, 46),
            Capture(4f, 200, 264, 240, 56)).forEach { capture ->
            fun px(dp: Int) = (dp * capture.density).roundToInt()
            val required = PlayerVolumeGeometry.controlsHeight(capture.progress, capture.transport,
                capture.action, capture.bottom, px(44), px(8))
            // Both a short percentage-height column and a column already taller than
            // the minimum must keep identical shared-element bounds across the switch.
            listOf(px(220), px(370)).forEach { nativeHeight ->
                val cache = PlayerVolumePanePreparation()
                val window = PlayerVolumePanePreparation.Window(px(400), px(800), "density=${capture.density}")
                val signature = rows(capture.transport)
                cache.remember(window, signature, required) // Completed outgoing native rows.
                fun transportTop(height: Int, offset: Int): Int {
                    val actionTop = height - capture.bottom - capture.action
                    return capture.progress + (actionTop - capture.progress - capture.transport) / 2 + offset
                }
                repeat(12) { switch ->
                    val incomingMinimum = PlayerVolumeMinimum(0)
                    val spacing = PlayerVolumeSpacing(0)
                    val prepared = incomingMinimum.update(0, checkNotNull(cache.required(window, signature)))
                    val margin = spacing.update(0, -px(24))
                    val firstHeight = maxOf(nativeHeight, prepared)
                    val firstTop = transportTop(firstHeight, spacing.offset(margin).roundToInt())
                    // Completion measures the same native rows, rather than expanding
                    // the control stack again after the transition has captured it.
                    val completeHeight = maxOf(nativeHeight, incomingMinimum.update(prepared, required))
                    val actionTop = completeHeight - capture.bottom - capture.action
                    val nativeTop = transportTop(completeHeight, 0)
                    val shift = checkNotNull(PlayerVolumeGeometry.transportShift(nativeTop, nativeTop + capture.transport,
                        capture.progress, actionTop, px(44), px(8), px(24)))
                    val completeMargin = spacing.update(margin, shift)
                    assertEquals("switch $switch at ${capture.density}", margin, completeMargin)
                    assertEquals(firstTop, transportTop(completeHeight, spacing.offset(completeMargin).roundToInt()))
                    assertEquals(-px(24), shift)
                    assertNotNull(PlayerVolumeGeometry.top(nativeTop + capture.transport + shift, actionTop, px(44), px(8)))
                }
            }
        }
    }

    @Test fun `rotation window resize DPI and font changes cannot seed a new pane with old pixels`() {
        val cache = PlayerVolumePanePreparation()
        val window = PlayerVolumePanePreparation.Window(1220, 2712, "530 font=1 portrait")
        val signature = rows(219)
        listOf(window.copy(width = 2712, height = 1220), window.copy(width = 610),
            window.copy(configuration = "440 font=1 portrait"),
            window.copy(configuration = "530 font=1.3 portrait")).forEach { changed ->
            cache.remember(window, signature, 881)
            assertNull(cache.required(changed, signature))
            assertNull(cache.required(window, signature)) // Returning requires a fresh completed layout too.
        }
    }

    @Test fun `different native radio or action row sizes wait for their own measurements`() {
        val cache = PlayerVolumePanePreparation()
        val window = PlayerVolumePanePreparation.Window(1220, 2712, "530 font=1")
        cache.remember(window, rows(219), 881)
        assertNull(cache.required(window, rows(219, action = 40)))
        assertNull(cache.required(window, rows(182)))
        assertNull(cache.required(window, rows(219).map { if (it.id == 1) it.copy(id = 4) else it }))
        assertEquals(881, cache.required(window, rows(219)))
    }

    @Test fun `unmeasured initial window or invalid row stack never creates a reservation`() {
        val cache = PlayerVolumePanePreparation()
        val initial = PlayerVolumePanePreparation.Window(0, 0, "530")
        cache.remember(initial, rows(219), 881)
        assertNull(cache.required(initial, rows(219)))
        val measured = initial.copy(width = 1220, height = 2712)
        cache.remember(measured, rows(219), 0)
        cache.remember(measured, emptyList(), 881)
        assertNull(cache.required(measured, rows(219)))
        assertNull(cache.required(measured, emptyList()))
    }

    @Test fun `initial minimum and spacing layouts settle before drawing and native animation cannot starve frames`() {
        val gate = PlayerVolumeDrawSettlement()
        assertFalse(gate.allow(true)) // Native minimum requests a layout.
        assertFalse(gate.allow(true)) // Spacing resolves from the new bounds.
        assertTrue(gate.allow(false)) // Final geometry is drawable.
        repeat(120) { assertTrue(gate.allow(false)) } // Normal animation frames are unaffected.
        assertFalse(gate.allow(true))
        assertFalse(gate.allow(true))
        repeat(120) { assertTrue(gate.allow(true)) } // A competing native writer cannot freeze AM.
        assertTrue(gate.allow(false))
        assertFalse(gate.allow(true)) // A later independent change can settle again.
    }
}
