package com.maven.notafraud.guard

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.maven.notafraud.Graph
import com.maven.notafraud.core.guard.Credits
import com.maven.notafraud.sense.AppLabels

/**
 * Quick Settings tile: "Quick Snap". Opens Snapchat free for a few minutes (to film and send
 * something right now), a limited number of times a day. Past the window, the block screen is back.
 */
class QuickOpenTile : TileService() {

    private val target get() = Graph.data.rules.profiles.entries.firstOrNull { it.value.quickOpen }?.key ?: SNAPCHAT

    override fun onStartListening() {
        super.onStartListening()
        Graph.init(this)
        render()
    }

    private fun left(): Int {
        val pkg = target
        val p = Graph.data.rules.profile(pkg)
        return (p.quickOpenPerDay - Credits.quickOpensToday(Graph.data.ledger, pkg, Graph.startOfToday())).coerceAtLeast(0)
    }

    private fun render() {
        val t = qsTile ?: return
        val pkg = target
        val p = Graph.data.rules.profile(pkg)
        val n = left()
        t.label = "Quick ${AppLabels.label(this, pkg).take(10)}"
        t.subtitle = if (!p.quickOpen) "Off" else if (n > 0) "$n left · ${p.quickOpenMin} min" else "None left today"
        t.state = if (p.quickOpen && n > 0) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        Graph.init(this)
        val pkg = target
        val now = Graph.now()
        var ok = false
        Graph.update { d ->
            Credits.quickOpen(d.ledger, d.rules.profile(pkg), pkg, AppLabels.label(this, pkg), now, Graph.startOfToday())
                ?.let { ok = true; d.copy(ledger = it) } ?: d
        }
        render()
        if (!ok) return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        val pi = PendingIntent.getActivity(this, 41, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startActivityAndCollapse(pi)
    }

    companion object {
        const val SNAPCHAT = "com.snapchat.android"
    }
}
