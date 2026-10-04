package com.streamtv.iptv

import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Result of looking at what a stream URL really returns (independent from any player). */
data class Probe(
    val code: Int, val type: String, val kind: String, val finalUrl: String,
    val snippet: String, val error: String?
)

object Diag {
    /** Opens the URL like a player would (same User-Agent / headers), reads the first bytes and classifies them. Blocking: call on IO. */
    fun probe(st: Stream, url: String): Probe {
        val client = Net.http.newBuilder().readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
        return try {
            val rb = Request.Builder().url(url).header("User-Agent", st.ua)
            st.headers.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { r ->
                val buf = ByteArray(8192)
                var n = 0
                r.body?.byteStream()?.let { s ->
                    while (n < buf.size) { val x = s.read(buf, n, buf.size - n); if (x <= 0) break; n += x }
                }
                val ct = r.header("Content-Type").orEmpty()
                Probe(r.code, ct, classify(buf, n, ct), r.request.url.toString(), textOf(buf, n), null)
            }
        } catch (e: Exception) {
            Probe(0, "", "ERROR", url, "", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun textOf(b: ByteArray, n: Int): String =
        String(b, 0, minOf(n, 160), Charsets.UTF_8).filter { it.code in 32..126 || it.code > 160 }.trim().take(100)

    private fun classify(b: ByteArray, n: Int, ct: String): String {
        if (n == 0) return "EMPTY"
        val head = String(b, 0, minOf(n, 64), Charsets.ISO_8859_1)
        return when {
            head.startsWith("#EXTM3U") -> "HLS"
            n >= 376 && b[0] == 0x47.toByte() && b[188] == 0x47.toByte() -> "TS"
            n >= 12 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "MP4"
            n >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "MKV"
            head.startsWith("FLV") -> "FLV"
            head.contains("<html", true) || head.startsWith("<!") -> "HTML"
            head.startsWith("{") || head.startsWith("[") -> "JSON"
            ct.startsWith("audio/") || head.startsWith("ID3") || (n > 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) -> "AUDIO"
            else -> "UNKNOWN"
        }
    }

    /** Human-readable verdict (Arabic / French). */
    fun explain(p: Probe): String {
        val sn = if (p.snippet.isNotBlank()) "\n«${p.snippet}»" else ""
        return when {
            p.error != null -> L.t("تعذر الوصول للخادم: ${p.error}", "Serveur injoignable : ${p.error}")
            p.code == 401 || p.code == 403 -> L.t("الخادم رفض الطلب (HTTP ${p.code}): الاشتراك منتهٍ، أو بلغتَ حد الاتصالات، أو الجهاز/الـIP محظور.",
                "Le serveur refuse (HTTP ${p.code}) : abonnement expiré, limite de connexions atteinte, ou appareil/IP bloqué.")
            p.code == 404 -> L.t("الرابط غير موجود (HTTP 404): القناة محذوفة أو الرابط خاطئ.", "Lien introuvable (HTTP 404) : chaîne supprimée ou lien erroné.")
            p.code >= 500 || p.code == 0 -> L.t("خطأ من الخادم (HTTP ${p.code}).", "Erreur du serveur (HTTP ${p.code}).")
            p.code >= 400 -> L.t("رفض الخادم الطلب (HTTP ${p.code}).", "Requête refusée (HTTP ${p.code}).")
            p.kind == "HTML" || p.kind == "JSON" -> L.t("الخادم ردّ برسالة بدل البث، غالباً حد اتصالات أو اشتراك منتهٍ.$sn", "Le serveur renvoie un message au lieu du flux (limite de connexions / abonnement ?).$sn")
            p.kind == "EMPTY" -> L.t("الخادم ردّ بلا بيانات (القناة متوقفة أو الاتصالات ممتلئة).", "Réponse vide (chaîne arrêtée ou connexions saturées).")
            p.kind == "UNKNOWN" -> L.t("صيغة غير معروفة.$sn", "Format inconnu.$sn")
            else -> L.t("الرابط سليم (النوع ${p.kind}) والمشكلة في ترميز الفيديو/الصوت على هذا الجهاز: جرّب المشغّل الآخر.",
                "Le lien est valide (type ${p.kind}) ; le souci vient du codec sur cet appareil : essayez l'autre lecteur.")
        }
    }

    /** Hides credentials before showing a URL on screen. */
    fun mask(u: String): String =
        u.replace(Regex("(/(?:live|movie|series)/)[^/]+/[^/]+/"), "$1***/***/")
            .replace(Regex("(?i)(password|token|play_token|mac)=[^&]+"), "$1=***")
}
