package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class CardSharingDexResolverTest {
    @Test fun `uniqueOrNull refuses empty and ambiguous sets`() {
        assertNull(CardSharingDexResolver.uniqueOrNull(emptyList()))
        assertNull(CardSharingDexResolver.uniqueOrNull(listOf("a", "b")))
        assertEquals("a", CardSharingDexResolver.uniqueOrNull(listOf("a", "a")))
        assertEquals("m", CardSharingDexResolver.uniqueOrNull(listOf("m")))
    }

    @Test fun `pickProgressFieldName prefers profile name then typed progress then sole Object`() {
        class Progress
        class Other
        val progress = Progress::class.java
        val fields = listOf(
            "a" to Integer.TYPE,
            "b" to Any::class.java,
            "c" to Other::class.java,
        )
        assertEquals("b", CardSharingDexResolver.pickProgressFieldName(fields, progress, "b"))
        assertEquals(
            "owner",
            CardSharingDexResolver.pickProgressFieldName(
                listOf("a" to Integer.TYPE, "owner" to progress),
                progress,
                "missing",
            ),
        )
        assertEquals(
            "b",
            CardSharingDexResolver.pickProgressFieldName(
                listOf("a" to Integer.TYPE, "b" to Any::class.java),
                progress,
                null,
            ),
        )
        assertNull(
            CardSharingDexResolver.pickProgressFieldName(
                listOf("a" to Integer.TYPE, "b" to Any::class.java, "c" to Any::class.java),
                progress,
                null,
            ),
        )
    }
}
