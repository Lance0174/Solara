package io.github.akudamatata.solara

import io.github.akudamatata.solara.data.LocalPlaylist
import io.github.akudamatata.solara.data.LocalPlaylists
import io.github.akudamatata.solara.data.Song
import io.github.akudamatata.solara.data.SongLists
import org.junit.Assert.*
import org.junit.Test

class LocalPlaylistTest {
    private val first = Song("1", "第一首", listOf("A"))
    private val second = Song("2", "第二首", listOf("B"))
    private val local = first.copy(localUri = "content://media/external/audio/media/7")

    @Test fun duplicateAddsKeepOrderAndPreserveDownloadedAudio() {
        val playlist = LocalPlaylist("commute", "通勤", listOf(first, second))
        val result = playlist.add(listOf(local, first.copy(source = "kuwo")))
        assertEquals(listOf(local, second, first.copy(source = "kuwo")), result.songs)
        assertEquals(result, result.add(listOf(first)))
        assertEquals(listOf(first, second), playlist.songs)
    }

    @Test fun reorderAndRemovalDoNotAffectAnotherPlaylist() {
        val firstList = LocalPlaylist("one", "通勤", listOf(first, second))
        val secondList = firstList.copy(id = "two", name = "收藏片段")
        assertEquals(listOf(second, first), firstList.move(second.key, -1).songs)
        assertEquals(listOf(first, second), secondList.songs)
        assertEquals(listOf(second), firstList.remove(first.key).songs)
        assertEquals(firstList, firstList.move(first.key, -1))
        assertEquals(firstList, firstList.move(second.key, 1))
    }

    @Test fun namesRejectWhitespaceDuplicatesAndAllowRenamingSelf() {
        val playlists = listOf(LocalPlaylist("one", "Commute"))
        assertNotNull(LocalPlaylists.nameError("  ", playlists))
        assertNotNull(LocalPlaylists.nameError(" commute ", playlists))
        assertNotNull(LocalPlaylists.nameError("a".repeat(41), playlists))
        assertNull(LocalPlaylists.nameError(" Commute ", playlists, "one"))
    }

    @Test fun multiplePlaylistRoundTripKeepsIdsOrderAndLocalUris() {
        val playlists = listOf(LocalPlaylist("one", "通勤", listOf(local, second)), LocalPlaylist("two", "睡前"))
        assertEquals(playlists, LocalPlaylists.decode(LocalPlaylists.encode(playlists)))
    }

    @Test fun portableExportDoesNotTrustDeviceSpecificUris() {
        val text = SongLists.export(listOf(local, second), favorites = false)
        assertEquals(listOf(first, second), SongLists.parse(text))
        assertFalse(text.contains("content://"))
    }

    @Test fun mergingCannotCreateAPlaylistThatFailsItsOwnReloadLimit() {
        val songs = (1..SongLists.MAX_SONGS).map { first.copy(id = it.toString()) }
        val full = LocalPlaylist("large", "已有歌单", songs)
        assertEquals(full, full.add(listOf(first)))
        assertThrows(IllegalArgumentException::class.java) { full.add(listOf(first.copy(id = "extra"))) }
        assertEquals(listOf(full), LocalPlaylists.decode(LocalPlaylists.encode(listOf(full))))
    }

    @Test fun searchMatchesTitleArtistsAndAlbumAcrossWordsIgnoringCase() {
        val song = first.copy(name = "夜空 Live", artists = listOf("Alice", "Bob"), album = "夏日回声")
        val songs = listOf(song, second)
        listOf("夜空", "ALICE", "bob", "回声", "  live\tAlice  夏日  ").forEach { query ->
            assertEquals(listOf(song), SongLists.search(songs, query))
        }
        assertTrue(SongLists.search(songs, "Alice 不存在").isEmpty())
    }

    @Test fun searchPreservesOrderObjectsAndDownloadedAudioAndCanBeCleared() {
        val songs = listOf(second, local, first.copy(id = "3", name = "第三首"))
        val result = SongLists.search(songs, "首")
        assertEquals(songs, result)
        assertSame(local, result[1])
        assertEquals("content://media/external/audio/media/7", result[1].localUri)
        assertSame(songs, SongLists.search(songs, " \t\n "))
        assertTrue(SongLists.search(songs, "不存在").isEmpty())
        assertEquals(listOf(second, local, first.copy(id = "3", name = "第三首")), songs)
    }

    @Test fun searchIncludesSongsBeyondFirstThousand() {
        val songs = (1..3165).map { first.copy(id = it.toString(), name = "曲目 $it") }
        assertEquals(listOf(songs.last()), SongLists.search(songs, "3165"))
        assertTrue(SongLists.search(emptyList(), "曲目").isEmpty())
    }
}
