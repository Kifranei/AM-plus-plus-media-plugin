package dev.kifranei.ampp.media

import io.github.proify.lyricon.lyric.model.Song

/** Prevent late lyric completions from replacing a newer track; cache pre-metadata completions. */
internal class LyriconSongState(private val publish: (Song?) -> Unit) {
    private val songs = object : LinkedHashMap<String, Song>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?): Boolean = size > 16
    }
    private val installedIds = HashSet<String>()
    private var metadata: Song? = null
    private var sent: Song? = null
    private var auxiliary = LyriconAuxiliarySelection()
    val currentId: String? get() = metadata?.id
    val hasLyrics: Boolean get() = !metadata?.id?.let(songs::get)?.lyrics.isNullOrEmpty()

    fun auxiliary(selection: LyriconAuxiliarySelection) {
        if (selection == auxiliary) return
        auxiliary = selection
        sendCurrent()
    }

    fun metadata(song: Song?): Boolean {
        val changed = song?.id != metadata?.id
        metadata = song
        sendCurrent()
        return changed
    }
    fun lyrics(song: Song, installed: Boolean = false) {
        val id = song.id ?: return
        if (!installed && id in installedIds) return
        songs[id] = song.deepCopy()
        installedIds.retainAll(songs.keys)
        if (installed) installedIds += id
        if (id == currentId) sendCurrent()
    }
    private fun sendCurrent() {
        val base = metadata
        val lyrics = base?.id?.let(songs::get)
        val next = base?.copy(duration = base.duration.takeIf { it > 0 } ?: lyrics?.duration ?: 0L,
            lyrics = lyrics?.lyrics)?.let(auxiliary::apply)
        if (next != sent) { sent = next; publish(next) }
    }
}
