package com.example.screenlimit.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Конфигурация движка. warnUnitMs/limitMsOverride меняются только в debug-сборке (adb broadcast). */
data class EngineConfig(
    val warnUnitMs: Long = 60_000L,
    val limitMsOverride: Long? = null,
    val clockToleranceMs: Long = 120_000L,
    val maxTickGapMs: Long = 120_000L,
    val bootCorrectionWindowMs: Long = 10 * 60_000L,
) {
    companion object {
        val WARN_STEPS = listOf(30, 20, 10)
    }
}

/** Входные данные одного «тика» сервиса. */
data class TickInput(
    val elapsed: Long,          // SystemClock.elapsedRealtime()
    val wall: Long,             // System.currentTimeMillis()
    val zone: ZoneId,
    val autoTime: Boolean,      // Settings.Global.AUTO_TIME
    val counting: Boolean,      // экран включён, разблокирован, на переднем плане «считаемое» приложение
)

sealed class EngineEvent {
    data class Warning(val minutesLeft: Int, val reason: String) : EngineEvent()
    data class Locked(val reason: String) : EngineEvent()
    object Unlocked : EngineEvent()
    data class PeriodReset(val newStart: Long) : EngineEvent()
    data class ClockTamper(val offsetMs: Long) : EngineEvent()
}

data class StepResult(val state: GuardState, val events: List<EngineEvent>, val trustedNow: Long)

sealed class ExitChoice {
    object RestOfDay : ExitChoice()
    data class Extra(val ms: Long) : ExitChoice()
}

object Engine {
    const val REASON_LIMIT = "limit"
    const val REASON_SCHEDULE = "schedule"

    // ---------- Доверенное время ----------

    data class Trusted(val now: Long, val state: GuardState, val tamperOffset: Long?)

    /**
     * Время, устойчивое к ручной перестановке часов:
     * пока устройство не перезагружалось, «сейчас» = якорь + прошедшее elapsedRealtime (монотонно);
     * ручные прыжки wall-clock больше допуска игнорируются. После перезагрузки берём wall-clock,
     * но не раньше последнего доверенного момента. В первые 10 минут после загрузки при включённом
     * автоматическом времени разрешаем коррекцию вперёд (сеть досинхронизировала часы).
     */
    fun trusted(state: GuardState, input: TickInput, cfg: EngineConfig): Trusted {
        val rebooted = state.anchorElapsed == 0L || input.elapsed < state.anchorElapsed
        if (rebooted) {
            val now = maxOf(input.wall, state.lastTrusted)
            val offset = if (state.lastTrusted != 0L && input.wall < state.lastTrusted - cfg.clockToleranceMs) input.wall - state.lastTrusted else null
            return Trusted(now, state.copy(anchorWall = now, anchorElapsed = input.elapsed, lastTrusted = now), offset)
        }
        val expected = state.anchorWall + (input.elapsed - state.anchorElapsed)
        val diff = input.wall - expected
        return when {
            kotlin.math.abs(diff) <= cfg.clockToleranceMs -> {
                // Мелкий дрейф — подстраиваемся под системные часы, но без движения назад.
                val now = maxOf(input.wall, state.lastTrusted)
                Trusted(now, state.copy(anchorWall = now, anchorElapsed = input.elapsed, lastTrusted = now), null)
            }
            diff > 0 && input.autoTime && input.elapsed <= cfg.bootCorrectionWindowMs -> {
                Trusted(input.wall, state.copy(anchorWall = input.wall, anchorElapsed = input.elapsed, lastTrusted = input.wall), null)
            }
            else -> {
                val now = maxOf(expected, state.lastTrusted)
                Trusted(now, state.copy(lastTrusted = now), diff)
            }
        }
    }

    // ---------- Период (сутки) ----------

