package com.utahradio.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Station pictures. Each station gets a 256×256 PNG in the app's storage:
 * the built-in picture, the station's logo (downloaded when you add it), or a generated tile.
 */
object ArtStore {
    private const val SIZE = 256

    private fun dir(context: Context) = File(context.filesDir, "art").apply { mkdirs() }
    private fun file(context: Context, id: String) = File(dir(context), "${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.png")

    /** Returns the picture file for a station, creating it if needed. */
    fun ensureFile(context: Context, station: Station): File {
        val f = file(context, station.id)
        if (f.exists() && f.length() > 0) return f
        val bitmap = if (station.builtIn && station.artRes != 0) {
            BitmapFactory.decodeResource(context.resources, station.artRes)
                ?.let { Bitmap.createScaledBitmap(it, SIZE, SIZE, true) }
                ?: tile(station.name)
        } else {
            tile(station.name)
        }
        write(f, bitmap)
        return f
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
    }

    /** Called after a rename so a generated tile shows the new name. */
    fun refreshTile(context: Context, station: Station) {
        if (station.artKind == "tile") file(context, station.id).delete()
    }

    fun bitmap(context: Context, station: Station): Bitmap? =
        BitmapFactory.decodeFile(ensureFile(context, station).absolutePath)

    /**
     * Downloads a station's logo (call off the main thread). Returns true if a logo was saved;
     * false means the generated tile will be used.
     */
    fun downloadLogo(context: Context, stationId: String, logoUrl: String?): Boolean {
        if (logoUrl.isNullOrBlank() || !logoUrl.startsWith("http")) return false
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(logoUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "UtahRadio/1.1 (Android)")
            if (conn.responseCode !in 200..299) return false
            val bytes = conn.inputStream.use { it.readBytes() }
            val logo = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            if (logo.width < 48 || logo.height < 48) return false // tiny favicons look blurry
            write(file(context, stationId), framed(logo))
            true
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun write(f: File, bitmap: Bitmap) {
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Puts a logo of any shape on a white square. */
    private fun framed(logo: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val pad = SIZE * 0.08f
        val box = SIZE - 2 * pad
        val scale = minOf(box / logo.width, box / logo.height)
        val w = logo.width * scale
        val h = logo.height * scale
        val dst = RectF((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2)
        c.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private val PALETTE = listOf(
        0xFFE84A5F to 0xFF6E2378, 0xFF2496C8 to 0xFF1E3C8C, 0xFF2BAE66 to 0xFF14595B,
        0xFFF2994A to 0xFFB33A3A, 0xFF8E54E9 to 0xFF4776E6, 0xFF11998E to 0xFF38EF7D,
        0xFFFC5C7D to 0xFF6A82FB, 0xFFDA4453 to 0xFF89216B,
    )

    /** A colorful square with the station's name on it. */
    fun tile(name: String): Bitmap {
        val (c1, c2) = PALETTE[(name.hashCode() and 0x7fffffff) % PALETTE.size]
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, SIZE.toFloat(), c1.toInt(), c2.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), bg)

        val label = shortLabel(name)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        var size = SIZE * 0.42f
        paint.textSize = size
        while (paint.measureText(label) > SIZE * 0.86f && size > 20f) {
            size -= 4f
            paint.textSize = size
        }
        val bounds = Rect()
        paint.getTextBounds(label, 0, label.length, bounds)
        canvas.drawText(label, SIZE / 2f, SIZE / 2f + bounds.height() / 2f, paint)
        return out
    }

    fun shortLabel(name: String): String {
        val clean = name.trim()
        if (clean.length <= 7) return clean
        val words = clean.split(Regex("[\\s\\-–|/]+")).filter { it.isNotBlank() }
        val first = words.firstOrNull().orEmpty()
        if (first.length in 2..7 && first.any { it.isDigit() || it.isUpperCase() }) return first
        return words.take(3).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
            .ifBlank { clean.take(6) }
    }
}

/** Lets Android Auto and the notification load station pictures (content://<app>.art/<id>). */
class ArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val segments = uri.pathSegments
        if (segments.size == 2 && segments[0] == "cover") {
            val key = segments[1].filter { it.isLetterOrDigit() }
            val cover = CoverArt.file(ctx, key)
            if (!cover.exists()) throw FileNotFoundException(key)
            return ParcelFileDescriptor.open(cover, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val id = uri.lastPathSegment ?: throw FileNotFoundException()
        val station = findStation(ctx, id) ?: throw FileNotFoundException(id)
        val file = ArtStore.ensureFile(ctx, station)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = if (uri.pathSegments.firstOrNull() == "cover") "image/jpeg" else "image/png"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
