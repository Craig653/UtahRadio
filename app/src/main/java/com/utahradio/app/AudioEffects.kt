package com.utahradio.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.session.MediaButtonReceiver
import kotlin.math.abs
import kotlin.math.ln

/**
 * Equalizer presets and volume leveling, attached to the player's audio.
 * Settings changes apply immediately.
 */
class AudioEffects {
    private var session = C.AUDIO_SESSION_ID_UNSET
    private var eq: Equalizer? = null
    private var leveler: DynamicsProcessing? = null

    fun attach(sessionId: Int) {
        if (sessionId == session) { apply(); return }
        release()
        session = sessionId
        apply()
    }

    fun apply() {
        if (session == C.AUDIO_SESSION_ID_UNSET || session == 0) return
        applyEq()
        applyLeveling()
    }

    fun release() {
        runCatching { eq?.release() }; eq = null
        runCatching { leveler?.release() }; leveler = null
    }

    private fun applyEq() {
        val preset = AppSettings.eqPreset
        if (preset == AppSettings.EQ_FLAT) {
            runCatching { eq?.release() }; eq = null
            return
        }
        runCatching {
            val e = eq ?: Equalizer(0, session).also { eq = it }
            val range = e.bandLevelRange
            for (b in 0 until e.numberOfBands) {
                val band = b.toShort()
                val hz = e.getCenterFreq(band) / 1000f
                val mb = (gainDb(preset, hz) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                e.setBandLevel(band, mb.toShort())
            }
            e.enabled = true
        }.onFailure { Log.w("AudioEffects", "Equalizer unavailable: ${it.message}") }
    }

    /** Gentle compression plus a limiter: quiet parts come up, loud ads come down. */
    private fun applyLeveling() {
        if (!AppSettings.leveling || Build.VERSION.SDK_INT < 28) {
            runCatching { leveler?.release() }; leveler = null
            return
        }
        if (leveler != null) return
        runCatching {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, false, 0, true, 1, false, 0, true,
            ).build()
            val d = DynamicsProcessing(0, session, cfg)
            d.setMbcBandAllChannelsTo(
                0,
                DynamicsProcessing.MbcBand(
                    true, 20_000f, 5f, 300f, 3.5f, -28f, 6f, -90f, 1f, 0f, 9f,
                ),
            )
            d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 80f, 10f, -1.5f, 0f))
            d.enabled = true
            leveler = d
        }.onFailure { Log.w("AudioEffects", "Leveling unavailable: ${it.message}") }
    }

    /** dB boost/cut for a frequency in each preset (smooth curve between points). */
    private fun gainDb(preset: Int, hz: Float): Float {
        val points: List<Pair<Float, Float>> = when (preset) {
            AppSettings.EQ_BASS -> listOf(60f to 6f, 230f to 3.5f, 910f to 0f, 3600f to 0f, 14000f to 1f)
            AppSettings.EQ_VOCAL -> listOf(60f to -4f, 230f to -1.5f, 910f to 2f, 3600f to 4f, 14000f to 1f)
            AppSettings.EQ_CAR -> listOf(60f to 3.5f, 230f to 1f, 910f to 0f, 3600f to 2.5f, 14000f to 3f)
            else -> return 0f
        }
        val x = ln(hz.coerceAtLeast(20f))
        if (x <= ln(points.first().first)) return points.first().second
        if (x >= ln(points.last().first)) return points.last().second
        for (i in 0 until points.size - 1) {
            val (f1, g1) = points[i]
            val (f2, g2) = points[i + 1]
            val x1 = ln(f1); val x2 = ln(f2)
            if (x in x1..x2) return g1 + (g2 - g1) * ((x - x1) / (x2 - x1))
        }
        return 0f
    }

    companion object {
        /**
         * Mono and left/right balance, done inside the player. Returns no processor when both are
         * off, so normal listening is untouched.
         */
        fun channelProcessors(): Array<AudioProcessor> {
            val mono = AppSettings.mono
            val b = AppSettings.balance
            if (!mono && abs(b) < 0.02f) return emptyArray()
            val left = if (b > 0) 1f - b else 1f
            val right = if (b < 0) 1f + b else 1f
            val mixer = ChannelMixingAudioProcessor()
            // Mono input → both speakers.
            mixer.putChannelMixingMatrix(ChannelMixingMatrix(1, 2, floatArrayOf(left, right)))
            // Stereo input.
            val stereo = if (mono) floatArrayOf(0.5f * left, 0.5f * right, 0.5f * left, 0.5f * right)
            else floatArrayOf(left, 0f, 0f, right)
            mixer.putChannelMixingMatrix(ChannelMixingMatrix(2, 2, stereo))
            return arrayOf(mixer)
        }
    }
}

/**
 * "Auto-play when Bluetooth connects": when a car stereo or headphones connect, start your last
 * station (after a few seconds, so the audio connection is ready).
 */
class BluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        AppSettings.init(context)
        if (!AppSettings.autoplayBluetooth || RadioService.isPlaying) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        val major = runCatching { device?.bluetoothClass?.majorDeviceClass }.getOrNull()
        // Only audio devices (car stereos, speakers, headphones) — not watches, keyboards, etc.
        if (major != null && major != BluetoothClass.Device.Major.AUDIO_VIDEO) return
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { if (!RadioService.isPlaying) sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY) }
            pending.finish()
        }, 4_000)
    }
}

/** Presses a media key on our player (starts it if needed, resuming your last station). */
fun sendMediaKey(context: Context, keyCode: Int) {
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    context.sendBroadcast(intent)
}
