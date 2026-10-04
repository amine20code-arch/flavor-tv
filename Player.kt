@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamtv.iptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent as AKey
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Overlay { NONE, INFO, MENU, AUDIO, SUBS, EPISODES }

private fun aspects() = listOf(L.t("ملاءمة", "Ajusté"), L.t("ملء", "Remplir"), L.t("تكبير", "Zoom"), "16:9", "4:3")

fun fmtTime(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun openExternal(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")) }
}

@Composable
fun Equalizer() {
    val t = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(24) { i ->
            val h by t.animateFloat(0.15f, 1f, infiniteRepeatable(tween(300 + (i * 53) % 400, easing = LinearEasing), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.width(10.dp).fillMaxHeight(h).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** Small preview window (16:9). OK on it opens fullscreen. */
@Composable
fun MiniPlayer(vm: MainViewModel, modifier: Modifier = Modifier) {
    val s = vm.session
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.aspectRatio(16f / 9f).background(Color.Black)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x55FFFFFF))
            .onFocusChanged { focused = it.isFocused }
            .clickable { s.fullscreen = true }
    ) {
        key(s.engineGen) {
            AndroidView(factory = { c -> s.createHost(c) }, onRelease = { v -> s.releaseHost(v) }, modifier = Modifier.fillMaxSize())
        }
        if (s.item?.kind == "radio") Box(Modifier.align(Alignment.Center)) { Equalizer() }
        if (s.loading) Text(L.t("جاري التحميل...", "Chargement..."), modifier = Modifier.align(Alignment.Center), color = Color.White)
    }
}

@Composable
private fun PickerPanel(title: String, entries: List<Pair<String, Boolean>>, first: FocusRequester, modifier: Modifier, onPick: (Int) -> Unit) {
    Column(modifier.width(400.dp).background(Color(0xEE000000)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color.White)
        LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(entries) { i, e ->
                Button(
                    onClick = { onPick(i) },
                    modifier = if (i == 0) Modifier.fillMaxWidth().focusRequester(first) else Modifier.fillMaxWidth()
                ) { Text((if (e.second) "● " else "○ ") + e.first, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
fun FullPlayer(vm: MainViewModel) {
    val s = vm.session
    val cur = s.item ?: return
    val ctx = LocalContext.current
    val isLive = cur.kind != "movie"
    val step = vm.settings.seek.int

    var overlay by remember { mutableStateOf(Overlay.INFO) }
    var tick by remember { mutableIntStateOf(0) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    var epg by remember { mutableStateOf<List<EpgEntity>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val first = remember { FocusRequester() }

    val showError = s.error != null
    val panel = overlay != Overlay.NONE && overlay != Overlay.INFO
    val modal = panel || showError

    BackHandler { if (panel) overlay = Overlay.NONE else vm.exitFullscreen() }

    LaunchedEffect(Unit) {
        while (true) {
            s.engine?.let { e -> pos = e.position; dur = e.duration; playing = e.isPlaying; buffering = e.isBuffering }
            delay(500)
        }
    }
    LaunchedEffect(cur.id) {
        epg = if (cur.tvgId.isNotBlank()) runCatching { vm.repo.nowNext(cur.tvgId) }.getOrDefault(emptyList()) else emptyList()
        tick++
    }
    LaunchedEffect(tick) {
        if (overlay == Overlay.NONE || overlay == Overlay.INFO) {
            overlay = Overlay.INFO
            delay(4000)
            if (overlay == Overlay.INFO) overlay = Overlay.NONE
        }
    }
    LaunchedEffect(modal, overlay) {
        delay(60)
        runCatching { if (modal) first.requestFocus() else focus.requestFocus() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || modal) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    AKey.KEYCODE_CHANNEL_UP, AKey.KEYCODE_MEDIA_NEXT -> { s.zap(1); tick++; true }
                    AKey.KEYCODE_CHANNEL_DOWN, AKey.KEYCODE_MEDIA_PREVIOUS -> { s.zap(-1); tick++; true }
                    AKey.KEYCODE_DPAD_UP -> { if (isLive) s.zap(1); tick++; true }
                    AKey.KEYCODE_DPAD_DOWN -> { if (isLive) { s.zap(-1); tick++ } else overlay = Overlay.MENU; true }
                    AKey.KEYCODE_DPAD_LEFT -> { if (!isLive) { s.seekBy(-step * 1000L); tick++ }; true }
                    AKey.KEYCODE_DPAD_RIGHT -> { if (!isLive) { s.seekBy(step * 1000L); tick++ }; true }
                    AKey.KEYCODE_MEDIA_REWIND -> { s.seekBy(-step * 1000L); tick++; true }
                    AKey.KEYCODE_MEDIA_FAST_FORWARD -> { s.seekBy(step * 1000L); tick++; true }
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_MEDIA_PLAY_PAUSE -> { s.togglePlay(); tick++; true }
                    AKey.KEYCODE_MEDIA_PLAY -> { s.engine?.setPlaying(true); tick++; true }
                    AKey.KEYCODE_MEDIA_PAUSE -> { s.engine?.setPlaying(false); tick++; true }
                    AKey.KEYCODE_MEDIA_STOP -> { vm.exitFullscreen(); true }
                    AKey.KEYCODE_MENU -> { overlay = Overlay.MENU; true }
                    AKey.KEYCODE_PROG_RED, AKey.KEYCODE_INFO, AKey.KEYCODE_GUIDE -> { tick++; true }
                    AKey.KEYCODE_PROG_GREEN -> { overlay = Overlay.AUDIO; true }
                    AKey.KEYCODE_PROG_YELLOW -> { overlay = Overlay.SUBS; true }
                    AKey.KEYCODE_PROG_BLUE -> { s.cycleAspect(); true }
                    else -> false
                }
            }
    ) {
        key(s.engineGen) {
            AndroidView(factory = { c -> s.createHost(c) }, onRelease = { v -> s.releaseHost(v) }, modifier = Modifier.fillMaxSize())
        }
        if (cur.kind == "radio") Box(Modifier.align(Alignment.Center)) { Equalizer() }
        if ((buffering || s.loading) && !showError && !s.inPip) {
            Text(L.t("جاري التحميل...", "Chargement..."), modifier = Modifier.align(Alignment.Center), color = Color.White)
        }

        // ---- info bar
        if (overlay == Overlay.INFO && !showError && !s.inPip) {
            val tf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xAA000000)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(cur.name, fontSize = 24.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isLive) {
                    epg.getOrNull(0)?.let { Text(L.t("الآن: ${it.title}  (${tf.format(Date(it.start))}-${tf.format(Date(it.stop))})", "Maintenant : ${it.title}  (${tf.format(Date(it.start))}-${tf.format(Date(it.stop))})"), color = Color.White) }
                    epg.getOrNull(1)?.let { Text(L.t("التالي: ${it.title}  (${tf.format(Date(it.start))})", "Ensuite : ${it.title}  (${tf.format(Date(it.start))})"), color = Color(0xFFBBBBBB)) }
                    Text(L.t("↑↓ أو CH+/CH-: تبديل القناة  -  OK: إيقاف مؤقت  -  MENU: خيارات  -  أخضر: الصوت  -  أصفر: الترجمة  -  أزرق: أبعاد الصورة", "↑↓ ou CH+/CH- : changer de chaîne  -  OK : pause  -  MENU : options  -  Vert : audio  -  Jaune : sous-titres  -  Bleu : format"), fontSize = 12.sp, color = Color(0xFF999999))
                } else {
                    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    }
                    Text("${fmtTime(pos)} / ${fmtTime(dur)}" + if (s.items.size > 1) "   -   ${s.index + 1}/${s.items.size}" else "", color = Color.White)
                    Text(L.t("←→: تقديم/إرجاع ${step} ث  -  OK: إيقاف مؤقت  -  ↓ أو MENU: خيارات", "←→ : ±${step} s  -  OK : pause  -  ↓ ou MENU : options"), fontSize = 12.sp, color = Color(0xFF999999))
                }
            }
        }

        // ---- error panel
        if (showError) {
            Column(
                Modifier.align(Alignment.Center).background(Color(0xDD000000)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(s.error ?: "", color = Color.White, fontSize = 14.sp, modifier = Modifier.width(560.dp))
                Button(onClick = { s.retry() }, modifier = Modifier.focusRequester(first)) { Text(L.t("إعادة تحميل البث", "Recharger le flux")) }
                Button(onClick = { s.switchEngine() }) { Text(L.t("جرّب المشغّل الآخر (ExoPlayer / VLC)", "Essayer l'autre lecteur (ExoPlayer / VLC)")) }
                Button(onClick = { s.diagnose() }) { Text(L.t("فحص الرابط (لماذا لا يعمل؟)", "Analyser le lien (pourquoi ça ne marche pas ?)")) }
                Button(onClick = { openExternal(ctx, s.currentUrl()) }) { Text(L.t("فتح في مشغّل خارجي", "Ouvrir dans un lecteur externe")) }
                Button(onClick = { vm.exitFullscreen() }) { Text(L.t("رجوع", "Retour")) }
            }
        }

        // ---- options menu
        if (overlay == Overlay.MENU && !showError) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xEE000000)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("${cur.name}  -  ${s.attemptLabel}  ${s.engine?.videoInfo ?: ""}",
                    color = Color(0xFFCCCCCC), maxLines = 1, overflow = TextOverflow.Ellipsis)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Button(onClick = { s.togglePlay() }, modifier = Modifier.focusRequester(first)) { Text(if (playing) L.t("إيقاف مؤقت", "Pause") else L.t("تشغيل", "Lecture")) } }
                    if (!isLive) {
                        item { Button(onClick = { s.seekBy(-step * 1000L) }) { Text("-${step}s") } }
                        item { Button(onClick = { s.seekBy(step * 1000L) }) { Text("+${step}s") } }
                    }
                    item { Button(onClick = { s.cycleSpeed() }) { Text(L.t("السرعة ${s.speed}x", "Vitesse ${s.speed}x")) } }
                    item { Button(onClick = { overlay = Overlay.AUDIO }) { Text(L.t("الصوت", "Audio")) } }
                    item { Button(onClick = { overlay = Overlay.SUBS }) { Text(L.t("الترجمة", "Sous-titres")) } }
                    item { Button(onClick = { s.cycleAspect() }) { Text(L.t("أبعاد الصورة: ${aspects()[s.aspect]}", "Format : ${aspects()[s.aspect]}")) } }
                    if (!isLive && s.items.size > 1) {
                        item { Button(onClick = { overlay = Overlay.EPISODES }) { Text(L.t("الحلقات (${s.index + 1}/${s.items.size})", "Épisodes (${s.index + 1}/${s.items.size})")) } }
                        item { Button(onClick = { s.zap(-1) }) { Text(L.t("السابق", "Précédent")) } }
                        item { Button(onClick = { s.zap(1) }) { Text(L.t("التالي", "Suivant")) } }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Button(onClick = { s.switchEngine() }) { Text(L.t("المشغّل: ${if (s.engineKind == "vlc") "VLC" else "ExoPlayer"} (تبديل)", "Lecteur : ${if (s.engineKind == "vlc") "VLC" else "ExoPlayer"} (changer)")) } }
                    item { Button(onClick = { if (s.engine?.supportsDelay() == true) s.addSubDelay(-250) else toast(ctx, L.t("التأخير يحتاج مشغّل VLC", "Le décalage nécessite VLC")) }) { Text(L.t("تأخير الترجمة -  (${s.subDelay}ms)", "Décalage sous-titres -  (${s.subDelay}ms)")) } }
                    item { Button(onClick = { if (s.engine?.supportsDelay() == true) s.addSubDelay(250) else toast(ctx, L.t("التأخير يحتاج مشغّل VLC", "Le décalage nécessite VLC")) }) { Text(L.t("تأخير الترجمة +", "Décalage sous-titres +")) } }
                    item { Button(onClick = { if (s.engine?.supportsDelay() == true) s.addAudioDelayMs(-100) else toast(ctx, L.t("التأخير يحتاج مشغّل VLC", "Le décalage nécessite VLC")) }) { Text(L.t("تأخير الصوت -  (${s.audioDelay}ms)", "Décalage audio -  (${s.audioDelay}ms)")) } }
                    item { Button(onClick = { if (s.engine?.supportsDelay() == true) s.addAudioDelayMs(100) else toast(ctx, L.t("التأخير يحتاج مشغّل VLC", "Le décalage nécessite VLC")) }) { Text(L.t("تأخير الصوت +", "Décalage audio +")) } }
                    item { Button(onClick = { s.toggleStable() }) { Text(L.t("التخزين: ${if (s.stable) "مستقر" else "عادي"}", "Tampon : ${if (s.stable) "Stable" else "Normal"}")) } }
                    item { Button(onClick = { s.cycleSleep() }) { Text(L.t("مؤقت النوم: ${s.sleepLabel}", "Minuterie : ${s.sleepLabel}")) } }
                    item { Button(onClick = { overlay = Overlay.NONE; (ctx as? MainActivity)?.enterPip() }) { Text(L.t("صورة داخل صورة", "Image dans l'image")) } }
                    item { Button(onClick = { s.engine?.setPlaying(false); openExternal(ctx, s.currentUrl()) }) { Text(L.t("مشغّل خارجي", "Lecteur externe")) } }
                    item { Button(onClick = { s.retry() }) { Text(L.t("إعادة تحميل", "Recharger")) } }
                    item { Button(onClick = { overlay = Overlay.NONE }) { Text(L.t("إغلاق", "Fermer")) } }
                }
            }
        }

        // ---- track pickers
        if (overlay == Overlay.AUDIO && !showError) {
            val tr = remember(overlay) { s.audioTracks() }
            val entries = if (tr.isEmpty()) listOf(L.t("لا توجد معلومات عن المسارات الصوتية", "Aucune information sur les pistes audio") to false) else tr.map { it.label to it.selected }
            PickerPanel(L.t("مسار الصوت", "Piste audio"), entries, first, Modifier.align(Alignment.CenterEnd)) { i ->
                if (tr.isNotEmpty()) s.engine?.selectAudio(tr[i].id)
                overlay = Overlay.NONE
            }
        }
        if (overlay == Overlay.SUBS && !showError) {
            val tr = remember(overlay) { s.subtitleTracks() }
            val entries = listOf(L.t("إيقاف", "Désactivé") to tr.none { it.selected }) + tr.map { it.label to it.selected }
            PickerPanel(L.t("الترجمة", "Sous-titres"), entries, first, Modifier.align(Alignment.CenterEnd)) { i ->
                if (i == 0) s.engine?.selectSubtitle(-1) else s.engine?.selectSubtitle(tr[i - 1].id)
                overlay = Overlay.NONE
            }
        }
        if (overlay == Overlay.EPISODES && !showError) {
            val entries = s.items.mapIndexed { i, c -> c.name to (i == s.index) }
            PickerPanel(L.t("الحلقات", "Épisodes"), entries, first, Modifier.align(Alignment.CenterEnd)) { i ->
                s.jumpTo(i)
                overlay = Overlay.NONE
            }
        }
    }
}
