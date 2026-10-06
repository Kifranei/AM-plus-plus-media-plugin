package dev.kifranei.ampp.media

internal data class PlayerMenuAction(val key: String, val index: Int)
internal data class PlayerMenuGroups(val shortcuts: List<PlayerMenuAction?>, val sections: List<List<PlayerMenuAction>>)
internal data class PlayerMenuBounds(val left: Int, val top: Int, val width: Int, val height: Int)
internal data class PlayerMenuInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Arrange only actions supplied by AM; an unavailable shortcut never invents a command. */
internal object PlayerMenuLayout {
    fun groups(actions: List<PlayerMenuAction>): PlayerMenuGroups {
        fun first(vararg keys: String) = keys.firstNotNullOfOrNull { key -> actions.firstOrNull { it.key == key } }
        val download = first("DOWNLOAD", "REMOVE_DOWNLOAD", "ADD_TO_LIBRARY")
        val favorite = first("FAVORITE_ITEM", "UNDO_FAVORITE_ITEM", "LOVE", "PLAY_MORE_LIKE_THIS")
        val share = first("SHARE_SONG", "SHARE_VIDEO", "SHARE_STATION") ?: actions.firstOrNull {
            it.key.startsWith("SHARE_") && it.key != "SHARE_LYRICS"
        }
        val quick = listOf(download, favorite, share)
        val remaining = actions.filter { it !in quick }
        fun group(key: String) = when {
            key.startsWith("PIN_") || key.startsWith("UNPIN_") || key.startsWith("ADD_TO_") -> 0
            key == "CREATE_STATION" -> 1
            key in setOf("OPEN_ALBUM", "OPEN_ARTIST", "SHOW_ARTIST", "VIEW_SONG_CREDITS", "SHARE_LYRICS", "LOAD_AND_SHARE_LYRICS") -> 2
            key in setOf("SUGGEST_LESS", "UNDO_SUGGEST_LESS", "DISLIKE", "PLAY_LESS_LIKE_THIS", "DELETE_FROM_LIBRARY", "REMOVE_DOWNLOAD") -> 4
            else -> 3
        }
        return PlayerMenuGroups(quick, (0..4).map { section -> remaining.filter { group(it.key) == section } }.filter { it.isNotEmpty() })
    }

    /** Intersect window system bars with the dialog content, including offset windows. */
    fun localInsets(root: PlayerMenuBounds, window: PlayerMenuBounds, insets: PlayerMenuInsets): PlayerMenuInsets =
        PlayerMenuInsets(
            (window.left + insets.left - root.left).coerceIn(0, root.width.coerceAtLeast(0)),
            (window.top + insets.top - root.top).coerceIn(0, root.height.coerceAtLeast(0)),
            (root.left + root.width - (window.left + window.width - insets.right)).coerceIn(0, root.width.coerceAtLeast(0)),
            (root.top + root.height - (window.top + window.height - insets.bottom)).coerceIn(0, root.height.coerceAtLeast(0)),
        )

    /** Keep the original upper-right card position independent of native player animations. */
    fun bounds(width: Int, height: Int, density: Float, topInset: Int, bottomInset: Int,
        contentHeight: Int, leftInset: Int = 0, rightInset: Int = 0): PlayerMenuBounds {
        val viewportWidth = width.coerceAtLeast(1)
        val viewportHeight = height.coerceAtLeast(1)
        val scale = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        fun dp(value: Int) = (value * scale).toInt()
        val safeLeft = leftInset.coerceIn(0, viewportWidth - 1)
        val safeRight = rightInset.coerceIn(0, viewportWidth - safeLeft - 1)
        val safeTop = topInset.coerceIn(0, viewportHeight - 1)
        val safeBottom = bottomInset.coerceIn(0, viewportHeight - safeTop - 1)
        val safeWidth = viewportWidth - safeLeft - safeRight
        val safeHeight = viewportHeight - safeTop - safeBottom
        val margin = dp(24).coerceAtMost(safeWidth / 8)
        val menuWidth = minOf(dp(340), (safeWidth * .68f).toInt()).coerceAtLeast(1)
            .coerceAtMost((safeWidth - margin * 2).coerceAtLeast(1))
        val minimumTop = safeTop + dp(12).coerceAtMost(safeHeight / 8)
        val maximumBottom = viewportHeight - safeBottom - dp(24).coerceAtMost(safeHeight / 8)
        val maxHeight = minOf(dp(620), (viewportHeight * .60f).toInt(), maximumBottom - minimumTop)
        val menuHeight = contentHeight.coerceIn(1, maxHeight.coerceAtLeast(1))
        return PlayerMenuBounds(viewportWidth - safeRight - margin - menuWidth, minimumTop, menuWidth, menuHeight)
    }
}
