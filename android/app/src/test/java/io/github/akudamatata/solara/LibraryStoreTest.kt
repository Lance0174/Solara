package io.github.akudamatata.solara

import android.app.Application
import android.content.Context
import io.github.akudamatata.solara.data.LibraryStore
import io.github.akudamatata.solara.data.NeteasePlaylists
import io.github.akudamatata.solara.data.Song
import io.github.akudamatata.solara.data.SongLists
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class LibraryStoreTest {
    private lateinit var context: Context
    private val song = Song("1", "测试歌曲", listOf("测试歌手"))

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("solara-library", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun upgradingKeepsOldQueueFavoritesAndPlaybackPosition() {
        context.getSharedPreferences("solara-library", Context.MODE_PRIVATE).edit()
            .putString("playlistSongs", SongLists.array(listOf(song)).toString())
            .putString("favoriteSongs", SongLists.array(listOf(song)).toString())
            .putInt("index", 0).putLong("position", 45000).commit()
        val store = LibraryStore(context)
        assertTrue(store.playlists.value.isEmpty())
        store.createPlaylist("通勤", store.library.value.queue)
        val restored = LibraryStore(context)
        assertEquals(listOf(song), restored.library.value.queue)
        assertEquals(listOf(song), restored.library.value.favorites)
        assertEquals(45000L, restored.savedPosition)
        assertEquals(listOf(song), restored.playlists.value.single().songs)
    }

    @Test fun namedPlaylistsSurviveReloadAndDeleteOnlySelectedCollection() {
        val store = LibraryStore(context)
        val first = store.createPlaylist(" 通勤 ", listOf(song))
        val second = store.createPlaylist("睡前", listOf(song))
        store.renamePlaylist(first.id, "在路上")
        val restored = LibraryStore(context)
        assertEquals(listOf("在路上", "睡前"), restored.playlists.value.map { it.name })
        restored.deletePlaylist(first.id)
        assertEquals(listOf(second), LibraryStore(context).playlists.value)
    }

    @Test fun downloadingReferencePersistsInLocalPlaylistAndFavorites() {
        val local = song.copy(localUri = "content://media/external/audio/media/23")
        val store = LibraryStore(context)
        val playlist = store.createPlaylist("离线", listOf(song))
        store.updatePlaylist(playlist.id) { it.add(listOf(local)) }
        store.saveFavorites(listOf(local))
        val restored = LibraryStore(context)
        assertEquals(local, restored.playlists.value.single().songs.single())
        assertEquals(local, restored.library.value.favorites.single())
    }

    @Test fun failedRenameLeavesBothPlaylistsIntact() {
        val store = LibraryStore(context)
        val first = store.createPlaylist("通勤", listOf(song))
        store.createPlaylist("睡前")
        val before = store.playlists.value
        assertThrows(IllegalArgumentException::class.java) { store.renamePlaylist(first.id, "睡前") }
        assertEquals(before, LibraryStore(context).playlists.value)
    }

    @Test fun importingNeteasePreservesExistingDownloadsAndSurvivesReload() {
        val imported = NeteasePlaylists.parse(JSONObject("""{"code":200,"playlist":{"id":1,"name":"外部歌单","trackCount":2,
            "tracks":[{"id":1,"name":"在线曲目"},{"id":2,"name":"新曲目"}]}}"""), "1")
        val local = song.copy(localUri = "content://media/external/audio/media/23")
        val store = LibraryStore(context)
        val target = store.createPlaylist("本地歌单", listOf(local))
        store.updatePlaylist(target.id) { it.add(imported.songs) }
        store.updatePlaylist(target.id) { it.add(imported.songs) }
        val restored = LibraryStore(context).playlists.value.single()
        assertEquals("本地歌单", restored.name)
        assertEquals(listOf("1", "2"), restored.songs.map { it.id })
        assertEquals(local, restored.songs.first())
        assertEquals("", restored.songs.last().localUri)
        assertTrue(store.library.value.queue.isEmpty())
    }

    @Test fun ensureDefaultPlaylistCreatesFavoritesOnlyWhenEmptyAndKeepsExisting() {
        val store = LibraryStore(context)
        // 初始化不自动建歌单，首次确保时才创建「我喜欢」。
        assertTrue(store.playlists.value.isEmpty())
        val seeded = store.ensureDefaultPlaylist()
        assertEquals(listOf("我喜欢"), seeded.map { it.name })
        assertEquals(listOf("我喜欢"), LibraryStore(context).playlists.value.map { it.name })
        // 已有歌单时保持原样，不再追加。
        val existing = LibraryStore(context)
        existing.createPlaylist("通勤")
        assertEquals(listOf("我喜欢", "通勤"), existing.ensureDefaultPlaylist().map { it.name })
    }

    @Test fun cacheLimitDefaultsToFiveAndCannotExceedFiveAfterSavingOrReloading() {
        val store = LibraryStore(context)
        assertEquals(5, store.settings.value.cacheLimitGb)
        store.saveSettings(store.settings.value.copy(cacheLimitGb = 2))
        assertEquals(2, LibraryStore(context).settings.value.cacheLimitGb)
        store.saveSettings(store.settings.value.copy(cacheLimitGb = 99))
        assertEquals(5, store.settings.value.cacheLimitGb)
        assertEquals(5, LibraryStore(context).settings.value.cacheLimitGb)
        store.saveSettings(store.settings.value.copy(cacheLimitGb = 0))
        assertEquals(1, LibraryStore(context).settings.value.cacheLimitGb)
    }

    @Test fun themeStyleDefaultsOffForNewAndUpgradedInstallations() {
        assertEquals("default", LibraryStore(context).settings.value.themeStyle)
        context.getSharedPreferences("solara-library", Context.MODE_PRIVATE).edit()
            .putString("theme", "dark").putString("playlistSongs", SongLists.array(listOf(song)).toString()).commit()
        val upgraded = LibraryStore(context)
        assertEquals("default", upgraded.settings.value.themeStyle)
        assertEquals("dark", upgraded.settings.value.theme)
        assertEquals(listOf(song), upgraded.library.value.queue)
    }

    @Test fun themeStyleSurvivesReloadAndCanBeDisabledWithoutChangingBrightness() {
        val store = LibraryStore(context)
        store.saveSettings(store.settings.value.copy(theme = "dark", themeStyle = "endfield"))
        val restored = LibraryStore(context)
        assertEquals("endfield", restored.settings.value.themeStyle)
        assertEquals("dark", restored.settings.value.theme)
        restored.saveSettings(restored.settings.value.copy(themeStyle = "default"))
        assertEquals("default", LibraryStore(context).settings.value.themeStyle)
        assertEquals("dark", LibraryStore(context).settings.value.theme)
    }

    @Test fun unsupportedThemeStylesFallBackToDefault() {
        context.getSharedPreferences("solara-library", Context.MODE_PRIVATE).edit().putString("themeStyle", "future-style").commit()
        val store = LibraryStore(context)
        assertEquals("default", store.settings.value.themeStyle)
        store.saveSettings(store.settings.value.copy(themeStyle = "invalid"))
        assertEquals("default", LibraryStore(context).settings.value.themeStyle)
    }
}
