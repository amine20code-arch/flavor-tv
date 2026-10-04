@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamtv.iptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.material3.Text as M3Text

enum class Section(val icon: String, private val ar: String, private val fr: String) {
    HOME("🏠", "الرئيسية", "Accueil"),
    SEARCH("🔍", "بحث", "Recherche"),
    LIVE("📺", "البث المباشر", "TV en direct"),
    MOVIES("🎬", "أفلام", "Films"),
    SERIES("🍿", "مسلسلات", "Séries"),
    MATCHES("⚽", "مباريات", "Matchs"),
    RADIO("📻", "راديو", "Radio"),
    SOURCES("➕", "إضافة مصدر", "Ajouter une source"),
    SETTINGS("⚙", "الإعدادات", "Réglages");
    val label: String get() = L.t(ar, fr)
}

typealias OpenFn = (List<ChannelEntity>, Int, Long) -> Unit

private const val FAV_KEY = "\u0001fav"
private const val RECENT_KEY = "\u0001recent"
private const val ALL_KEY = "\u0001all"
private val SPORT_RE = Regex("(?i)sport|bein|ssc|match|football|soccer|ligue|liga|premier|champions|كأس|رياض|مباريات|كرة|foot")
val LocalLite = compositionLocalOf { false }
val LocalIconPad = compositionLocalOf { 14.dp }

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

private fun catName(k: String) = when (k) {
    FAV_KEY -> L.t("★ قائمتي", "★ Ma liste")
    RECENT_KEY -> L.t("⏱ متابعة المشاهدة", "⏱ Reprendre")
    ALL_KEY -> L.t("الكل", "Tout")
    "" -> L.t("بدون فئة", "Sans catégorie")
    else -> k
}

private fun kindLabel(k: String) = when (k) { "live" -> L.t("مباشر", "Direct"); "movie" -> L.t("فيلم", "Film"); "series" -> L.t("مسلسل", "Série"); else -> L.t("راديو", "Radio") }

// =====================================================================================================================
// Home shell: navigation drawer + the section chosen by the user (each section has its own palette and look)
// =====================================================================================================================

