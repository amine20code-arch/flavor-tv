package com.streamtv.iptv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Arabic / French. Reading L.fr inside a composable makes the whole UI refresh when the language changes. */
object L {
    var fr by mutableStateOf(false)
    fun t(ar: String, fr: String): String = if (this.fr) fr else ar
}
