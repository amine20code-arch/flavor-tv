package com.streamtv.iptv

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import java.io.File
import kotlin.system.exitProcess

/**
 * Memory-aware image loading + crash guard: an unexpected crash is written to a file, the app restarts itself
 * and shows the error once, so it never just disappears.
 */
class StreamApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        installCrashGuard()
    }

    private fun installCrashGuard() {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                File(filesDir, "last_crash.txt").writeText(Log.getStackTraceString(e))
                val sp = getSharedPreferences("crash", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val last = sp.getLong("ts", 0L)
                sp.edit().putLong("ts", now).commit()
                if (now - last > 20_000) { // avoid restart loops
                    val i = packageManager.getLaunchIntentForPackage(packageName)
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        val pi = PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
                        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, now + 700, pi)
                    }
                }
            } catch (t: Throwable) {
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(150L * 1024 * 1024).build() }
        .bitmapConfig(Bitmap.Config.RGB_565).crossfade(false).build()

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) { imageLoader.memoryCache?.clear(); System.gc() }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var server: RemoteServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startRemote()
        val crashFile = File(filesDir, "last_crash.txt")
        setContent {
            val profile by vm.profile.collectAsStateWithLifecycle()
            var crash by remember { mutableStateOf(if (crashFile.exists()) runCatching { crashFile.readText() }.getOrNull() else null) }
            val s = vm.session
            // The language flag is read here so the whole tree refreshes when it changes.
            val pal = vm.settings.pal(vm.section)
            LaunchedEffect(Unit) {
                RemoteBus.play.collect { u ->
                    s.play(listOf(ChannelEntity(playlistId = 0, kind = "live", name = L.t("بث من الهاتف", "Flux depuis le téléphone"), streamUrl = u)), 0, full = true, preview = false)
                }
            }
            SectionTheme(pal) {
                Surface(Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = pal.bg, contentColor = Color.White)) {
                    when {
                        s.fullscreen && s.item != null -> FullPlayer(vm)
                        profile == null -> ProfileScreen(vm)
                        else -> HomeScreen(vm)
                    }
                    crash?.let { text ->
                        Dialog(onDismissRequest = { crashFile.delete(); crash = null }) {
                            Column(Modifier.width(700.dp).background(Color(0xEE111111)).padding(20.dp)) {
                                Text(L.t("تعافى التطبيق من انهيار. أرسل هذا النص للمطوّر:", "L'application a récupéré d'un plantage. Envoyez ce texte au développeur :"), fontSize = 14.sp)
                                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                                    Text(text.take(1800), fontSize = 10.sp, color = Color(0xFFFF9999))
                                }
                                Button(onClick = { crashFile.delete(); crash = null }) { Text("OK") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startRemote() {
        val token = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        try {
            // keys are delivered inside the app (Instrumentation needs a system permission that normal apps do not have)
            val srv = RemoteServer(8080, token) { code ->
                runOnUiThread {
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
            }
            srv.start(); server = srv
            RemoteInfo.url = "http://${localIp()}:8080/?k=$token"
        } catch (e: Exception) { RemoteInfo.url = "" }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.session.fullscreen && vm.session.item != null) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.session.inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        val pip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        if (!pip) vm.session.engine?.setPlaying(false) // never keep playing in the background
    }

    override fun onStart() {
        super.onStart()
        if (vm.session.item != null) vm.session.engine?.setPlaying(true)
    }

    // Focus-loss safety net: a D-pad key with nothing focused re-grabs focus instead of leaving the UI stuck.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && window.decorView.findFocus() == null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER -> window.decorView.requestFocus()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() { server?.stop(); super.onDestroy() }
}