@Composable
fun HomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pal = LocalPal.current
    val section = vm.section
    var episodes by remember { mutableStateOf<List<ChannelEntity>?>(null) }

    val open: OpenFn = { list, idx, resume ->
        val t = list[idx]
        if (t.kind == "series" && t.streamUrl.startsWith("series:")) {
            scope.launch {
                val e = vm.episodes(t)
                if (e.isEmpty()) toast(ctx, L.t("لا توجد حلقات", "Aucun épisode")) else episodes = e
            }
        } else vm.playFull(list, idx, resume)
    }

    val iconPad = when (vm.settings.iconSize.int) { 32 -> 26.dp; 44 -> 16.dp; else -> 6.dp }
    CompositionLocalProvider(LocalLite provides vm.settings.lite.on, LocalIconPad provides iconPad) {
        NavigationDrawer(drawerContent = {
            Column(Modifier.fillMaxHeight().background(Color(0xCC000000)).padding(12.dp), verticalArrangement = Arrangement.Center) {
                Section.values().forEach { s ->
                    NavigationDrawerItem(selected = section == s, onClick = { vm.section = s }, leadingContent = { Text(s.icon) }) { Text(s.label) }
                }
            }
        }) {
            Box(Modifier.fillMaxSize().background(pal.brush()).padding(start = 80.dp, top = 14.dp, end = 12.dp)) {
                when (section) {
                    Section.HOME -> HomeSection(vm, open)
                    Section.SEARCH -> SearchScreen(vm, open)
                    Section.LIVE -> BrowseScreen(vm, "live", null, open)
                    Section.MOVIES -> BrowseScreen(vm, "movie", null, open)
                    Section.SERIES -> BrowseScreen(vm, "series", null, open)
                    Section.MATCHES -> BrowseScreen(vm, "live", SPORT_RE, open)
                    Section.RADIO -> BrowseScreen(vm, "radio", null, open)
                    Section.SOURCES -> SourcesScreen(vm)
                    Section.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
        episodes?.let { eps ->
            Dialog(onDismissRequest = { episodes = null }) {
                Box(Modifier.width(560.dp).heightIn(max = 480.dp).background(Color(0xEE111111)).padding(16.dp)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(eps) { i, e ->
                            Button(onClick = { episodes = null; vm.playFull(eps, i) }, modifier = Modifier.fillMaxWidth()) { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 18.sp, color = LocalPal.current.accent2)
}

@Composable
fun HomeSection(vm: MainViewModel, open: OpenFn) {
    val pal = LocalPal.current
    val hist by vm.history.collectAsStateWithLifecycle(emptyList())
    val favs by vm.favorites.collectAsStateWithLifecycle(emptyList())
    val count by vm.itemCount.collectAsStateWithLifecycle()
    val now = remember { java.text.SimpleDateFormat("EEEE d MMMM  ·  HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(L.t("أهلاً بك في Stream DZ", "Bienvenue sur Stream DZ"), fontSize = 34.sp)
                Text(now, color = pal.muted)
            }
        }
        if (count == 0) item {
            Text(L.t("لا توجد قنوات بعد: افتح القائمة الجانبية ← إضافة مصدر.", "Aucune chaîne : ouvrez le menu → Ajouter une source."), color = pal.muted)
        }
        if (hist.isNotEmpty()) item {
            val items = hist.map { it.toItem() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(L.t("متابعة المشاهدة", "Reprendre"))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(items) { i, c -> MediaCard(c, 190.dp, 106.dp, false, false, Modifier, { open(items, i, hist[i].position) }, {}) }
                }
            }
        }
        if (favs.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(L.t("قائمتي", "Ma liste"))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(favs) { i, c -> MediaCard(c, 190.dp, 106.dp, false, false, Modifier, { open(favs, i, 0L) }, { vm.toggleFav(c) }) }
                }
            }
        }
    }
}

// =====================================================================================================================
// Shared building blocks
// =====================================================================================================================

@Composable
fun MediaCard(c: ChannelEntity, w: Dp, h: Dp, selected: Boolean, playing: Boolean, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    val pal = LocalPal.current
    val lite = LocalLite.current
    val crop = c.kind == "movie" || c.kind == "series"
    val iconPad = LocalIconPad.current
    Card(
        onClick = onClick, onLongClick = onLong, modifier = modifier.width(w).height(h),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = CardDefaults.border(
            border = if (selected || playing) Border(BorderStroke(2.dp, pal.accent)) else Border.None,
            focusedBorder = Border(BorderStroke(3.dp, Color.White)))
    ) {
        Box(Modifier.fillMaxSize().background(pal.card)) {
            if (c.logo.isNotBlank() && !lite) {
                AsyncImage(model = c.logo, contentDescription = null, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(if (crop) 0.dp else iconPad))
            } else Text(c.name.take(2), modifier = Modifier.align(Alignment.Center), fontSize = 26.sp, color = pal.muted)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(horizontal = 6.dp, vertical = 5.dp)
            ) {
                Text((if (playing) "● " else "") + c.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
            }
        }
    }
}

class BrowseData(
    val groups: List<GroupCount>, val cat: String, val list: List<ChannelEntity>,
    val favs: List<ChannelEntity>, val hist: List<HistoryEntity>, val pos: Map<Long, Long>,
    val allowAll: Boolean, val setCat: (String) -> Unit, val more: () -> Unit
) {
    /** Group keys shown to the user: special lists first, then the real groups. */
    val keys: List<String> = buildList {
        if (favs.isNotEmpty()) add(FAV_KEY)
        if (hist.isNotEmpty()) add(RECENT_KEY)
        if (allowAll) add(ALL_KEY)
        groups.forEach { add(it.groupTitle) }
    }
    fun count(k: String): Int? = groups.firstOrNull { it.groupTitle == k }?.c
}

@Composable
fun rememberBrowse(vm: MainViewModel, kind: String, only: Regex?): BrowseData? {
    val hide = vm.settings.hideAdult.on
    val gAll by remember(kind, hide) { vm.groups(kind) }.collectAsStateWithLifecycle(emptyList())
    val hist by remember(kind) { vm.historyOf(kind) }.collectAsStateWithLifecycle(emptyList())
    val favs by remember(kind) { vm.favoritesOf(kind) }.collectAsStateWithLifecycle(emptyList())
    val groups = if (only != null) gAll.filter { only.containsMatchIn(it.groupTitle) } else gAll
    val key = if (only != null) "m_$kind" else kind
    val allowAll = only == null
    val want = vm.cats[key]
    val cat = when {
        want == FAV_KEY && favs.isNotEmpty() -> FAV_KEY
        want == RECENT_KEY && hist.isNotEmpty() -> RECENT_KEY
        want == ALL_KEY && allowAll -> ALL_KEY
        want != null && groups.any { it.groupTitle == want } -> want
        allowAll -> ALL_KEY
        else -> groups.firstOrNull()?.groupTitle ?: ALL_KEY
    }
    var limit by remember(key, cat) { mutableIntStateOf(300) }
    val list by remember(key, cat, limit, favs, hist) {
        when (cat) {
            FAV_KEY -> flowOf(favs)
            RECENT_KEY -> flowOf(hist.map { it.toItem() })
            ALL_KEY -> if (allowAll) vm.channelsAll(kind, limit) else flowOf(emptyList<ChannelEntity>())
            else -> vm.channels(kind, cat, limit)
        }
    }.collectAsStateWithLifecycle(emptyList())
    if (groups.isEmpty() && favs.isEmpty() && hist.isEmpty()) return null
    val pos = hist.associate { it.itemId to it.position }
    return BrowseData(
        groups, cat, list, favs, hist, pos, allowAll,
        setCat = { k -> vm.cats[key] = k },
        more = { if (list.size >= limit && cat != FAV_KEY && cat != RECENT_KEY) limit += 300 }
    )
}

/** Everything the layouts need, bundled so each look only has to arrange the same three blocks. */
class BCtx(
    val vm: MainViewModel, val kind: String, val key: String, val b: BrowseData, val sel: ChannelEntity?,
    val open: OpenFn, val onPick: (ChannelEntity, Int) -> Unit, val playFocus: FocusRequester, val playingId: Long?
)

@Composable
fun BrowseScreen(vm: MainViewModel, kind: String, only: Regex?, open: OpenFn) {
    val b = rememberBrowse(vm, kind, only)
    if (b == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                when (kind) {
                    "movie" -> L.t("لا توجد أفلام. أضف مصدر Xtream يحتوي على VOD.", "Aucun film. Ajoutez une source Xtream avec VOD.")
                    "series" -> L.t("لا توجد مسلسلات. أضف مصدر Xtream يحتوي على مسلسلات.", "Aucune série. Ajoutez une source Xtream avec des séries.")
                    "radio" -> L.t("لا توجد محطات راديو.", "Aucune station de radio.")
                    else -> if (only != null) L.t("لا توجد فئات رياضية في مصادرك.", "Aucune catégorie sportive dans vos sources.")
                    else L.t("لا توجد قنوات. افتح القائمة الجانبية وأضف مصدراً.", "Aucune chaîne. Ouvrez le menu latéral et ajoutez une source.")
                }, color = LocalPal.current.muted)
        }
    } else BrowseBody(vm, kind, if (only != null) "m_$kind" else kind, b, open)
}

@Composable
private fun BrowseBody(vm: MainViewModel, kind: String, key: String, b: BrowseData, open: OpenFn) {
    val s = vm.session
    val look = vm.settings.look(vm.section)
    val sel = vm.selected[key]?.takeIf { x -> b.list.any { it.id == x.id } }
    val playFocus = remember { FocusRequester() }
    val playingId = if (s.previewMode) s.item?.id else null

    LaunchedEffect(Unit) {
        if (vm.restoreFocus) { vm.restoreFocus = false; delay(300); runCatching { playFocus.requestFocus() } }
    }
    DisposableEffect(Unit) { onDispose { if (s.previewMode && !s.fullscreen) s.stop() } }

    val onPick: (ChannelEntity, Int) -> Unit = { c, i ->
        vm.selected[key] = c
        val full = vm.settings.clickMode.v == "full"
        when {
            c.kind == "series" -> {}                                   // the detail pane lists the episodes
            c.kind == "movie" -> { if (full) open(b.list, i, if (vm.settings.resume.on) b.pos[c.id] ?: 0L else 0L) }
            s.previewMode && s.item?.id == c.id -> s.fullscreen = true  // second OK on the same channel = fullscreen
            full -> open(b.list, i, 0L)
            else -> s.play(b.list, i, 0L, full = false, preview = true)
        }
    }
    val ctx = BCtx(vm, kind, key, b, sel, open, onPick, playFocus, playingId)
    when (look) {
        "apple" -> AppleLayout(ctx)
        "dtv" -> DtvLayout(ctx)
        "glass" -> GlassLayout(ctx)
        else -> HepiLayout(ctx)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Group selectors (one per look)
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun GroupTiles(c: BCtx) {
    val pal = LocalPal.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(c.b.keys) { _, k ->
            val on = k == c.b.cat
            Card(
                onClick = { c.b.setCat(k) }, modifier = Modifier.width(112.dp).height(84.dp),
                scale = CardDefaults.scale(focusedScale = 1.08f),
                border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White)))
            ) {
                Column(
                    Modifier.fillMaxSize().background(if (on) pal.accent else pal.card).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Text(when (k) { FAV_KEY -> "★"; RECENT_KEY -> "⏱"; ALL_KEY -> "▦"; else -> groupIcon(k) }, fontSize = 22.sp)
                    Text(catName(k), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (on) pal.onAccent else pal.text)
                }
            }
        }
    }
}

