package com.example.screenlimit

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.screenlimit.core.Engine
import com.example.screenlimit.core.EngineEvent
import com.example.screenlimit.core.ExitChoice
import com.example.screenlimit.core.GuardState
import com.example.screenlimit.core.HistoryEntry
import com.example.screenlimit.core.TickInput
import java.time.ZoneId

/** Центральный контроллер: учёт времени, предупреждения, блокировка. */
object Guard {
    private const val TAG = "ScreenLimit"
    const val CH_STATUS = "sl_status"
    const val CH_WARN = "sl_warn_alarm"
    const val NOTIF_STATUS = 1
    const val NOTIF_WARN = 2

    /** Только debug: ввод кириллицы в поле фразы из adb-теста (adb input text не умеет кириллицу). */
    @Volatile var debugTypeSink: ((String) -> Unit)? = null
    /** Только debug: состояние экрана подтверждений для тестов (без uiautomator, который отключает AccessibilityService). */
    @Volatile var debugStateSource: (() -> String)? = null
    @Volatile var lastForeground: String? = null      // от AccessibilityService (без клавиатур и systemui)
    @Volatile var lastForegroundAt: Long = 0

    // ---------- Разрешённые приложения и «окно помощников» ----------
    /** Сколько после разрешённого приложения допускаются его «помощники» (браузер/Custom Tabs, GMS, выбор приложения, камера…). */
    const val HELPER_WINDOW_MS = 2 * 60_000L
    /** Последнее «основное» (не клавиатура/systemui и не помощник) приложение на переднем плане. */
    @Volatile var lastPrimary: String? = null
    @Volatile private var lastPrimaryIsAllowedApp = false
    /** Когда (elapsedRealtime) разрешённое приложение последний раз было на переднем плане / запущено с экрана блокировки. */
    @Volatile var lastAllowedAppAt = 0L

    /**
     * Учёт переднего плана для окна помощников. Помощник допускается, только если перед ним было
     * разрешённое приложение (а не экран блокировки, лаунчер и т.п.) и прошло не больше [HELPER_WINDOW_MS].
     */
    fun noteForeground(ctx: Context, pkg: String, whitelist: Set<String>) {
        if (Allowed.isNeutral(ctx, pkg)) return
        val inList = pkg in whitelist
        if (!inList && Allowed.isHelper(ctx, pkg)) return            // помощник не меняет «основное» приложение
        val allowedApp = inList || (pkg != ctx.packageName && pkg != "android" && Allowed.isBaseAllowed(ctx, pkg))
        lastPrimary = pkg; lastPrimaryIsAllowedApp = allowedApp
        if (allowedApp) lastAllowedAppAt = SystemClock.elapsedRealtime()
    }

    /** Запуск разрешённого приложения с экрана блокировки. */
    fun noteAllowedLaunch(pkg: String) {
        lastPrimary = pkg; lastPrimaryIsAllowedApp = true; lastAllowedAppAt = SystemClock.elapsedRealtime()
    }

    fun helperWindowOpen(): Boolean =
        lastPrimaryIsAllowedApp && lastAllowedAppAt > 0 && SystemClock.elapsedRealtime() - lastAllowedAppAt < HELPER_WINDOW_MS

