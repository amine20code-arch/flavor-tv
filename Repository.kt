package com.streamtv.iptv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

class Repository(val db: AppDb) {
    val dao = db.dao()
    // The EPG file can be 100 MB+: it is imported in the background so the channels are usable immediately.
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun <T> io(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching { block() } }

    suspend fun addM3u(name: String, url: String, epg: String, hours: Int): Result<Int> = io {
        val pid = dao.insertPlaylist(PlaylistEntity(name = name, type = "m3u", url = url, epgUrl = epg))
        var n = 0
        try {
            Net.open(url).use { r -> M3uParser.parse(r.body!!.charStream().buffered(), pid) { dao.insertItems(it); n += it.size } }
        } catch (e: Exception) { rollback(pid); throw e }
        if (epg.isNotBlank() && hours > 0) bg.launch { runCatching { loadEpg(pid, epg, hours) } }
        n
    }

    suspend fun addXtream(name: String, server: String, user: String, pass: String, hours: Int): Result<Int> = io {
        val c = XtreamClient(server, user, pass)
        val pid = dao.insertPlaylist(PlaylistEntity(name = name, type = "xtream", url = c.base, username = user, password = pass))
        var n = 0
        try { c.live(pid) { dao.insertItems(it); n += it.size } } catch (e: Exception) { rollback(pid); throw e }
        runCatching { c.movies(pid) { dao.insertItems(it); n += it.size } }
        runCatching { c.series(pid) { dao.insertItems(it); n += it.size } }
        if (hours > 0) bg.launch { runCatching { loadEpg(pid, c.epgUrl(), hours) } }
        n
    }

    suspend fun addStalker(name: String, portal: String, mac: String): Result<Int> = io {
        val pid = dao.insertPlaylist(PlaylistEntity(name = name, type = "stalker", url = portal, username = mac))
        var n = 0
        try { StalkerClient(portal, mac).channels(pid) { dao.insertItems(it); n += it.size } } catch (e: Exception) { rollback(pid); throw e }
        n
    }

    private suspend fun rollback(pid: Long) { dao.deleteItems(pid); dao.deleteEpg(pid); dao.deletePlaylist(pid) }

    suspend fun removePlaylist(p: PlaylistEntity) = withContext(Dispatchers.IO) { rollback(p.id) }

    private suspend fun loadEpg(pid: Long, url: String, hours: Int): Int {
        var n = 0
        Net.open(url).use { r ->
            var s: InputStream = BufferedInputStream(r.body!!.byteStream())
            if (url.contains(".gz")) s = GZIPInputStream(s)
            dao.deleteEpg(pid)
            XmltvParser.parse(s, pid, hours) { dao.insertEpg(it); n += it.size }
        }
        return n
    }

    suspend fun refreshEpg(p: PlaylistEntity, hours: Int): Result<Int> = io {
        val url = when {
            p.epgUrl.isNotBlank() -> p.epgUrl
            p.type == "xtream" -> XtreamClient(p.url, p.username, p.password).epgUrl()
            else -> error("This source has no EPG URL")
        }
        loadEpg(p.id, url, hours)
    }

    /** Turns a stored item into a playable stream (Stalker links are created per play). */
    suspend fun resolve(item: ChannelEntity): Stream = withContext(Dispatchers.IO) {
        if (item.streamUrl.startsWith("stalker:")) {
            val p = dao.playlist(item.playlistId) ?: error("Source removed")
            val u = StalkerClient(p.url, p.username).resolve(item.streamUrl.removePrefix("stalker:"))
            Stream(u, MAG_UA, mapOf("Cookie" to "mac=${p.username}; stb_lang=en; timezone=GMT", "X-User-Agent" to "Model: MAG250; Link: WiFi"))
        } else Stream(item.streamUrl, VLC_UA)
    }

    suspend fun episodes(item: ChannelEntity): List<ChannelEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val p = dao.playlist(item.playlistId) ?: return@runCatching emptyList<ChannelEntity>()
            XtreamClient(p.url, p.username, p.password).episodes(item.streamUrl.removePrefix("series:"), p.id)
        }.getOrDefault(emptyList())
    }

    suspend fun trailer(item: ChannelEntity): String? = withContext(Dispatchers.IO) {
        if (item.kind != "movie" || item.ext.isBlank()) return@withContext null
        val p = dao.playlist(item.playlistId)?.takeIf { it.type == "xtream" } ?: return@withContext null
        XtreamClient(p.url, p.username, p.password).trailer(item.ext)
    }

    suspend fun nowNext(tvgId: String): List<EpgEntity> = dao.nowNext(tvgId, System.currentTimeMillis())
}