@Composable
private fun GroupPills(c: BCtx) {
    val pal = LocalPal.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Color(0x55000000)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(c.b.keys) { _, k ->
                val on = k == c.b.cat
                Card(
                    onClick = { c.b.setCat(k) },
                    scale = CardDefaults.scale(focusedScale = 1.06f),
                    border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(50)))
                ) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.Transparent).padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text(catName(k), fontSize = 14.sp, maxLines = 1, color = if (on) pal.onAccent else Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupSidebar(c: BCtx, width: Dp) {
    val pal = LocalPal.current
    LazyColumn(
        Modifier.width(width).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Color(0x66000000)),
        verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        itemsIndexed(c.b.keys) { _, k ->
            val on = k == c.b.cat
            Card(
                onClick = { c.b.setCat(k) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                scale = CardDefaults.scale(focusedScale = 1.03f),
                border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White)))
            ) {
                Row(
                    Modifier.fillMaxWidth().background(if (on) pal.accent else Color.Transparent).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(catName(k), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = if (on) pal.onAccent else pal.text)
                    c.b.count(k)?.let { n -> Text("$n", fontSize = 11.sp, color = if (on) pal.onAccent else pal.muted) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Item collections
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun ItemsGrid(c: BCtx, modifier: Modifier, minSize: Dp, h: Dp) {
    val state: LazyGridState = rememberLazyGridState(c.vm.scrollPos["g${c.key}${c.b.cat}"] ?: 0)
    DisposableEffect(c.b.cat) { onDispose { c.vm.scrollPos["g${c.key}${c.b.cat}"] = state.firstVisibleItemIndex } }
    val list = c.b.list
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize), state = state, modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 4.dp, start = 4.dp, end = 4.dp)
    ) {
        gridItemsIndexed(list) { i, it ->
            LaunchedEffect(i, list.size) { if (i >= list.size - 12) c.b.more() }
            val playing = c.playingId == it.id
            MediaCard(it, minSize, h, c.sel?.id == it.id, playing,
                if (playing) Modifier.focusRequester(c.playFocus) else Modifier,
                { c.onPick(it, i) }, { c.vm.toggleFav(it) })
        }
    }
}

@Composable
private fun ItemsRow(c: BCtx, w: Dp, h: Dp) {
    val state: LazyListState = rememberLazyListState(c.vm.scrollPos["r${c.key}${c.b.cat}"] ?: 0)
    DisposableEffect(c.b.cat) { onDispose { c.vm.scrollPos["r${c.key}${c.b.cat}"] = state.firstVisibleItemIndex } }
    val list = c.b.list
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
        itemsIndexed(list) { i, it ->
            LaunchedEffect(i, list.size) { if (i >= list.size - 8) c.b.more() }
            val playing = c.playingId == it.id
            MediaCard(it, w, h, c.sel?.id == it.id, playing,
                if (playing) Modifier.focusRequester(c.playFocus) else Modifier,
                { c.onPick(it, i) }, { c.vm.toggleFav(it) })
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The four looks
// ---------------------------------------------------------------------------------------------------------------------

/** Cards and grid with gold highlights: preview on top, category tiles, channel grid. */
@Composable
private fun HepiLayout(c: BCtx) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 170.dp, max = 190.dp)) { DetailPane(c, horizontal = true, style = "hepi") }
        GroupTiles(c)
        ItemsGrid(c, Modifier.weight(1f), if (c.kind == "movie" || c.kind == "series") 120.dp else 150.dp, if (c.kind == "movie" || c.kind == "series") 170.dp else 96.dp)
    }
}

