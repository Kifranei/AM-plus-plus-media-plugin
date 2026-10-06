package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class SharedCardActionTest {
    @Test fun `song and lyrics export actions stay distinct after renderer selection changes`() {
        val actions = SharedCardKind.entries.flatMap { kind -> listOf(SharedCardAction(kind, false), SharedCardAction(kind, true)) }
        assertEquals(4, actions.map { it.id }.toSet().size)
        actions.forEach { assertEquals(it, SharedCardAction.fromId(it.id)) }
        assertEquals(SharedCardKind.SONG, SharedCardAction.fromId("amppShareSongImage")?.kind)
        assertEquals(SharedCardKind.LYRICS, SharedCardAction.fromId("amppSaveLyricsImage")?.kind)
    }
    @Test fun `native share destinations and malformed IDs are never intercepted as image exports`() {
        listOf(null, "", "instagram", "whatsapp", "copyLink", "moreOptions", "amppShareSongImage-extra")
            .forEach { assertNull(SharedCardAction.fromId(it)) }
    }
}