    // ---------- Экран подтверждений ----------
    /** Живой экран подтверждений (создан и не уничтожен) — в любом состоянии: resumed, paused (клавиатура, диалог), stopped. */
    @Volatile var exitFlowRef: java.lang.ref.WeakReference<android.app.Activity>? = null
    fun exitFlowAlive(): Boolean = exitFlowRef?.get()?.let { !it.isFinishing && !it.isDestroyed } == true
    private var lastTamperLog = 0L
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun notifyListeners() = synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }

    fun isLocked(ctx: Context) = Store.get(ctx).state.locked

    // ---------- Тик ----------

    @Synchronized
    fun tick(ctx: Context, why: String = ""): GuardState {
        val store = Store.get(ctx)
        val s = store.settings
        val cfg = store.engineConfig()
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val interactive = pm.isInteractive
        val unlocked = !km.isKeyguardLocked
        val fg = if (interactive && unlocked) foregroundPackage(ctx) else null
        val counting = interactive && unlocked && !Allowed.isExcludedFromCounting(ctx, fg)
        val autoTime = runCatching { SysSettings.Global.getInt(ctx.contentResolver, SysSettings.Global.AUTO_TIME) == 1 }.getOrDefault(true)
        val input = TickInput(SystemClock.elapsedRealtime(), System.currentTimeMillis(), ZoneId.systemDefault(), autoTime, counting)
        val prev = store.state
        val r = Engine.step(s, prev, input, cfg)
        store.state = r.state

        for (e in r.events) when (e) {
            is EngineEvent.Warning -> onWarning(ctx, e.minutesLeft, e.reason)
            is EngineEvent.Locked -> {
                store.log(HistoryEntry.LOCK, if (e.reason == Engine.REASON_SCHEDULE) "Блокировка по расписанию" else "Лимит исчерпан (${fmtDur(r.state.usedMs)})")
                onLockEngaged(ctx)
            }
            is EngineEvent.Unlocked -> onLockReleased(ctx)
            is EngineEvent.PeriodReset -> store.log(HistoryEntry.RESET, "Новые сутки — счётчик обнулён")
            is EngineEvent.ClockTamper -> if (r.trustedNow - lastTamperLog > 10 * 60_000L) {
                lastTamperLog = r.trustedNow
                store.log(HistoryEntry.TAMPER, "Системные часы сдвинуты на ${e.offsetMs / 60_000} мин — игнорируется")
            }
        }

        if (r.state.locked) {
            // Страховка без Accessibility: если открыто запрещённое приложение — вернуть экран блокировки.
            if (fg != null && !Allowed.isAllowedDuringLock(ctx, fg, s.whitelist) && !BlockerService.running) showBlock(ctx)
            // Помощник (браузер, GMS, камера…) после окончания окна — вернуть экран блокировки.
            else if (fg != null && Allowed.isHelper(ctx, fg) && !Allowed.isAllowedDuringLock(ctx, fg, s.whitelist)) showBlock(ctx)
            if (r.events.none { it is EngineEvent.Locked }) Owner.ensureLockPolicies(ctx, true)
        }
        scheduleWake(ctx, Engine.nextWakeDelay(s, r.state, r.trustedNow, input.zone, counting, cfg))
        if (why.isNotEmpty() && BuildConfig.DEBUG) Log.d(TAG, "tick($why) used=${r.state.usedMs} locked=${r.state.locked} fg=$fg counting=$counting ev=${r.events}")
        notifyListeners()
        return r.state
    }

    fun foregroundPackage(ctx: Context): String? {
        val a = lastForeground
        if (a != null && BlockerService.running) return a
        return runCatching {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val end = System.currentTimeMillis()
            val ev = usm.queryEvents(end - 60 * 60_000L, end)
            val e = UsageEvents.Event()
            var last: String? = null
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
            }
            last
        }.getOrNull() ?: a
    }

    // ---------- Блокировка ----------

    private fun onLockEngaged(ctx: Context) {
        Owner.ensureLockPolicies(ctx, true)
        showBlock(ctx)
    }

    private fun onLockReleased(ctx: Context) {
        Owner.ensureLockPolicies(ctx, false)
        val store = Store.get(ctx)
        if (store.exitProgress?.purpose == ExitActivity.P_LOCK) store.exitProgress = null
        val am = ctx.getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            ctx.startActivity(Intent(ctx, BlockActivity::class.java).setAction(BlockActivity.ACTION_RELEASE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    @Volatile private var lastShowBlock = 0L

    fun showBlock(ctx: Context) {
        // Идёт выход из блокировки: не уничтожать экран подтверждений (BlockActivity — singleTask и закрыл бы его),
        // а вернуть его самого на передний план — на том же шаге.
        val ex = exitFlowRef?.get()
        if (ex != null && !ex.isFinishing && !ex.isDestroyed) {
            if (ExitActivity.resumed) return
            if (BuildConfig.DEBUG) Log.d(TAG, "showBlock -> bring exit flow to front")
            runCatching {
                ctx.startActivity(Intent(ctx, ExitActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }.onFailure { Log.w(TAG, "bring exit flow", it) }
            return
        }
        // 1.1.0: one launch per gesture. Several sources (accessibility event of the launcher, its recents window,
        // the tick) used to fire within the same second and stacked two pause-screen launches.
        val now = SystemClock.uptimeMillis()
        if (BlockerService.pauseResumed || now - lastShowBlock < 1_000) return
        lastShowBlock = now
        if (BuildConfig.DEBUG) Log.d(TAG, "showBlock from " + Throwable().stackTrace.drop(1).take(2).joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName })
        runCatching {
            ctx.startActivity(Intent(ctx, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { Log.w(TAG, "showBlock", it) }
    }

    /** Выход после 10 подтверждений. */
    fun grantExit(ctx: Context, choice: ExitChoice) {
        synchronized(this) {
            val store = Store.get(ctx)
            val st = store.state
            val now = maxOf(System.currentTimeMillis(), st.lastTrusted)
            store.state = Engine.grantExit(store.settings, st, choice, now, ZoneId.systemDefault(), store.engineConfig())
            val d = when (choice) { ExitChoice.RestOfDay -> "до конца суток"; is ExitChoice.Extra -> "+${choice.ms / 60_000} мин" }
            store.log(HistoryEntry.EXIT, "Выход из блокировки после 10 подтверждений: $d")
            Owner.ensureLockPolicies(ctx, false)
        }
        tick(ctx, "exit")
    }

    // ---------- Предупреждения ----------

    fun onWarning(ctx: Context, minutes: Int, reason: String) {
        ensureChannels(ctx)
        val i = Intent(ctx, WarningActivity::class.java)
            .putExtra(WarningActivity.EXTRA_MIN, minutes).putExtra(WarningActivity.EXTRA_REASON, reason)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 100 + minutes, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = WarningActivity.message(minutes, reason)
        val n = NotificationCompat.Builder(ctx, CH_WARN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Стоп-экран")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_WARN, n) }
        // При включённом экране FSI показывается лишь как heads-up — открываем будильник напрямую
        // (разрешено: у приложения есть «поверх других окон» / привязанный AccessibilityService).
        runCatching { ctx.startActivity(i) }
    }

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_STATUS) == null)
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Статус лимита", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        if (nm.getNotificationChannel(CH_WARN) == null)
            nm.createNotificationChannel(NotificationChannel(CH_WARN, "Предупреждения (будильник)", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            })
    }

    // ---------- Будильники ----------

    fun scheduleWake(ctx: Context, delayMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_WAKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val d = delayMs.coerceIn(1_000L, 6 * 60 * 60_000L)
        val at = SystemClock.elapsedRealtime() + d
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
        // Страховочный «пульс» раз в 15 минут — перезапускает сервис, если его убила оболочка OEM.
        val hb = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_HEARTBEAT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 15 * 60_000L, 15 * 60_000L, hb) }
    }

    // ---------- Уведомление сервиса ----------

    fun statusNotification(ctx: Context): android.app.Notification {
        ensureChannels(ctx)
        val store = Store.get(ctx)
        val st = store.state; val s = store.settings
        val rem = Engine.remainingMs(s, st, store.engineConfig()).coerceAtLeast(0)
        val title = when {
            st.locked -> "Телефон заблокирован"
            !s.limitEnabled -> "Лимит выключен"
            st.unlockedRestOfPeriod -> "Лимит снят до конца суток"
            else -> "Осталось ${fmtDur(rem)}"
        }
        val text = "Сегодня: ${fmtDur(st.usedMs)}" + if (s.limitEnabled) " из ${fmtDur(Engine.limitMs(s, store.engineConfig()) + st.bonusMs)}" else ""
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun fmtDur(ms: Long): String {
        val totalMin = (ms / 60_000).coerceAtLeast(0)
        val h = totalMin / 60; val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "$h ч $m мин"
            h > 0 -> "$h ч"
            ms in 1 until 60_000 -> "${ms / 1000} с"
            else -> "$m мин"
        }
    }
}
