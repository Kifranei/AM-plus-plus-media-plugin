package dev.kifranei.ampp.media

import dev.kifranei.ampp.media.TabletPlayerContentWidth.Region
import dev.kifranei.ampp.media.TabletPlayerContentWidthGeometry.Axis
import dev.kifranei.ampp.media.TabletPlayerContentWidthGeometry.Padding
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class TabletPlayerContentWidthTest {
    private val geometry = TabletPlayerContentWidthGeometry
    @Test fun animatedCoverWidthDoesNotRelayoutTheControlsEveryFrame() {
        val settlement = TabletContentWidthSettlement()
        assertEquals(Region(80f, 400f), settlement.observe(Region(80f, 400f)))
        for (width in listOf(324f, 328f, 332f, 336f)) {
            assertEquals(320f, settlement.observe(Region(240 - width / 2, 240 + width / 2)).width, .001f)
        }
        assertEquals(336f, settlement.observe(Region(72f, 408f)).width, .001f)
        // Centering the column follows immediately, retaining its settled width.
        assertEquals(Region(372f, 708f), settlement.observe(Region(372f, 708f)))
        settlement.clear()
        assertEquals(Region(80f, 400f), settlement.observe(Region(80f, 400f)))
    }

    @Test fun nativePlaybackScaleAndAncestorScaleDefineTheVisualWidth() {
        // A 400px card scaled .8 around its center, inside a .9-scaled pane at x=100.
        val region = geometry.cover(400f, Axis(.8f * .9f, 100f + 40f * .9f), 1000f, 1f, true)!!
        assertEquals(136f, region.left, .001f)
        assertEquals(424f, region.right, .001f)
        assertEquals(288f, region.width, .001f)
    }

    @Test fun pauseAndResumeKeepMetadataProgressAndVolumeAtTheFullPlayingWidth() {
        // Native G0.P scales the card around its pivot from 1 to .85 when paused.
        // A separate .9 pane scale and a translated/centered column must remain intact.
        for (origin in listOf(100f, 460f)) {
            for (pauseScale in listOf(1f, .97f, .9f, .85f, .9f, 1f)) {
                val rendered = Axis(.9f * pauseScale, origin + .9f * 200f * (1f - pauseScale))
                val playing = checkNotNull(geometry.playingAxis(rendered, pauseScale, 200f))
                val region = checkNotNull(geometry.cover(400f, playing, 1000f, 1f, true))
                assertEquals(origin, region.left, .001f)
                assertEquals(origin + 360f, region.right, .001f)
                val controls = Axis(.9f, origin - 36f)
                assertEquals(Padding(40, 40), geometry.padding(region, controls, 480))
                val volume = checkNotNull(PlayerVolumeGeometry.column(region.left.roundToInt(), region.width.roundToInt(), 1000, 0))
                assertEquals(origin.toInt() to 360, volume)
            }
        }
    }

    @Test fun initiallyPausedCoverUsesThePlayingWidthWithoutWaitingForAPlaybackSample() {
        val rendered = Axis(.85f, 130f) // 400px card at 100, centered pivot 200.
        val playing = checkNotNull(geometry.playingAxis(rendered, .85f, 200f))
        assertEquals(100f, playing.offset, .001f)
        assertEquals(1f, playing.scale, .001f)
        assertEquals(Region(100f, 500f), geometry.cover(400f, playing, 1000f, 1f, true))
        assertNull(geometry.playingAxis(rendered, 0f, 200f))
        assertNull(geometry.playingAxis(rendered, Float.NaN, 200f))
        assertNull(geometry.playingAxis(rendered, .85f, Float.NaN))
    }

    @Test fun controlsUseTheSameBoundsDespiteTheirOwnAncestorTransform() {
        val region = Region(136f, 424f)
        assertEquals(Padding(40, 40), geometry.padding(region, Axis(.9f, 100f), 400))
        assertEquals(Padding(56, 96), geometry.padding(region, Axis(1f, 80f), 440))
    }

    @Test fun absolutePaddingReplacesTheNative32dpInsetWithoutAddingItAgain() {
        val region = Region(80f, 400f)
        repeat(100) { assertEquals(Padding(80, 80), geometry.padding(region, Axis(1f, 0f), 480)) }
        // Native restore and a new cover/pane use their new bounds, not the previous 80px delta.
        assertEquals(Padding(48, 48), geometry.padding(Region(48f, 432f), Axis(1f, 0f), 480))
    }

    @Test fun metadataUsesNativeGuidelineAnchorsAndKeepsTheMoreTouchPadding() {
        val region = Region(80f, 400f)
        assertEquals(48, geometry.startMargin(region, 32f, 0, false))
        assertEquals(40, geometry.startMargin(region, 32f, 8, false))
        // More's outer right edge is 432; its 32px padded slot leaves the icon at 400.
        assertEquals(16, geometry.endMargin(region, 448f, 32, false))
        assertEquals(400, 448 - geometry.endMargin(region, 448f, 32, false)!! - 32)
    }

    @Test fun rtlAlignsLogicalStartAndEndToThePhysicalCoverEdges() {
        val region = Region(80f, 400f)
        assertEquals(48, geometry.startMargin(region, 448f, 0, true))
        assertEquals(16, geometry.endMargin(region, 32f, 32, true))
        assertEquals(80, 32 + geometry.endMargin(region, 32f, 32, true)!! + 32)
    }

    @Test fun offsetAndPlaybackChangesNeverAccumulateOldMargins() {
        repeat(100) { assertEquals(48, geometry.startMargin(Region(80f, 400f), 32f, 0, false)) }
        assertEquals(16, geometry.startMargin(Region(48f, 432f), 32f, 0, false))
        assertEquals(-16, geometry.endMargin(Region(16f, 464f), 448f, 0, false))
    }

    @Test fun unstableSongCannotProduceARegion() {
        assertNull(geometry.cover(400f, Axis(1f, 0f), 480f, 1f, false))
    }

    @Test fun undersizedOrClippedCoverIsRejectedInsteadOfResized() {
        assertNotNull(geometry.cover(88f, Axis(1f, 0f), 480f, 2f, true))
        assertNull(geometry.cover(87f, Axis(1f, 0f), 480f, 2f, true))
        assertNull(geometry.cover(400f, Axis(1f, -1f), 480f, 1f, true))
        assertNull(geometry.cover(400f, Axis(1f, 81f), 480f, 1f, true))
    }

    @Test fun singularMirroredAndNonFiniteTransformsAreRejected() {
        for (axis in listOf(Axis(0f, 0f), Axis(-1f, 0f), Axis(Float.NaN, 0f),
            Axis(Float.POSITIVE_INFINITY, 0f), Axis(1f, Float.NaN))) {
            assertNull(geometry.cover(400f, axis, 1000f, 1f, true))
            assertNull(geometry.padding(Region(80f, 400f), axis, 480))
        }
        assertNull(geometry.cover(Float.NaN, Axis(1f, 0f), 1000f, 1f, true))
        assertNull(geometry.cover(400f, Axis(1f, 0f), 1000f, 0f, true))
    }

    @Test fun controlsOutsideTheCoverCannotUseNegativePaddingOrCollapseTheirContent() {
        assertNull(geometry.padding(Region(80f, 400f), Axis(1f, 100f), 480))
        assertNull(geometry.padding(Region(80f, 400f), Axis(1f, 0f), 399))
        assertNull(geometry.padding(Region(80f, 80f), Axis(1f, 0f), 480))
    }

    @Test fun fractionalMappingRoundsEachEdgeWithinHalfAPixel() {
        val region = Region(80.25f, 399.75f)
        assertEquals(Padding(80, 80), geometry.padding(region, Axis(1f, 0f), 480))
        assertEquals(48, geometry.startMargin(region, 32f, 0, false))
    }

    @Test fun nativeSeekbar32dpPaddingMustBeRemovedInsideTheAlignedControls() {
        val cover = Region(80f, 400f)
        val padding = geometry.padding(cover, Axis(1f, 0f), 480)!!
        // Keeping MinimalSeekBar's native 32px slots would shrink the 320px track to 256px.
        val withNativeSeekPadding = Region(padding.left + 32f, 480f - padding.right - 32f)
        assertEquals(256f, withNativeSeekPadding.width, 0f)
        val withZeroSeekPadding = Region(padding.left.toFloat(), 480f - padding.right)
        assertEquals(cover, withZeroSeekPadding)
    }

    @Test fun nestedSeekPaddingMustBeNeutralizedInLocalPixelsBeforeAncestorMapping() {
        val cover = Region(136f, 424f)
        val axis = Axis(.9f, 100f)
        val padding = geometry.padding(cover, axis, 400)!!
        // No track/container inset survives the adapter. Native .9 ancestor scale is retained.
        val track = Region(axis.offset + padding.left * axis.scale,
            axis.offset + (400 - padding.right) * axis.scale)
        assertEquals(cover.left, track.left, .001f)
        assertEquals(cover.right, track.right, .001f)
        val insetStillPresent = (32f + 8f) * axis.scale
        assertEquals(36f, insetStillPresent, .001f)
        assertEquals(216f, track.width - 2f * insetStillPresent, .001f)
    }

    @Test fun measuredNative72pxMarginCannotBeAddedToTheNewOuterInset() {
        // Observed native group [108,1172], progress [180,1100]: 72px per side (32dp).
        val cover = Region(180f, 1100f)
        val padding = geometry.padding(cover, Axis(1f, 108f), 1064)!!
        assertEquals(Padding(72, 72), padding)
        val retainedMargin = Region(108f + padding.left + 72f, 1172f - padding.right - 72f)
        assertEquals(776f, retainedMargin.width, 0f)
        val normalizedTrack = Region(108f + padding.left, 1172f - padding.right)
        assertEquals(cover, normalizedTrack)
    }

    @Test fun speakerSlotsStayInsideTheFullPlayingCoverWidthInExpandedAndCenteredColumns() {
        for (left in listOf(80, 380)) {
            val cover = Region(left.toFloat(), left + 320f)
            val row = checkNotNull(PlayerVolumeGeometry.column(cover.left.toInt(), cover.width.toInt(), 1000, 0))
            assertEquals(left to 320, row)
            // IosVolumeSliderView reserves 40px on each side inside this row.
            val track = Region(row.first + 40f, row.first + row.second - 40f)
            assertTrue(track.left > cover.left)
            assertTrue(track.right < cover.right)
            assertEquals(240f, track.width, 0f)
            // Its speaker paths and the louder arc remain inside the row's outer edges.
            assertTrue(row.first + 5f >= cover.left)
            assertTrue(row.first + row.second - .25f <= cover.right)
        }
    }
}