    fun periodFor(t: Long, zone: ZoneId, resetMinute: Int): Pair<Long, Long> {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone)
        val resetTime = LocalTime.of(resetMinute / 60, resetMinute % 60)
        var startDate = ldt.toLocalDate()
        if (ldt.toLocalTime() < resetTime) startDate = startDate.minusDays(1)
        val start = startDate.atTime(resetTime).atZone(zone).toInstant().toEpochMilli()
        val end = startDate.plusDays(1).atTime(resetTime).atZone(zone).toInstant().toEpochMilli()
        return start to end
    }

    fun limitMs(s: Settings, cfg: EngineConfig): Long = cfg.limitMsOverride ?: (s.limitMinutes * 60_000L)

    fun remainingMs(s: Settings, st: GuardState, cfg: EngineConfig): Long =
        limitMs(s, cfg) + st.bonusMs - st.usedMs

    /** Пороги, уже «пройденные» при данном остатке, — их не нужно звонить (напр. лимит < 30 мин). */
    fun premarkedWarnings(remaining: Long, cfg: EngineConfig): Set<Int> =
        EngineConfig.WARN_STEPS.filter { it * cfg.warnUnitMs >= remaining }.toSet()

    // ---------- Расписание ----------

    fun minuteOfDay(t: Long, zone: ZoneId): Int {
        val lt = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone).toLocalTime()
        return lt.hour * 60 + lt.minute
    }

    fun inWindow(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    /** Момент окончания текущего окна (если t внутри окна). */
    fun windowEnd(t: Long, zone: ZoneId, start: Int, end: Int): Long {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone)
        val endTime = LocalTime.of(end / 60, end % 60)
        var d = ldt.toLocalDate()
        if (ldt.toLocalTime() >= endTime) d = d.plusDays(1)
        return d.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
    }

    /** Ближайшее начало окна строго после t. */
    fun nextWindowStart(t: Long, zone: ZoneId, start: Int): Pair<Long, LocalDate> {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone)
        val st = LocalTime.of(start / 60, start % 60)
        var d = ldt.toLocalDate()
        if (ldt.toLocalTime() >= st) d = d.plusDays(1)
        return d.atTime(st).atZone(zone).toInstant().toEpochMilli() to d
    }

    // ---------- Основной шаг ----------

    fun step(s: Settings, st0: GuardState, input: TickInput, cfg: EngineConfig = EngineConfig()): StepResult {
        val events = ArrayList<EngineEvent>()
        val tr = trusted(st0, input, cfg)
        val now = tr.now
        var st = tr.state
        if (tr.tamperOffset != null) events += EngineEvent.ClockTamper(tr.tamperOffset)

        // Период
        if (st.periodEnd == 0L || now >= st.periodEnd || now < st.periodStart) {
            val (ps, pe) = periodFor(now, input.zone, s.resetMinuteOfDay)
            val hadPeriod = st.periodEnd != 0L
            st = st.copy(
                periodStart = ps, periodEnd = pe, usedMs = 0, bonusMs = 0,
                unlockedRestOfPeriod = false,
                firedWarnings = premarkedWarnings(limitMs(s, cfg), cfg),
            )
            if (hadPeriod) events += EngineEvent.PeriodReset(ps)
        }

        // Учёт использования — только по монотонным часам
        val delta = if (st.lastTickElapsed in 1..input.elapsed) (input.elapsed - st.lastTickElapsed).coerceAtMost(cfg.maxTickGapMs) else 0L
        if (input.counting && !st.locked) st = st.copy(usedMs = st.usedMs + delta)
        st = st.copy(lastTickElapsed = input.elapsed)

        // Лимит
        val remaining = remainingMs(s, st, cfg)
        val limitActive = s.limitEnabled && !st.unlockedRestOfPeriod
        val limitLock = limitActive && remaining <= 0

        if (limitActive && s.warningsEnabled) {
            val crossed = EngineConfig.WARN_STEPS.filter { it * cfg.warnUnitMs >= remaining && it !in st.firedWarnings }
            if (crossed.isNotEmpty()) {
                st = st.copy(firedWarnings = st.firedWarnings + crossed)
                if (!limitLock) events += EngineEvent.Warning(crossed.min(), REASON_LIMIT)
            }
        }

        // Расписание
        var scheduleLock = false
        if (s.scheduleEnabled) {
            val m = minuteOfDay(now, input.zone)
            val inside = inWindow(m, s.scheduleStartMin, s.scheduleEndMin)
            scheduleLock = inside && now >= st.scheduleOverrideUntil
            if (!inside && s.warningsEnabled) {
                val (startAt, day) = nextWindowStart(now, input.zone, s.scheduleStartMin)
                val until = startAt - now
                val crossed = EngineConfig.WARN_STEPS.filter { until <= it * cfg.warnUnitMs && "${day.toEpochDay()}-$it" !in st.scheduleFired }
                if (crossed.isNotEmpty()) {
                    val keys = crossed.map { "${day.toEpochDay()}-$it" }
                    val keep = st.scheduleFired.filter { k -> (k.substringBefore('-').toLongOrNull() ?: 0) >= day.toEpochDay() - 2 }
                    st = st.copy(scheduleFired = (keep + keys).toSet())
                    events += EngineEvent.Warning(crossed.min(), REASON_SCHEDULE)
                }
            }
        }

        val lockNow = limitLock || scheduleLock
        val reason = if (limitLock) REASON_LIMIT else if (scheduleLock) REASON_SCHEDULE else ""
        if (lockNow && !st.locked) {
            st = st.copy(locked = true, lockReason = reason, lockStartedAt = now)
            events += EngineEvent.Locked(reason)
            if (s.finalWarning && limitLock && s.warningsEnabled && 0 !in st.firedWarnings) {
                st = st.copy(firedWarnings = st.firedWarnings + 0)
                events += EngineEvent.Warning(0, REASON_LIMIT)
            }
        } else if (!lockNow && st.locked) {
            st = st.copy(locked = false, lockReason = "")
            events += EngineEvent.Unlocked
        } else if (lockNow) {
            st = st.copy(lockReason = reason)
        }
        return StepResult(st, events, now)
    }

    /** Применить выбор после 10 подтверждений. */
    fun grantExit(s: Settings, st: GuardState, choice: ExitChoice, now: Long, zone: ZoneId, cfg: EngineConfig = EngineConfig()): GuardState {
        val inWin = s.scheduleEnabled && inWindow(minuteOfDay(now, zone), s.scheduleStartMin, s.scheduleEndMin)
        return when (choice) {
            ExitChoice.RestOfDay -> st.copy(
                unlockedRestOfPeriod = true,
                scheduleOverrideUntil = if (inWin) windowEnd(now, zone, s.scheduleStartMin, s.scheduleEndMin) else st.scheduleOverrideUntil,
                locked = false, lockReason = "",
            )
            is ExitChoice.Extra -> {
                val limit = limitMs(s, cfg)
                val bonus = if (st.usedMs + choice.ms > limit + st.bonusMs) st.usedMs + choice.ms - limit else st.bonusMs
                st.copy(
                    bonusMs = bonus,
                    firedWarnings = premarkedWarnings(limit + bonus - st.usedMs, cfg),
                    scheduleOverrideUntil = if (inWin) now + choice.ms else st.scheduleOverrideUntil,
                    locked = false, lockReason = "",
                )
            }
        }
    }

    /** Через сколько мс нужно проснуться, чтобы точно поймать следующий порог/блокировку. */
    fun nextWakeDelay(s: Settings, st: GuardState, now: Long, zone: ZoneId, counting: Boolean, cfg: EngineConfig = EngineConfig()): Long {
        var best = Long.MAX_VALUE
        if (s.limitEnabled && !st.unlockedRestOfPeriod && !st.locked && counting) {
            val rem = remainingMs(s, st, cfg)
            best = minOf(best, rem.coerceAtLeast(0))
            if (s.warningsEnabled) EngineConfig.WARN_STEPS.filter { it !in st.firedWarnings }.forEach { w ->
                val d = rem - w * cfg.warnUnitMs
                if (d >= 0) best = minOf(best, d)
            }
        }
        if (st.periodEnd > now) best = minOf(best, st.periodEnd - now)
        if (s.scheduleEnabled) {
            val (startAt, _) = nextWindowStart(now, zone, s.scheduleStartMin)
            best = minOf(best, startAt - now)
            if (s.warningsEnabled) EngineConfig.WARN_STEPS.forEach { w ->
                val d = startAt - w * cfg.warnUnitMs - now
                if (d > 0) best = minOf(best, d)
            }
            if (st.scheduleOverrideUntil > now) best = minOf(best, st.scheduleOverrideUntil - now)
            if (inWindow(minuteOfDay(now, zone), s.scheduleStartMin, s.scheduleEndMin))
                best = minOf(best, windowEnd(now, zone, s.scheduleStartMin, s.scheduleEndMin) - now)
        }
        return best.coerceAtLeast(250)
    }
}