/** Big hero with spaced title and a pill navigation bar; a row of landscape tiles at the bottom. */
@Composable
private fun AppleLayout(c: BCtx) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupPills(c)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val bg = c.sel?.logo.orEmpty()
            if (bg.isNotBlank() && !LocalLite.current) {
                AsyncImage(model = bg, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.22f, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xCC000000), Color.Transparent))))
            DetailPane(c, horizontal = true, style = "apple")
        }
        SectionTitle(L.t("التالي", "À suivre"))
        Box(Modifier.fillMaxWidth().height(146.dp)) { ItemsRow(c, 190.dp, 106.dp) }
    }
}

/** Sidebar with a filled selection, hero on top and a row of posters below. */
@Composable
private fun DtvLayout(c: BCtx) {
    val poster = c.kind == "movie" || c.kind == "series"
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupSidebar(c, 200.dp)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f)) { DetailPane(c, horizontal = true, style = "dtv") }
            SectionTitle(catName(c.b.cat))
            Box(Modifier.fillMaxWidth().height(if (poster) 210.dp else 118.dp)) { ItemsRow(c, if (poster) 128.dp else 180.dp, if (poster) 190.dp else 100.dp) }
        }
    }
}

/** Radio / glass look: groups on one side, station grid, "now playing" panel on the other. */
@Composable
private fun GlassLayout(c: BCtx) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupSidebar(c, 170.dp)
        ItemsGrid(c, Modifier.weight(1f).fillMaxHeight(), 120.dp, 120.dp)
        Box(Modifier.width(300.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0x55000000)).padding(12.dp)) {
            DetailPane(c, horizontal = false, style = "glass")
        }
    }
}

