package com.streamtv.iptv

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One cycling setting stored in SharedPreferences. The label lists are parallel to [values] (Arabic / French). */
class Opt(
    private val sp: SharedPreferences, private val key: String, private val values: List<String>,
    private val labelsAr: List<String>, private val labelsFr: List<String>, default: String
) {
    var v by mutableStateOf(sp.getString(key, default)?.takeIf { it in values } ?: default)
        private set
    val on: Boolean get() = v == "on"
    val int: Int get() = v.toIntOrNull() ?: 0
    val label: String get() { val i = values.indexOf(v).coerceAtLeast(0); return L.t(labelsAr[i], labelsFr[i]) }
    fun cycle() { v = values[(values.indexOf(v) + 1) % values.size]; sp.edit().putString(key, v).apply() }
    fun set(x: String) { if (x in values) { v = x; sp.edit().putString(key, v).apply() } }
}

private fun onOff(sp: SharedPreferences, key: String, default: String) =
    Opt(sp, key, listOf("on", "off"), listOf("مفعّل", "متوقف"), listOf("Activé", "Désactivé"), default)

val LOOK_IDS = listOf("hepi", "apple", "dtv", "glass")
val LOOK_AR = listOf("بطاقات وشبكة (ذهبي)", "سينمائي (هيرو كبير)", "شريط جانبي (أحمر)", "زجاجي (إذاعة)")
val LOOK_FR = listOf("Cartes et grille", "Cinéma (grand héros)", "Barre latérale", "Verre (radio)")
val PAL_IDS = listOf("gold", "rust", "crimson", "algeria", "midnight", "amoled", "neon")

class AppSettings(sp: SharedPreferences) {
    val engine = Opt(sp, "engine", listOf("auto", "exo", "vlc"), listOf("تلقائي (Exo ثم VLC)", "ExoPlayer", "VLC"), listOf("Auto (Exo puis VLC)", "ExoPlayer", "VLC"), "auto")
    val buffer = Opt(sp, "buffer", listOf("fast", "balanced", "stable"), listOf("سريع", "متوازن", "مستقر"), listOf("Rapide", "Équilibré", "Stable"), "balanced")
    val hw = onOff(sp, "hw", "on")
    val aspect = Opt(sp, "aspect", listOf("0", "1", "2", "3", "4"), listOf("ملاءمة", "ملء", "تكبير", "16:9", "4:3"), listOf("Ajusté", "Remplir", "Zoom", "16:9", "4:3"), "0")
    val seek = Opt(sp, "seek", listOf("10", "20", "30", "60"), listOf("10 ث", "20 ث", "30 ث", "60 ث"), listOf("10 s", "20 s", "30 s", "60 s"), "10")
    val resume = onOff(sp, "resume", "on")
    val autoNext = onOff(sp, "autonext", "on")
    val subSize = Opt(sp, "subsize", listOf("80", "100", "130", "160"), listOf("صغير", "عادي", "كبير", "كبير جداً"), listOf("Petit", "Normal", "Grand", "Très grand"), "100")
    val clickMode = Opt(sp, "click", listOf("preview", "full"), listOf("معاينة أولاً", "ملء الشاشة مباشرة"), listOf("Aperçu d'abord", "Plein écran direct"), "preview")
    val iconSize = Opt(sp, "icon", listOf("32", "44", "60"), listOf("صغيرة", "متوسطة", "كبيرة"), listOf("Petites", "Moyennes", "Grandes"), "32")
    val lite = onOff(sp, "lite", "off")
    val epgHours = Opt(sp, "epg", listOf("0", "24", "48"), listOf("بدون", "24 ساعة", "48 ساعة"), listOf("Aucun", "24 h", "48 h"), "24")
    val hideAdult = onOff(sp, "adult", "on")
    val ua = Opt(sp, "ua", listOf("vlc", "smarters", "exo", "browser"), listOf("VLC", "IPTV Smarters", "ExoPlayer", "متصفح"), listOf("VLC", "IPTV Smarters", "ExoPlayer", "Navigateur"), "vlc")

    private fun lookOpt(k: String, d: String) = Opt(sp, k, LOOK_IDS, LOOK_AR, LOOK_FR, d)
    private fun palOpt(k: String, d: String) = Opt(sp, k, PAL_IDS, PALS.map { it.ar }, PALS.map { it.fr }, d)
    val lookLive = lookOpt("look_live", "hepi");   val palLive = palOpt("pal_live", "gold")
    val lookMovies = lookOpt("look_movies", "apple"); val palMovies = palOpt("pal_movies", "rust")
    val lookSeries = lookOpt("look_series", "dtv");  val palSeries = palOpt("pal_series", "crimson")
    val lookRadio = lookOpt("look_radio", "glass");  val palRadio = palOpt("pal_radio", "algeria")
    val palHome = palOpt("pal_home", "algeria")

    fun look(sec: Section): String = when (sec) {
        Section.LIVE, Section.MATCHES -> lookLive.v
        Section.MOVIES -> lookMovies.v
        Section.SERIES -> lookSeries.v
        Section.RADIO -> lookRadio.v
        else -> "hepi"
    }

    fun pal(sec: Section): Pal {
        val id = when (sec) {
            Section.LIVE, Section.MATCHES -> palLive.v
            Section.MOVIES -> palMovies.v
            Section.SERIES -> palSeries.v
            Section.RADIO -> palRadio.v
            else -> palHome.v
        }
        return PALS.firstOrNull { it.id == id } ?: PALS[0]
    }

    fun userAgent(): String = when (ua.v) {
        "smarters" -> "IPTVSmartersPro"
        "exo" -> "ExoPlayerLib/1.4.1"
        "browser" -> "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        else -> VLC_UA
    }
}
