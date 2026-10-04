package com.streamtv.iptv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** A colour palette. Three of them are inspired by the reference screenshots (gold cards, rust cinema, crimson sidebar). */
data class Pal(
    val id: String, val ar: String, val fr: String,
    val bg: Color, val bg2: Color, val card: Color, val card2: Color,
    val accent: Color, val accent2: Color, val text: Color, val muted: Color, val onAccent: Color
) {
    val name: String get() = L.t(ar, fr)
    fun brush(): Brush = Brush.verticalGradient(listOf(bg2, bg))
}

val PALS = listOf(
    Pal("gold", "ذهبي داكن", "Or sombre", Color(0xFF101010), Color(0xFF211B10), Color(0xFF242424), Color(0xFF2F2A20),
        Color(0xFFB8975A), Color(0xFFE0C27C), Color.White, Color(0xFFA39C8C), Color(0xFF14110A)),
    Pal("rust", "برتقالي سينمائي", "Orange cinéma", Color(0xFF220E06), Color(0xFFA9501B), Color(0x66000000), Color(0x99000000),
        Color(0xFFF2E6DA), Color(0xFFFFFFFF), Color.White, Color(0xFFE5C3A8), Color(0xFF2A1208)),
    Pal("crimson", "أزرق وأحمر", "Bleu et rouge", Color(0xFF0A2231), Color(0xFF123A52), Color(0xFF123349), Color(0xFF1B4560),
        Color(0xFFD9441E), Color(0xFFF26A3B), Color.White, Color(0xFF8FB0C4), Color.White),
    Pal("algeria", "الجزائر", "Algérie", Color(0xFF04180F), Color(0xFF0B3A24), Color(0xFF0C2A1C), Color(0xFF13422C),
        Color(0xFF0F9D58), Color(0xFFD21034), Color.White, Color(0xFF9FC7B2), Color.White),
    Pal("midnight", "أزرق ليلي", "Bleu nuit", Color(0xFF0B1530), Color(0xFF162A5C), Color(0xFF121C3B), Color(0xFF1B2A55),
        Color(0xFF2E7BFF), Color(0xFF6FA8FF), Color.White, Color(0xFF8FA0C8), Color.White),
    Pal("amoled", "أسود وأحمر", "Noir et rouge", Color(0xFF000000), Color(0xFF1A0003), Color(0xFF121212), Color(0xFF1E1E1E),
        Color(0xFFE50914), Color(0xFFFF4B55), Color.White, Color(0xFF9A9A9A), Color.White),
    Pal("neon", "بنفسجي", "Violet", Color(0xFF120B2E), Color(0xFF2A1260), Color(0xFF1C1245), Color(0xFF2A1B63),
        Color(0xFFB026FF), Color(0xFFFF4FD8), Color.White, Color(0xFFB7A6E0), Color.White)
)

val LocalPal = staticCompositionLocalOf { PALS[0] }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SectionTheme(p: Pal, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.text, surface = p.card, onSurface = p.text)) {
        androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = p.accent)) {
            CompositionLocalProvider(LocalPal provides p) { content() }
        }
    }
}

fun groupIcon(name: String): String {
    val n = name.lowercase()
    return when {
        Regex("sport|bein|ssc|match|كرة|رياض|مباريات|foot").containsMatchIn(n) -> "⚽"
        Regex("news|أخبار|اخبار|info|actualit").containsMatchIn(n) -> "📰"
        Regex("kid|child|cartoon|أطفال|اطفال|enfant|junior").containsMatchIn(n) -> "🧸"
        Regex("movie|film|cinema|أفلام|افلام").containsMatchIn(n) -> "🎬"
        Regex("series|مسلسل|serie|drama").containsMatchIn(n) -> "🍿"
        Regex("music|موسيق|اغاني|أغاني|musique").containsMatchIn(n) -> "🎵"
        Regex("quran|islam|قرآن|قران|إسلام|اسلام|religio").containsMatchIn(n) -> "🕌"
        Regex("doc|وثائق|nature|discovery|geo").containsMatchIn(n) -> "🌍"
        Regex("radio|راديو|إذاعة|اذاعة").containsMatchIn(n) -> "📻"
        Regex("algeria|dz|جزائر|alg").containsMatchIn(n) -> "🇩🇿"
        else -> "📺"
    }
}