// =====================================================================================================================
// Detail pane: preview for live/radio, details for movies, episodes for series
// =====================================================================================================================

@Composable
private fun DetailPane(c: BCtx, horizontal: Boolean, style: String) {
    val sel = c.sel
    when {
        c.kind == "movie" && sel != null -> MovieDetail(c, sel, horizontal, style)
        c.kind == "series" && sel != null -> SeriesDetail(c, sel, horizontal, style)
        c.kind == "movie" || c.kind == "series" ->
            Box(Modifier.fillMaxSize(), Alignment.CenterStart) { Text(L.t("اختر عنواناً لعرض تفاصيله", "Choisissez un titre pour voir les détails"), color = LocalPal.current.muted) }
        else -> LivePane(c, sel, horizontal, style)
    }
}

@Composable
private fun TitleText(text: String, style: String) {
    if (style == "apple") Text(text.uppercase(), fontSize = 28.sp, letterSpacing = 5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    else Text(text, fontSize = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun LivePane(c: BCtx, sel: ChannelEntity?, horizontal: Boolean, style: String) {
    val vm = c.vm
    val s = vm.session
    val pal = LocalPal.current
    val shown = sel ?: s.item?.takeIf { s.previewMode }
    val epg by produceState(emptyList<EpgEntity>(), shown?.id) {
        value = if (shown != null && shown.tvgId.isNotBlank()) runCatching { vm.repo.nowNext(shown.tvgId) }.getOrDefault(emptyList()) else emptyList()
    }
    val info: @Composable ColumnScope.() -> Unit = {
        if (shown == null) Text(L.t("اختر قناة ثم اضغط OK للمعاينة", "Choisissez une chaîne puis OK pour l'aperçu"), color = pal.muted)
        else {
            TitleText(shown.name, style)
            if (shown.groupTitle.isNotBlank()) Text(shown.groupTitle, fontSize = 13.sp, color = pal.muted)
            epg.getOrNull(0)?.let { Text(L.t("الآن: ${it.title}", "Maintenant : ${it.title}"), fontSize = 14.sp) }
            epg.getOrNull(1)?.let { Text(L.t("التالي: ${it.title}", "Ensuite : ${it.title}"), fontSize = 13.sp, color = pal.muted) }
            if (s.error != null && s.previewMode && !s.fullscreen) Text(s.error ?: "", fontSize = 12.sp, color = Color(0xFFFF9999), maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { if (s.previewMode && s.item?.id == shown.id) s.fullscreen = true else c.open(c.b.list, c.b.list.indexOfFirst { it.id == shown.id }.coerceAtLeast(0), 0L) }) { Text(L.t("ملء الشاشة", "Plein écran")) }
                Button(onClick = { vm.toggleFav(shown) }) { Text(L.t("قائمتي", "Ma liste")) }
            }
        }
    }
    if (horizontal) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MiniPlayer(vm, Modifier.width(300.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = info)
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniPlayer(vm, Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = info)
        }
    }
}

private fun openTrailer(ctx: Context, link: String) {
    val url = if (link.startsWith("http")) link else "https://www.youtube.com/watch?v=$link"
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun MovieDetail(c: BCtx, m: ChannelEntity, horizontal: Boolean, style: String) {
    val vm = c.vm
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pal = LocalPal.current
    val saved = c.b.pos[m.id] ?: 0L
    val idx = c.b.list.indexOfFirst { it.id == m.id }.coerceAtLeast(0)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (m.logo.isNotBlank() && !LocalLite.current) {
            AsyncImage(model = m.logo, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(if (horizontal) 118.dp else 130.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TitleText(m.name, style)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (m.rating.isNotBlank() && m.rating != "0") Text("★ ${m.rating}", color = Color(0xFFF5C518))
                if (m.groupTitle.isNotBlank()) Text(m.groupTitle, color = pal.muted, fontSize = 13.sp)
            }
            if (m.plot.isNotBlank()) Text(m.plot, maxLines = if (horizontal) 3 else 6, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { c.open(c.b.list, idx, if (vm.settings.resume.on) saved else 0L) }) {
                    Text(if (saved > 5000 && vm.settings.resume.on) L.t("متابعة من ${fmtTime(saved)}", "Reprendre à ${fmtTime(saved)}") else L.t("تشغيل", "Lecture"))
                }
                if (saved > 5000 && vm.settings.resume.on) Button(onClick = { c.open(c.b.list, idx, 0L) }) { Text(L.t("من البداية", "Depuis le début")) }
                Button(onClick = { scope.launch { val t = vm.trailer(m); if (t == null) toast(ctx, L.t("لا يوجد إعلان", "Pas de bande-annonce")) else openTrailer(ctx, t) } }) { Text(L.t("الإعلان", "Bande-annonce")) }
                Button(onClick = { vm.toggleFav(m) }) { Text(L.t("قائمتي", "Ma liste")) }
            }
        }
    }
}

@Composable
private fun SeriesDetail(c: BCtx, sr: ChannelEntity, horizontal: Boolean, style: String) {
    val vm = c.vm
    val pal = LocalPal.current
    val eps by produceState<List<ChannelEntity>?>(null, sr.id) { value = vm.episodes(sr) }
    val hist by remember { vm.historyOf("movie") }.collectAsStateWithLifecycle(emptyList())
    val posMap = hist.associate { it.itemId to it.position }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TitleText(sr.name, style)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sr.rating.isNotBlank() && sr.rating != "0") Text("★ ${sr.rating}", color = Color(0xFFF5C518))
            if (sr.groupTitle.isNotBlank()) Text(sr.groupTitle, color = pal.muted, fontSize = 13.sp)
        }
        if (sr.plot.isNotBlank()) Text(sr.plot, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        val list = eps
        when {
            list == null -> Text(L.t("جاري تحميل الحلقات...", "Chargement des épisodes..."), color = pal.muted)
            list.isEmpty() -> Text(L.t("لا توجد حلقات", "Aucun épisode"), color = pal.muted)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.playFull(list, 0, if (vm.settings.resume.on) posMap[list[0].id] ?: 0L else 0L) }) { Text(L.t("تشغيل الحلقة الأولى", "Lire le premier épisode")) }
                    Button(onClick = { vm.toggleFav(sr) }) { Text(L.t("قائمتي", "Ma liste")) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(list) { i, e ->
                        Card(onClick = { vm.playFull(list, i, if (vm.settings.resume.on) posMap[e.id] ?: 0L else 0L) }, modifier = Modifier.width(190.dp),
                            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White)))) {
                            Box(Modifier.fillMaxWidth().background(pal.card).padding(10.dp)) {
                                Text((if ((posMap[e.id] ?: 0L) > 5000) "▶ " else "") + e.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================================================================
// Search, sources, settings, profiles
// =====================================================================================================================

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, onFocus: () -> Unit = {}, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { M3Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.NumberPassword) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() })
}

