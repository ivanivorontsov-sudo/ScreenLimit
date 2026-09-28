package com.example.screenlimit

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Постоянный foreground-сервис: тикает учёт каждые ~5 с, пока экран включён. */
class GuardService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var lastText = ""
    private val loop = object : Runnable {
        override fun run() {
            runCatching { Guard.tick(this@GuardService) }.onFailure { Log.w("ScreenLimit", "tick", it) }
            updateNotification()
            h.removeCallbacks(this)
            if (getSystemService(PowerManager::class.java).isInteractive) h.postDelayed(this, TICK_MS)
        }
    }
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) { runCatching { Guard.tick(c, "screen_off") }; h.removeCallbacks(loop); updateNotification() }
            else { h.removeCallbacks(loop); h.post(loop) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        startFg()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, rx, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun startFg() {
        val n = Guard.statusNotification(this)
        runCatching {
            ServiceCompat.startForeground(this, Guard.NOTIF_STATUS, n,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        }.onFailure { Log.w("ScreenLimit", "startForeground", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        h.removeCallbacks(loop); h.post(loop)
        return START_STICKY
    }

    private fun updateNotification() {
        val n = Guard.statusNotification(this)
        val key = n.extras.getCharSequence("android.title").toString() + n.extras.getCharSequence("android.text")
        if (key != lastText) {
            lastText = key
            runCatching { getSystemService(NotificationManager::class.java).notify(Guard.NOTIF_STATUS, n) }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        start(this)
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(rx) }
        super.onDestroy()
        // Если нас остановили не через систему (напр. OEM-киллер) — будильник-«пульс» поднимет снова.
        Guard.scheduleWake(this, 5_000)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TICK_MS = 5_000L
        @Volatile var running = false
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java)) }
                .onFailure { Log.w("ScreenLimit", "start service", it); runCatching { Guard.tick(ctx, "fallback") } }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Перезагрузка / обновление: восстановить сервис и блокировку.
        GuardService.start(ctx)
        val st = runCatching { Guard.tick(ctx, "boot:${intent.action}") }.getOrNull()
        if (st?.locked == true) { Owner.ensureLockPolicies(ctx, true); Guard.showBlock(ctx) }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        runCatching { Guard.tick(ctx, "alarm:${intent.action}") }
        if (!GuardService.running) GuardService.start(ctx)
    }

    companion object {
        const val ACTION_WAKE = "com.example.screenlimit.WAKE"
        const val ACTION_HEARTBEAT = "com.example.screenlimit.HEARTBEAT"
    }
}
