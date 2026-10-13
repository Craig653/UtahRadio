package com.utahradio.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Starts up app-wide helpers: crash logging, network monitoring, settings. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        StationRepository.init(this)
        AppSettings.init(this)
        NetworkMonitor.start(this)
        DataUsage.init(this)
    }
}

/**
 * Saves a report if the app ever closes unexpectedly. Next time you open the app it offers to
 * share the report (by email, text, etc. — you choose), so problems can be fixed.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                file(app).writeText(
                    "Utah Radio ${BuildConfigInfo.version(app)} crashed " +
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                        "\nThread: ${thread.name}\n${BuildConfigInfo.device()}\n\n" + sw.toString().take(12_000)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? = file(context).takeIf { it.exists() }?.readText()
    fun clear(context: Context) { file(context).delete() }
}

object BuildConfigInfo {
    fun version(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("?")

    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}

/** Is the phone online, and is it on mobile data? */
object NetworkMonitor {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online
    private val _cellular = MutableStateFlow(false)
    val cellular: StateFlow<Boolean> = _cellular
    private var cm: ConnectivityManager? = null

    fun start(context: Context) {
        if (cm != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm = manager
        refresh()
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
            })
        }
    }

    fun refresh() {
        val manager = cm ?: return
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _cellular.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI).not()
    }
}
