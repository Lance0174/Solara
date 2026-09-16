package io.github.akudamatata.solara

import io.github.akudamatata.solara.data.LocalPlaylist
import io.github.akudamatata.solara.data.LocalPlaylists
import io.github.akudamatata.solara.data.SOURCES
import io.github.akudamatata.solara.data.Song
import io.github.akudamatata.solara.data.SongLists
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalAudioImportTest {
    private val localUri = "content://com.android.providers.media.documents/document/audio%3A1234"
    private val localSong = Song.local("我的本地歌", listOf("本地歌手"), "本地专辑", localUri)

    @Test fun localSongUsesLocalSourceAndStableKey() {
        assertEquals("local", localSong.source)
        assertEquals("local:$localUri", localSong.key)
        assertEquals(localUri, localSong.localUri)
        assertEquals("我的本地歌", localSong.name)
        assertEquals("本地歌手", localSong.artist)
        assertEquals("本地专辑", localSong.album)
        assertEquals("本地音频", SOURCES[localSong.source])
    }

    @Test fun fromJsonAcceptsAnyContentUriAsLocalAudio() {
        // OpenDocument 可能返回 media 以外的 content:// 文件，均应视为本机可播放的本地音频。
        val json = localSong.toJson(includeLocal = true)
        val restored = Song.fromJson(json, allowLocal = true)
        assertEquals(localSong, restored)
        assertEquals(localUri, restored.localUri)
    }

    @Test fun fromJsonDoesNotTrustNonContentUrisForLocalAudio() {
        val json = localSong.copy(localUri = "http://example.com/evil.mp3").toJson(includeLocal = true)
        assertEquals("", Song.fromJson(json, allowLocal = true).localUri)
    }

    @Test fun localSongsRoundTripThroughLocalPlaylistPersistence() {
        val playlist = LocalPlaylist("local-only", "本地音乐", listOf(localSong))
        val restored = LocalPlaylists.decode(LocalPlaylists.encode(listOf(playlist))).single()
        assertEquals(playlist, restored)
        assertEquals(localUri, restored.songs.single().localUri)
    }

    @Test fun duplicateLocalImportsDoNotAddSameFileTwice() {
        val playlist = LocalPlaylist("p", "歌单", listOf(localSong))
        assertEquals(playlist, playlist.add(listOf(localSong)))
        assertEquals(1, playlist.songs.size)
    }

    @Test fun localSongsAreExcludedFromPortableExport() {
        // 导出与网页版兼容的便携 JSON 时，不携带本机私有文件地址。
        val text = SongLists.export(listOf(localSong), favorites = false)
        val parsed = SongLists.parse(text)
        assertEquals(1, parsed.size)
        assertTrue(parsed.first().localUri.isBlank())
        assertFalse(text.contains("content://"))
    }
}
