package com.example.screenlimit

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Будильник-предупреждение: звук + вибрация, поверх экрана блокировки. */
class WarningActivity : AppCompatActivity() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        supportActionBar?.hide()
        render()
        start()
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); render(); stop(); start() }

    private fun render() {
        val min = intent.getIntExtra(EXTRA_MIN, 30)
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "limit"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(48))
            setBackgroundColor(ContextCompat.getColor(this@WarningActivity, R.color.warn_bg))
        }
        val fg = ContextCompat.getColor(this, R.color.block_fg)
        root.text("⏰", 72f, gravity = Gravity.CENTER, color = fg)
        root.text(if (min > 0) "Осталось $min мин" else "Время вышло", 34f, bold = true, gravity = Gravity.CENTER, color = fg, topDp = 12)
        root.text(message(min, reason), 18f, gravity = Gravity.CENTER, color = fg, topDp = 16)
        root.button("Понятно") { finish() }.apply { id = R.id.btnDismiss; layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(40) } }
        setContentView(root)
    }

    private fun start() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        runCatching {
            vibrator = getSystemService(Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        }
        h.postDelayed({ stop() }, AUTO_STOP_MS)
    }

    private fun stop() {
        h.removeCallbacksAndMessages(null)
        runCatching { ringtone?.stop() }; ringtone = null
        runCatching { vibrator?.cancel() }
    }

    override fun onDestroy() {
        stop()
        runCatching { getSystemService(NotificationManager::class.java).cancel(Guard.NOTIF_WARN) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MIN = "min"
        const val EXTRA_REASON = "reason"
        const val AUTO_STOP_MS = 60_000L

        fun message(min: Int, reason: String) = when {
            reason == "schedule" && min > 0 -> "Через $min мин начнётся режим отдыха по расписанию. Телефон будет заблокирован — останутся только звонки и SMS."
            min > 0 -> "Через $min мин экранного времени телефон заблокируется. Останутся только звонки и SMS."
            else -> "Дневной лимит исчерпан. Телефон заблокирован — доступны звонки и SMS."
        }
    }
}
