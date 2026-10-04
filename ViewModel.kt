package com.streamtv.iptv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val ADULT_RE = Regex("(?i)adult|xxx|porn|18\\+|sex|erotic|للكبار|إباحي")

fun HistoryEntity.toItem() = ChannelEntity(id = itemId, playlistId = playlistId, kind = kind, name = name, logo = logo, streamUrl = url, tvgId = tvgId)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    val repo = Repository(AppDb.get(app))
    private val dao = repo.dao
    val prefs = app.getSharedPreferences("ui", 0)
    val settings = AppSettings(prefs)

    init { L.fr = prefs.getString("lang", if (java.util.Locale.getDefault().language == "fr") "fr" else "ar") == "fr" }
    fun setLang(fr: Boolean) { L.fr = fr; prefs.edit().putString("lang", if (fr) "fr" else "ar").apply() }

    val session = PlaybackSession(app, settings, repo) { c, pos, dur -> saveProgress(c, pos, dur) }

    // ---- navigation state that must survive going fullscreen and coming back
    var section by mutableStateOf(Section.HOME)
    val cats = mutableStateMapOf<String, String>()
    val selected = mutableStateMapOf<String, ChannelEntity>()
    val scrollPos = HashMap<String, Int>()
    var restoreFocus = false

    // ---- profiles
    val profile = MutableStateFlow<ProfileEntity?>(null)
    val profiles = dao.profiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlists = dao.playlists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val itemCount = dao.count().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            if (dao.profileCount() == 0) dao.insertProfile(ProfileEntity(name = L.t("الرئيسي", "Principal")))
            val ps = dao.profiles().first()
            // a single profile without PIN opens directly (no "who is watching" screen at every start)
            if (ps.size == 1 && ps[0].pin.isBlank() && profile.value == null) profile.value = ps[0]
        }
    }

    fun select(p: ProfileEntity?) { profile.value = p }
    fun addProfile(name: String, pin: String, kids: Boolean) {
        if (name.isBlank()) return
        viewModelScope.launch { dao.insertProfile(ProfileEntity(name = name.trim(), pin = pin, isKids = kids)) }
    }
    fun deleteProfile(p: ProfileEntity) {
        viewModelScope.launch { if (dao.profileCount() > 1) dao.deleteProfile(p.id) }
    }

    // ---- browsing
    fun groups(kind: String): Flow<List<GroupCount>> =
        combine(dao.groups(kind), profile, snapshotFlow { settings.hideAdult.on }) { gs, p, hide ->
            if (p?.isKids == true || hide) gs.filter { !ADULT_RE.containsMatchIn(it.groupTitle) } else gs
        }
    fun channels(kind: String, group: String, limit: Int) = dao.byGroup(kind, group, limit)
    fun channelsAll(kind: String, limit: Int) = dao.byKind(kind, limit)

    fun historyOf(kind: String): Flow<List<HistoryEntity>> =
        profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.historyOf(p.id, kind) }
    fun favoritesOf(kind: String): Flow<List<ChannelEntity>> =
        profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.favoritesOf(p.id, kind) }
    val history: Flow<List<HistoryEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.history(p.id) }
    val favorites: Flow<List<ChannelEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.favorites(p.id) }

    suspend fun search(q: String): List<ChannelEntity> {
        val r = dao.search(q, 200)
        return if (profile.value?.isKids == true || settings.hideAdult.on) r.filter { !ADULT_RE.containsMatchIn(it.groupTitle) } else r
    }
    suspend fun featured(kind: String): List<ChannelEntity> = dao.featured(kind, 8)
    suspend fun episodes(c: ChannelEntity): List<ChannelEntity> = repo.episodes(c)
    suspend fun trailer(c: ChannelEntity): String? = repo.trailer(c)

    fun toggleFav(c: ChannelEntity) {
        val p = profile.value ?: return
        viewModelScope.launch(Dispatchers.IO) { if (dao.isFav(p.id, c.id) > 0) dao.removeFav(p.id, c.id) else dao.addFav(FavEntity(p.id, c.id)) }
    }
    fun clearHistory() { val p = profile.value ?: return; viewModelScope.launch(Dispatchers.IO) { dao.clearHistory(p.id) } }
    fun clearFavorites() { val p = profile.value ?: return; viewModelScope.launch(Dispatchers.IO) { dao.clearFavs(p.id) } }

    fun saveProgress(c: ChannelEntity, pos: Long, dur: Long) {
        val p = profile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertHistory(HistoryEntity(p.id, c.id, c.playlistId, c.kind, c.name, c.logo, c.streamUrl, c.tvgId, pos, dur, System.currentTimeMillis()))
        }
    }

    // ---- playback entry points
    fun playFull(list: List<ChannelEntity>, idx: Int, resume: Long = 0L) {
        session.play(list, idx, resume, full = true, preview = false)
    }

    /** Back from fullscreen: a channel started from the preview keeps playing there, everything else stops. */
    fun exitFullscreen() {
        session.fullscreen = false
        if (session.previewMode) restoreFocus = true else session.stop()
    }

    // ---- sources
    private fun run(block: suspend () -> Result<Int>) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            status.value = L.t("جاري الاستيراد...", "Importation...")
            block().onSuccess { status.value = L.t("تم استيراد $it عنصر", "$it éléments importés") }
                .onFailure { status.value = L.t("فشل الاستيراد: ${it.message}. تحقق من الرابط/البيانات والاتصال.", "Échec de l'importation : ${it.message}. Vérifiez le lien, les identifiants et la connexion.") }
            busy.value = false
        }
    }
    fun addM3u(name: String, url: String, epg: String) = run { repo.addM3u(name.ifBlank { "Playlist" }, url.trim(), epg.trim(), settings.epgHours.int) }
    fun addXtream(name: String, server: String, user: String, pass: String) =
        run { repo.addXtream(name.ifBlank { "Xtream" }, server.trim(), user.trim(), pass.trim(), settings.epgHours.int) }
    fun addStalker(name: String, portal: String, mac: String) = run { repo.addStalker(name.ifBlank { "Stalker" }, portal.trim(), mac.trim().uppercase()) }
    fun removePlaylist(p: PlaylistEntity) { viewModelScope.launch { repo.removePlaylist(p) } }
    fun refreshEpg(p: PlaylistEntity) = run { repo.refreshEpg(p, settings.epgHours.int.coerceAtLeast(24)) }

    override fun onCleared() { session.release(); super.onCleared() }
}