@Composable
fun SearchScreen(vm: MainViewModel, open: OpenFn) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    LaunchedEffect(Unit) { RemoteBus.text.collect { q += it } }
    LaunchedEffect(q) { if (q.length >= 2) { delay(300); results = vm.search(q) } else results = emptyList() }
    Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(q, { q = it }, L.t("ابحث عن قناة أو فيلم أو مسلسل (لوحة مفاتيح الهاتف تعمل أيضاً)", "Rechercher une chaîne, un film ou une série (le clavier du téléphone fonctionne aussi)"))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            itemsIndexed(results) { i, c ->
                Card(onClick = { open(results, i, 0L) }, modifier = Modifier.fillMaxWidth(),
                    border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White)))) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(c.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(kindLabel(c.kind), fontSize = 12.sp, color = LocalPal.current.muted)
                    }
                }
            }
        }
    }
}

@Composable
fun SourcesScreen(vm: MainViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var mode by remember { mutableIntStateOf(0) } // 0 M3U, 1 Xtream, 2 Stalker
    var focused by remember { mutableIntStateOf(0) }
    val v = remember { mutableStateListOf("", "", "", "", "") } // 0 name, 1 url, 2 user/mac, 3 pass, 4 epg
    LaunchedEffect(Unit) { RemoteBus.text.collect { v[focused] = v[focused] + it } }
    val idxs = when (mode) { 0 -> listOf(0, 1, 4); 1 -> listOf(0, 1, 2, 3); else -> listOf(0, 1, 2) }
    fun label(i: Int) = when (i) {
        0 -> L.t("الاسم", "Nom")
        1 -> listOf(L.t("رابط القائمة (.m3u / .m3u8)", "Lien de la liste (.m3u / .m3u8)"), L.t("الخادم (http://host:port)", "Serveur (http://host:port)"), L.t("رابط البوابة (http://host/c/)", "Lien du portail (http://host/c/)"))[mode]
        2 -> if (mode == 1) L.t("اسم المستخدم", "Nom d'utilisateur") else L.t("عنوان MAC (00:1A:79:xx:xx:xx)", "Adresse MAC (00:1A:79:xx:xx:xx)")
        3 -> L.t("كلمة المرور", "Mot de passe")
        else -> L.t("رابط EPG بصيغة XMLTV (اختياري)", "Lien EPG XMLTV (facultatif)")
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(620.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        item { Text(L.t("إضافة مصدر", "Ajouter une source"), fontSize = 26.sp) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("M3U", "Xtream", "Stalker").forEachIndexed { i, n -> Button(onClick = { mode = i; focused = 0 }) { Text(if (mode == i) "● $n" else n) } }
            }
        }
        idxs.forEach { i -> item(key = "f$i-$mode") { Field(v[i], { v[i] = it }, label(i), { focused = i }) } }
        item {
            Button(enabled = !busy, onClick = {
                when (mode) { 0 -> vm.addM3u(v[0], v[1], v[4]); 1 -> vm.addXtream(v[0], v[1], v[2], v[3]); else -> vm.addStalker(v[0], v[1], v[2]) }
            }) { Text(if (busy) L.t("جاري الاستيراد...", "Importation...") else L.t("استيراد", "Importer")) }
        }
        item { Text(status) }
        item { Text(L.t("مصادرك", "Vos sources"), fontSize = 20.sp) }
        itemsIndexed(playlists) { _, p ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.name} (${p.type})", modifier = Modifier.width(280.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.type != "stalker") Button(onClick = { vm.refreshEpg(p) }) { Text("EPG") }
                Button(onClick = { vm.removePlaylist(p) }) { Text(L.t("حذف", "Supprimer")) }
            }
        }
    }
}

