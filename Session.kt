package com.streamtv.iptv

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One way of trying to open a stream: which engine, which format hint, and whether to use the .ts variant of an Xtream .m3u8 link. */
data class Attempt(val engine: String, val mode: Int, val alt: Boolean) {
    val label: String get() = (if (engine == "vlc") "VLC" else "ExoPlayer") +
        when (mode) { 1 -> " · TS"; 2 -> " · HLS"; else -> "" } + (if (alt) " · .ts" else "")
}

/**
 * One playback session shared by the small preview window and the fullscreen player (the channel keeps playing when it
 * goes fullscreen). Radical player strategy:
 *  - a plan of several attempts (engine x format hint x URL variant) is tried automatically, each with a start-up watchdog;
 *  - Stalker links are single-use tokens, so a FRESH link is requested for every attempt;
 *  - when every attempt fails the URL is inspected (status code, first bytes) and the real reason is shown.
 */
class PlaybackSession(
    private val app: Application,
    private val settings: AppSettings,
    private val repo: Repository,
    private val saver: (ChannelEntity, Long, Long) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var engine: Engine? = null
        private set
    var engineKind by mutableStateOf("")
        private set
    var engineGen by mutableIntStateOf(0)
        private set
    var items by mutableStateOf<List<ChannelEntity>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var attemptLabel by mutableStateOf("")
        private set
    var fullscreen by mutableStateOf(false)
    var previewMode by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
        private set
    var aspect by mutableIntStateOf(settings.aspect.int)
        private set
    var stable by mutableStateOf(false)
        private set
    var subDelay by mutableLongStateOf(0L)
        private set
    var audioDelay by mutableLongStateOf(0L)
        private set
    var sleepLabel by mutableStateOf("")
        private set

    val item: ChannelEntity? get() = items.getOrNull(index)

    private var stream: Stream? = null
    private var plan: List<Attempt> = emptyList()
    private var attemptIdx = 0
    private var rebuffers = 0
    private var lastResume = 0L
    private var lastStart = 0L
    private var host: View? = null
    private var loadJob: Job? = null
    private var watchJob: Job? = null
    private var sleepAt = 0L
    private var sleepIdx = 0
    private var curUrl = ""

    init {
        sleepLabel = L.t("متوقف", "Off")
        scope.launch {
            while (true) {
                delay(10_000)
                tick()
            }
        }
    }

    private fun tick() {
        saveNow()
        if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
            sleepAt = 0; sleepIdx = 0; sleepLabel = L.t("متوقف", "Off")
            stop()
        }
    }

    // ---------------------------------------------------------------- starting playback

    fun play(list: List<ChannelEntity>, idx: Int, resumeMs: Long = 0L, full: Boolean = false, preview: Boolean = false) {
        if (list.isEmpty()) return
        items = list
        index = idx.coerceIn(0, list.size - 1)
        previewMode = preview
        if (full) fullscreen = true
        start(resumeMs)
    }

    fun zap(d: Int) {
        if (items.size > 1) { index = (index + d + items.size) % items.size; start(0L) }
    }

    fun jumpTo(i: Int) {
        if (i in items.indices) { index = i; start(0L) }
    }

    fun retry() = start(lastResume)

    private fun firstEngine() = if (settings.engine.v == "vlc") "vlc" else "exo"

    private fun buildPlan(cur: ChannelEntity, url: String): List<Attempt> {
        val first = firstEngine()
        val other = if (first == "exo") "vlc" else "exo"
        val xtreamLive = url.contains("/live/") && url.endsWith(".m3u8")
        val isHls = url.contains("m3u8", true)
        val l = ArrayList<Attempt>()
        l.add(Attempt(first, 0, false))
        if (xtreamLive) l.add(Attempt(first, 0, true))            // many panels only serve .ts
        if (first == "exo" && cur.kind != "movie") {
            if (!isHls) l.add(Attempt("exo", 1, false))            // unknown extension: force the MPEG-TS parser
            if (!isHls || xtreamLive) l.add(Attempt("exo", 2, false)) // or force HLS
        }
        l.add(Attempt(other, 0, false))
        if (xtreamLive) l.add(Attempt(other, 0, true))
        return l.distinct()
    }

    private fun start(resume: Long) {
        val cur = item ?: return
        loadJob?.cancel(); watchJob?.cancel()
        error = null; loading = true; rebuffers = 0; attemptLabel = ""
        lastResume = resume; lastStart = System.currentTimeMillis()
        speed = 1f; subDelay = 0L; audioDelay = 0L
        ensureEngine(firstEngine())
        engine?.setSpeed(1f)
        if (cur.kind != "movie") saver(cur, 0L, 0L)
        loadJob = scope.launch {
            val st = try {
                repo.resolve(cur)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                error = L.t("تعذر الحصول على رابط القناة: ", "Impossible d'obtenir le lien : ") + (e.message ?: ""); loading = false
                return@launch
            }
            stream = st.copy(ua = settings.userAgent().takeIf { cur.streamUrl.startsWith("stalker:").not() } ?: st.ua)
            plan = buildPlan(cur, st.url)
            attemptIdx = 0
            execute(resume)
        }
    }

    private fun urlFor(a: Attempt, base: String) =
        if (a.alt && base.endsWith(".m3u8")) base.removeSuffix(".m3u8") + ".ts" else base

    private fun execute(resume: Long) {
        val cur = item ?: return
        val a = plan.getOrNull(attemptIdx) ?: return
        loadJob?.cancel(); watchJob?.cancel()
        loadJob = scope.launch {
            // Stalker tokens are single use: every attempt after the first needs a new link.
            var st = stream ?: return@launch
            if (attemptIdx > 0 && cur.streamUrl.startsWith("stalker:")) {
                loading = true
                st = try { repo.resolve(cur).also { stream = it } } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { onEngineError("link: ${e.message}"); return@launch }
            }
            ensureEngine(a.engine)
            loading = false
            curUrl = urlFor(a, st.url)
            attemptLabel = "${attemptIdx + 1}/${plan.size}  ${a.label}"
            try { engine?.load(st, curUrl, resume, a.mode) } catch (t: Throwable) { onEngineError(t.message ?: "load failed"); return@launch }
            // start-up watchdog: a stream that never produces a frame counts as failed
            val limit = if (stable || settings.buffer.v == "stable") 22_000L else 14_000L
            val mine = attemptIdx
            watchJob = scope.launch {
                delay(limit)
                if (mine == attemptIdx && engine?.started != true && error == null) onEngineError("timeout")
            }
        }
    }

    private fun onEngineError(m: String) {
        if (error != null) return
        if (attemptIdx + 1 < plan.size) { attemptIdx++; execute(lastResume); return }
        // every attempt failed: look at what the server really answers and tell the user
        watchJob?.cancel()
        loading = false
        val st = stream
        val shown = L.t("فشل التشغيل ($m).", "Échec de la lecture ($m).")
        error = shown
        if (st != null) scope.launch {
            val p = withContext(Dispatchers.IO) { Diag.probe(st, curUrl) }
            error = shown + "\n" + Diag.explain(p) + "\n" + Diag.mask(curUrl)
        }
    }

    /** Called from the error panel: inspects the current URL without any player. */
    fun diagnose() {
        val st = stream ?: return
        error = L.t("جاري الفحص...", "Analyse en cours...")
        scope.launch {
            val p = withContext(Dispatchers.IO) { Diag.probe(st, curUrl) }
            error = Diag.explain(p) + "\nHTTP ${p.code} · ${p.kind}\n" + Diag.mask(p.finalUrl)
        }
    }

    private fun onEngineEnded() {
        val c = item ?: return
        if (c.kind == "movie") {
            saver(c, 0L, 0L)
            if (settings.autoNext.on && index + 1 < items.size) jumpTo(index + 1)
        } else if (System.currentTimeMillis() - lastStart > 5000) {
            start(0L) // live stream dropped: reconnect
        }
    }

    private fun onRebuffer() {
        val c = item ?: return
        rebuffers++
        if (c.kind != "movie" && !stable && engine?.kind == "exo" && rebuffers >= 4) toggleStable()
    }

    // ---------------------------------------------------------------- engine management

    private fun ensureEngine(kind: String) {
        val cur = engine
        if (cur != null && cur.kind == kind) return
        cur?.let { dispose(it) }
        host = null
        val e: Engine = try {
            if (kind == "vlc") VlcEngine(app, settings, stable) else ExoEngine(app, settings, stable)
        } catch (t: Throwable) {
            ExoEngine(app, settings, stable)
        }
        e.setAspect(aspect)
        e.onError = { m -> main.post { onEngineError(m) } }
        e.onEnded = { main.post { onEngineEnded() } }
        e.onRebuffer = { main.post { onRebuffer() } }
        engine = e
        engineKind = e.kind
        engineGen++
    }

    private fun dispose(e: Engine) {
        e.onError = null; e.onEnded = null; e.onRebuffer = null
        e.release()
    }

    fun createHost(ctx: Context): View {
        val e = engine
        val v = e?.createHost(ctx) ?: View(ctx)
        host = v
        e?.attach(v)
        return v
    }

    fun releaseHost(v: View) {
        if (host === v) { engine?.detach(); host = null }
    }

    /** Manual switch from the menu: restart the plan with the other engine first. */
    fun switchEngine() {
        val e = engine ?: return
        val cur = item ?: return
        val k = if (e.kind == "exo") "vlc" else "exo"
        val pos = if (cur.kind == "movie") e.position else 0L
        val url = stream?.url ?: return
        plan = listOf(Attempt(k, 0, false), Attempt(if (k == "exo") "vlc" else "exo", 0, false))
        attemptIdx = 0; error = null; lastResume = pos
        stream = stream?.copy(url = url)
        execute(pos)
    }

    fun toggleStable() {
        stable = !stable
        val e = engine ?: return
        val kind = e.kind
        val pos = if (item?.kind == "movie") e.position else 0L
        dispose(e)
        engine = null
        host = null
        ensureEngine(kind)
        if (stream != null && plan.isNotEmpty()) execute(pos)
    }

    // ---------------------------------------------------------------- controls

    fun togglePlay() { engine?.let { it.setPlaying(!it.isPlaying) } }

    fun seekBy(ms: Long) {
        val e = engine ?: return
        val t = (e.position + ms).coerceAtLeast(0L)
        val d = e.duration
        e.seekTo(if (d > 0) t.coerceAtMost(d - 500L).coerceAtLeast(0L) else t)
    }

    fun cycleSpeed() {
        val l = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val i = l.indexOf(speed).let { if (it < 0) 2 else it }
        speed = l[(i + 1) % l.size]
        engine?.setSpeed(speed)
    }

    fun cycleAspect() {
        aspect = (aspect + 1) % 5
        engine?.setAspect(aspect)
    }

    fun addSubDelay(ms: Long) { subDelay = engine?.addSubtitleDelay(ms) ?: 0L }
    fun addAudioDelayMs(ms: Long) { audioDelay = engine?.addAudioDelay(ms) ?: 0L }
    fun audioTracks(): List<Track> = engine?.audioTracks() ?: emptyList()
    fun subtitleTracks(): List<Track> = engine?.subtitleTracks() ?: emptyList()
    fun currentUrl(): String = curUrl.ifBlank { stream?.url ?: "" }

    fun cycleSleep() {
        val mins = listOf(0, 15, 30, 60, 90)
        sleepIdx = (sleepIdx + 1) % mins.size
        val m = mins[sleepIdx]
        sleepAt = if (m == 0) 0L else System.currentTimeMillis() + m * 60_000L
        sleepLabel = if (m == 0) L.t("متوقف", "Off") else "$m min"
    }

    // ---------------------------------------------------------------- stopping

    fun saveNow() {
        val c = item ?: return
        val e = engine ?: return
        if (c.kind == "movie" && e.duration > 0 && e.position > 0) saver(c, e.position, e.duration)
    }

    fun stop() {
        saveNow()
        loadJob?.cancel(); watchJob?.cancel()
        engine?.let { dispose(it) }
        engine = null; engineKind = ""; host = null
        items = emptyList(); index = 0
        error = null; loading = false; attemptLabel = ""
        fullscreen = false; previewMode = false
        speed = 1f
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
