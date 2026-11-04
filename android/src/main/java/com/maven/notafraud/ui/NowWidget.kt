package com.maven.notafraud.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.maven.notafraud.Graph
import com.maven.notafraud.R
import com.maven.notafraud.core.guard.Ladder

/** Home-screen widget: ladder level, what to do now, the most pressing routine item. Updated with the notification. */
class NowWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        Graph.init(ctx)
        push(ctx, force = true)
    }

    companion object {
        private var last: String? = null

        fun push(ctx: Context, force: Boolean = false) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(ctx, NowWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val n = Graph.now()
            val st = Graph.nowState()
            val lad = Ladder.state(Graph.data, n)
            val level = "LEVEL ${lad.level} · ${Ladder.NAMES[lad.level].uppercase()}" + if (lad.bedtime) " · BEDTIME" else ""
            val detail = listOfNotNull(st.detail.ifBlank { null }, st.routine?.let { "Routine: $it" }, lad.reasons.firstOrNull()?.takeIf { lad.level > 0 })
                .joinToString("\n")
            val key = "$level|${st.headline}|$detail"
            if (!force && key == last) return
            last = key
            val open = PendingIntent.getActivity(
                ctx, 21, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(ctx.packageName, R.layout.widget_now).apply {
                setTextViewText(R.id.widget_level, level)
                setTextColor(R.id.widget_level, when (lad.level) { 0 -> 0xFF7CE38B.toInt(); 1 -> 0xFFFFC857.toInt(); 2 -> 0xFFFF9F43.toInt(); else -> 0xFFFF6B6B.toInt() })
                setTextViewText(R.id.widget_headline, st.headline)
                setTextViewText(R.id.widget_detail, detail)
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            runCatching { manager.updateAppWidget(ids, views) }
        }
    }
}
