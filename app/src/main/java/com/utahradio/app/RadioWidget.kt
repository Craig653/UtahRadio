package com.utahradio.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.media3.session.MediaButtonReceiver

/** Home-screen widget: what's playing, play/stop and next station. */
class RadioWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        RadioWidget.refresh(context)
    }
}

object RadioWidget {
    private data class Shown(
        val stationId: String?, val song: String?, val artist: String?, val cover: String?, val playing: Boolean,
    )
    private var last: Shown? = null

    /** Redraws with the last known state (or your last station, ready to play). */
    fun refresh(context: Context) {
        val l = last
        if (l != null) {
            draw(context, findStation(context, l.stationId), l.song, l.artist, l.cover, l.playing)
        } else {
            val st = StationRepository.byId(context, StationPrefs(context).lastStationId)
                ?: StationRepository.all(context).firstOrNull()
            draw(context, st, null, null, null, false)
        }
    }

    fun update(context: Context, station: Station?, song: String?, artist: String?, coverKey: String?, playing: Boolean) {
        val shown = Shown(station?.id, song, artist, coverKey, playing)
        if (shown == last) return
        last = shown
        draw(context, station ?: StationRepository.byId(context, StationPrefs(context).lastStationId), song, artist, coverKey, playing)
    }

    private fun draw(context: Context, station: Station?, song: String?, artist: String?, coverKey: String?, playing: Boolean) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val component = ComponentName(context, RadioWidgetProvider::class.java)
        val ids = runCatching { manager.getAppWidgetIds(component) }.getOrNull()
        if (ids == null || ids.isEmpty()) return

        val views = RemoteViews(context.packageName, R.layout.widget_radio)
        views.setTextViewText(R.id.widget_station, station?.name ?: context.getString(R.string.app_name))
        views.setTextViewText(R.id.widget_title, song ?: if (station != null) (if (playing) "Live radio" else "Tap play to listen") else "Add a station to start")
        val sub = artist ?: station?.subtitle ?: ""
        views.setTextViewText(R.id.widget_artist, sub)
        views.setViewVisibility(R.id.widget_artist, if (sub.isBlank()) View.GONE else View.VISIBLE)

        art(context, station, coverKey)?.let { views.setImageViewBitmap(R.id.widget_art, it) }

        views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_stop else R.drawable.ic_play)
        views.setOnClickPendingIntent(
            R.id.widget_play,
            mediaButton(context, if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY, 1),
        )
        views.setOnClickPendingIntent(R.id.widget_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT, 2))
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        runCatching { manager.updateAppWidget(component, views) }
    }

    private fun art(context: Context, station: Station?, coverKey: String?): Bitmap? = runCatching {
        val cover = coverKey?.let { CoverArt.file(context, it) }?.takeIf { it.exists() }
        val bmp = if (cover != null) BitmapFactory.decodeFile(cover.absolutePath)
        else station?.let { ArtStore.bitmap(context, it) }
        bmp?.let { Bitmap.createScaledBitmap(it, 192, 192, true) }
    }.getOrNull()

    private fun mediaButton(context: Context, keyCode: Int, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