@Composable
private fun OptButton(title: String, o: Opt) {
    Button(onClick = { o.cycle() }) { Text("$title: ${o.label}") }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 20.sp, color = LocalPal.current.accent2)
        content()
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val st = vm.settings
    val profile by vm.profile.collectAsStateWithLifecycle()
    LazyColumn(Modifier.width(900.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
        item {
            SettingsGroup(L.t("اللغة / Langue", "Langue / اللغة")) {
                Button(onClick = { vm.setLang(!L.fr) }) { Text(if (L.fr) "Français  →  العربية" else "العربية  →  Français") }
            }
        }
        item {
            SettingsGroup(L.t("المشغّل (حل مشاكل التشغيل)", "Lecteur (problèmes de lecture)")) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OptButton(L.t("المحرك", "Moteur"), st.engine)
                    OptButton(L.t("التخزين المؤقت", "Tampon"), st.buffer)
                    OptButton(L.t("فك الترميز العتادي", "Décodage matériel"), st.hw)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OptButton(L.t("هوية الطلب (User-Agent)", "User-Agent"), st.ua)
                    OptButton(L.t("أبعاد الصورة", "Format d'image"), st.aspect)
                    OptButton(L.t("القفزة", "Saut"), st.seek)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OptButton(L.t("استئناف الأفلام", "Reprise des films"), st.resume)
                    OptButton(L.t("الحلقة التالية تلقائياً", "Épisode suivant auto"), st.autoNext)
                    OptButton(L.t("حجم الترجمة", "Taille sous-titres"), st.subSize)
                }
                Text(L.t("إذا لم يعمل قناة: افتحها ثم اضغط OK ← «فحص الرابط» ليخبرك التطبيق بالسبب الحقيقي (اشتراك، حد اتصالات، صيغة...).",
                    "Si une chaîne ne marche pas : ouvrez-la puis « Analyser le lien » indique la vraie cause (abonnement, limite de connexions, format...)."), fontSize = 12.sp, color = LocalPal.current.muted)
            }
        }
        item {
            SettingsGroup(L.t("التصفح", "Navigation")) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OptButton(L.t("النقر على القناة", "Clic sur une chaîne"), st.clickMode)
                    OptButton(L.t("الشعارات", "Logos"), st.iconSize)
                    OptButton(L.t("وضع خفيف (بلا صور)", "Mode léger (sans images)"), st.lite)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OptButton(L.t("إخفاء فئات البالغين", "Masquer le contenu adulte"), st.hideAdult)
                    OptButton(L.t("مدة EPG", "Durée EPG"), st.epgHours)
                }
            }
        }
        item {
            SettingsGroup(L.t("التيمات (لكل قسم تيمة وشكل)", "Thèmes (un thème et un style par section)")) {
                Text(L.t("البث المباشر", "TV en direct"), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OptButton(L.t("الشكل", "Style"), st.lookLive); OptButton(L.t("اللون", "Couleurs"), st.palLive) }
                Text(L.t("أفلام", "Films"), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OptButton(L.t("الشكل", "Style"), st.lookMovies); OptButton(L.t("اللون", "Couleurs"), st.palMovies) }
                Text(L.t("مسلسلات", "Séries"), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OptButton(L.t("الشكل", "Style"), st.lookSeries); OptButton(L.t("اللون", "Couleurs"), st.palSeries) }
                Text(L.t("راديو", "Radio"), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OptButton(L.t("الشكل", "Style"), st.lookRadio); OptButton(L.t("اللون", "Couleurs"), st.palRadio) }
                Text(L.t("الرئيسية والإعدادات", "Accueil et réglages"), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OptButton(L.t("اللون", "Couleurs"), st.palHome) }
            }
        }
        item {
            SettingsGroup(L.t("الحساب والبيانات", "Profil et données")) {
                Text(L.t("الملف الشخصي: ${profile?.name ?: ""}${if (profile?.isKids == true) " (أطفال)" else ""}", "Profil : ${profile?.name ?: ""}${if (profile?.isKids == true) " (enfants)" else ""}"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.select(null) }) { Text(L.t("تبديل الملف الشخصي", "Changer de profil")) }
                    Button(onClick = { vm.clearHistory() }) { Text(L.t("مسح السجل", "Effacer l'historique")) }
                    Button(onClick = { vm.clearFavorites() }) { Text(L.t("مسح قائمتي", "Vider ma liste")) }
                }
            }
        }
        item {
            SettingsGroup(L.t("ريموت الهاتف", "Télécommande téléphone")) {
                val url = RemoteInfo.url
                if (url.isBlank()) Text(L.t("خدمة الريموت غير متاحة (المنفذ مستعمل أو لا توجد شبكة).", "Télécommande indisponible (port utilisé ou pas de réseau)."))
                else {
                    Text(L.t("امسح الرمز بهاتف على نفس الشبكة: أسهم، لوحة مفاتيح، وبث رابط.", "Scannez avec un téléphone sur le même réseau : flèches, clavier et lecture d'un lien."), color = LocalPal.current.muted)
                    Image(bitmap = remember(url) { qr(url, 320) }, contentDescription = "QR", modifier = Modifier.size(200.dp).background(Color.White).padding(8.dp))
                    Text(url, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(vm: MainViewModel) {
    val pal = LocalPal.current
    val ps by vm.profiles.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<ProfileEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(pal.brush()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(L.t("من يشاهد؟", "Qui regarde ?"), fontSize = 34.sp)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ps.forEach { p ->
                Card(onClick = { if (p.pin.isBlank()) vm.select(p) else pinFor = p }, onLongClick = { vm.deleteProfile(p) },
                    scale = CardDefaults.scale(focusedScale = 1.12f),
                    border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White)))) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(100.dp).background(pal.accent, RoundedCornerShape(12.dp)), Alignment.Center) { Text(p.name.take(1).uppercase(), fontSize = 40.sp, color = pal.onAccent) }
                        Spacer(Modifier.height(8.dp))
                        Text(p.name + if (p.isKids) L.t(" (أطفال)", " (enfants)") else "")
                        if (p.pin.isNotBlank()) Text("🔒", fontSize = 12.sp)
                    }
                }
            }
            Card(onClick = { adding = true }, scale = CardDefaults.scale(focusedScale = 1.12f)) {
                Box(Modifier.size(132.dp, 160.dp), Alignment.Center) { Text(L.t("+  إضافة", "+  Ajouter"), fontSize = 22.sp) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(L.t("اضغط مطولاً على OK فوق الملف لحذفه", "Appui long sur OK pour supprimer un profil"), color = pal.muted, fontSize = 12.sp)
    }
    pinFor?.let { p ->
        var pin by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { pinFor = null }) {
            Column(Modifier.background(Color(0xEE111111)).padding(24.dp).width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L.t("الرمز السري لـ ${p.name}", "Code PIN de ${p.name}"))
                Field(pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, L.t("الرمز", "PIN"), secret = true)
                if (wrong) Text(L.t("رمز خاطئ", "Code incorrect"), color = Color(0xFFFF6666))
                Button(onClick = { if (pin == p.pin) { pinFor = null; vm.select(p) } else wrong = true }) { Text(L.t("موافق", "OK")) }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }
        var kids by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { adding = false }) {
            Column(Modifier.background(Color(0xEE111111)).padding(24.dp).width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L.t("ملف جديد", "Nouveau profil"))
                Field(name, { name = it }, L.t("الاسم", "Nom"))
                Field(pin, { pin = it.filter(Char::isDigit).take(8) }, L.t("رمز سري (اختياري)", "Code PIN (facultatif)"), secret = true)
                Button(onClick = { kids = !kids }) { Text(if (kids) L.t("ملف أطفال: مفعّل (إخفاء فئات البالغين)", "Profil enfants : activé (catégories adultes masquées)") else L.t("ملف أطفال: متوقف", "Profil enfants : désactivé")) }
                Button(onClick = { vm.addProfile(name, pin, kids); adding = false }) { Text(L.t("إنشاء", "Créer")) }
            }
        }
    }
}