/** Политика изменения настроек: ослабление — только через 10 подтверждений, во время блокировки — никогда. */
object SettingsPolicy {
    private fun windowMinutes(start: Int, end: Int): Set<Int> =
        (0 until 24 * 60).filter { Engine.inWindow(it, start, end) }.toSet()

    fun loosenings(old: Settings, new: Settings): List<String> {
        val r = ArrayList<String>()
        if (old.limitEnabled && !new.limitEnabled) r += "выключение дневного лимита"
        if (new.limitEnabled && old.limitEnabled && new.limitMinutes > old.limitMinutes) r += "увеличение лимита"
        if (old.scheduleEnabled && !new.scheduleEnabled) r += "выключение расписания"
        if (old.scheduleEnabled && new.scheduleEnabled &&
            !windowMinutes(new.scheduleStartMin, new.scheduleEndMin).containsAll(windowMinutes(old.scheduleStartMin, old.scheduleEndMin))
        ) r += "сокращение окна блокировки"
        if (old.strictMode && !new.strictMode) r += "выключение строгого режима"
        if (!old.whitelist.containsAll(new.whitelist)) r += "расширение списка разрешённых приложений"
        if (old.resetMinuteOfDay != new.resetMinuteOfDay) r += "смена времени сброса"
        return r
    }

    fun isLoosening(old: Settings, new: Settings) = loosenings(old, new).isNotEmpty()
}

/** Время по приложениям из событий UsageStats (чистая функция — тестируется). */
object AppUsageCalc {
    const val RESUMED = 1
    const val PAUSED = 2
    const val SCREEN_OFF = 16

    data class Ev(val ts: Long, val pkg: String, val type: Int)

    fun compute(events: List<Ev>, begin: Long, end: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        var cur: String? = null
        var since = begin
        var sawAny = false
        fun add(p: String, ms: Long) { if (ms > 0) out[p] = (out[p] ?: 0) + ms }
        for (e in events.sortedBy { it.ts }) {
            if (e.ts < begin || e.ts > end) continue
            when (e.type) {
                RESUMED -> {
                    cur?.let { add(it, e.ts - since) }
                    cur = e.pkg; since = e.ts
                }
                PAUSED -> {
                    if (cur == e.pkg) { add(e.pkg, e.ts - since); cur = null }
                    else if (cur == null && !sawAny) add(e.pkg, e.ts - begin)
                }
                SCREEN_OFF -> { cur?.let { add(it, e.ts - since) }; cur = null }
            }
            sawAny = true
        }
        cur?.let { add(it, end - since) }
        return out
    }
}
