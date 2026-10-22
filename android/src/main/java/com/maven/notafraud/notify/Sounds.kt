package com.maven.notafraud.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * A short sound on the alarm stream. Alarms get through Do Not Disturb and Samsung's Bedtime mode
 * by default, so bedtime nudges are actually heard. Stops after a few seconds.
 */
object Sounds {
    fun bedtime(ctx: Context, seconds: Int = 4) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val r = RingtoneManager.getRingtone(ctx, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            r.isLooping = false
            r.play()
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { r.stop() } }, seconds * 1000L)
        }
        runCatching {
            ctx.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 600), -1))
        }
    }
}
