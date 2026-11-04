package com.maven.notafraud.notify

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.maven.notafraud.core.guard.Ladder
import com.maven.notafraud.core.plan.NowState

/**
 * Optional (Brick ladder → lock screen): the lock screen wallpaper shows the ladder level, what to do now,
 * and today's 3. Redrawn only when that text changes (a few times a day). The home screen wallpaper is untouched.
 */
object LockWallpaper {
    fun update(ctx: Context, st: NowState, lad: Ladder.State, picks: List<String>) {
        val prefs = ctx.getSharedPreferences("ladder", Context.MODE_PRIVATE)
        val key = listOf(lad.level, lad.bedtime, st.headline, picks.joinToString("|")).joinToString("#")
        if (prefs.getString("wallKey", null) == key) return
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(720)
        val h = dm.heightPixels.coerceAtLeast(1280)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(12, 14, 18))
        val accent = when (lad.level) { 0 -> Color.rgb(124, 227, 139); 1 -> Color.rgb(255, 200, 87); 2 -> Color.rgb(255, 159, 67); else -> Color.rgb(255, 107, 107) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = w * 0.04f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = w * 0.075f; typeface = Typeface.DEFAULT_BOLD }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(184, 192, 200); textSize = w * 0.045f }
        val x = w * 0.08f
        var y = h * 0.52f
        c.drawText("LEVEL ${lad.level} · ${Ladder.NAMES[lad.level].uppercase()}" + if (lad.bedtime) " · BEDTIME" else "", x, y, small)
        y += big.textSize * 1.4f
        for (line in wrap(st.headline, big, w - 2 * x).take(3)) { c.drawText(line, x, y, big); y += big.textSize * 1.2f }
        y += body.textSize
        if (picks.isNotEmpty()) {
            c.drawText("TODAY'S 3", x, y, small); y += body.textSize * 1.5f
            for (p in picks.take(3)) for (line in wrap("· $p", body, w - 2 * x).take(2)) { c.drawText(line, x, y, body); y += body.textSize * 1.35f }
        }
        val ok = runCatching { WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK) }.isSuccess
        if (ok) prefs.edit().putString("wallKey", key).apply()
        bmp.recycle()
    }

    private fun wrap(text: String, p: Paint, max: Float): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(next) > max && line.isNotEmpty()) { out += line; line = word } else line = next
        }
        if (line.isNotEmpty()) out += line
        return out
    }
}
