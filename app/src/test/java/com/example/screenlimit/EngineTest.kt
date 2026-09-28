package com.example.screenlimit

import com.example.screenlimit.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class EngineTest {
    private val zone = ZoneId.of("Asia/Vladivostok")
    private fun wall(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    /** Симулятор: тикает каждые stepMs, двигая монотонные и настенные часы. */
    private class Sim(var s: Settings, startWall: Long, val zone: ZoneId, val cfg: EngineConfig = EngineConfig()) {
        var st = GuardState(); var elapsed = 1_000_000L; var wall = startWall
        val events = ArrayList<EngineEvent>()
        fun tick(counting: Boolean = true, autoTime: Boolean = true): StepResult {
            val r = Engine.step(s, st, TickInput(elapsed, wall, zone, autoTime, counting), cfg)
            st = r.state; events += r.events; return r
        }
        fun run(ms: Long, stepMs: Long = 5_000, counting: Boolean = true) {
            var left = ms
            while (left > 0) { val d = minOf(stepMs, left); elapsed += d; wall += d; left -= d; tick(counting) }
        }
        fun warnings() = events.filterIsInstance<EngineEvent.Warning>().map { it.minutesLeft }
    }

    @Test fun countsOnlyWhenCounting() {
        val sim = Sim(Settings(limitMinutes = 120), wall(2026, 9, 28, 10, 0), zone)
        sim.tick(); sim.run(10 * 60_000L); assertEquals(10 * 60_000L, sim.st.usedMs)
        sim.run(10 * 60_000L, counting = false); assertEquals(10 * 60_000L, sim.st.usedMs)
        sim.run(60_000L); assertEquals(11 * 60_000L, sim.st.usedMs)
    }

    @Test fun capsHugeGapsBetweenTicks() {
        val sim = Sim(Settings(), wall(2026, 9, 28, 10, 0), zone)
        sim.tick()
        sim.elapsed += 3_600_000L; sim.wall += 3_600_000L; sim.tick(true)
        assertEquals(EngineConfig().maxTickGapMs, sim.st.usedMs)
    }

    @Test fun warningsAt30_20_10_thenLockWithFinal() {
        val sim = Sim(Settings(limitMinutes = 60), wall(2026, 9, 28, 10, 0), zone)
        sim.tick()
        sim.run(29 * 60_000L); assertTrue(sim.warnings().isEmpty())
        sim.run(61_000L); assertEquals(listOf(30), sim.warnings())
        sim.run(10 * 60_000L); assertEquals(listOf(30, 20), sim.warnings())
        sim.run(10 * 60_000L); assertEquals(listOf(30, 20, 10), sim.warnings())
        assertFalse(sim.st.locked)
        sim.run(10 * 60_000L)
        assertTrue(sim.st.locked)
        assertEquals(listOf(30, 20, 10, 0), sim.warnings())
        assertEquals(1, sim.events.count { it is EngineEvent.Locked })
    }

    @Test fun lockedStateDoesNotAccumulate() {
        val sim = Sim(Settings(limitMinutes = 1, warningsEnabled = false), wall(2026, 9, 28, 10, 0), zone)
        sim.tick(); sim.run(70_000L)
        assertTrue(sim.st.locked)
        val used = sim.st.usedMs
        sim.run(10 * 60_000L); assertEquals(used, sim.st.usedMs)
    }

    @Test fun shortLimitSkipsAlreadyPassedWarnings() {
        val sim = Sim(Settings(limitMinutes = 15), wall(2026, 9, 28, 10, 0), zone)
        sim.tick()
        sim.run(5 * 60_000L + 1_000); assertEquals(listOf(10), sim.warnings())
    }

    @Test fun testMultiplierShrinksWarnings() {
        val cfg = EngineConfig(warnUnitMs = 1_000L, limitMsOverride = 120_000L)
        val sim = Sim(Settings(), wall(2026, 9, 28, 10, 0), zone, cfg)
        sim.tick(); sim.run(89_000L, 1_000); assertTrue(sim.warnings().isEmpty())
        sim.run(2_000L, 1_000); assertEquals(listOf(30), sim.warnings())
        sim.run(30_000L, 1_000); assertTrue(sim.st.locked)
        assertEquals(listOf(30, 20, 10, 0), sim.warnings())
    }

    @Test fun midnightResetUnlocksAndClearsUsage() {
        val sim = Sim(Settings(limitMinutes = 1, warningsEnabled = false), wall(2026, 9, 28, 23, 58), zone)
        sim.tick(); sim.run(70_000L); assertTrue(sim.st.locked)
        sim.run(2 * 60_000L, counting = false)
        assertFalse(sim.st.locked); assertEquals(0L, sim.st.usedMs)
        assertTrue(sim.events.any { it is EngineEvent.PeriodReset }); assertTrue(sim.events.any { it is EngineEvent.Unlocked })
    }

    @Test fun customResetTime() {
        val (s, e) = Engine.periodFor(wall(2026, 9, 28, 3, 0), zone, 4 * 60)
        assertEquals(wall(2026, 9, 27, 4, 0), s); assertEquals(wall(2026, 9, 28, 4, 0), e)
        val (s2, _) = Engine.periodFor(wall(2026, 9, 28, 5, 0), zone, 4 * 60)
        assertEquals(wall(2026, 9, 28, 4, 0), s2)
    }

    @Test fun clockForwardJumpDoesNotResetDay() {
        val sim = Sim(Settings(limitMinutes = 1, warningsEnabled = false), wall(2026, 9, 28, 20, 0), zone)
        sim.tick(); sim.run(70_000L); assertTrue(sim.st.locked)
        sim.elapsed = 2 * 3_600_000L    // устройство давно загружено (вне окна коррекции)
        sim.st = sim.st.copy(anchorElapsed = sim.elapsed - 1000, lastTickElapsed = sim.elapsed - 1000)
        sim.wall += 1000 + 6 * 3_600_000L     // ручной перевод на 6 часов вперёд (за полночь)
        sim.tick(counting = true, autoTime = false)
        assertTrue("должен оставаться заблокированным", sim.st.locked)
        assertTrue(sim.events.any { it is EngineEvent.ClockTamper })
        assertFalse(sim.events.any { it is EngineEvent.PeriodReset })
    }

    @Test fun clockBackwardJumpDoesNotGiveTimeBack() {
        val sim = Sim(Settings(limitMinutes = 60), wall(2026, 9, 28, 10, 0), zone)
        sim.tick(); sim.run(30 * 60_000L)
        val used = sim.st.usedMs
        sim.wall -= 5 * 3_600_000L; sim.elapsed += 5_000
        sim.tick()
        assertEquals(used + 5_000, sim.st.usedMs)
        assertTrue(sim.events.any { it is EngineEvent.ClockTamper })
    }

    @Test fun rebootWithClockBackStaysInSameDay() {
        val sim = Sim(Settings(limitMinutes = 1, warningsEnabled = false), wall(2026, 9, 28, 20, 0), zone)
        sim.tick(); sim.run(70_000L); assertTrue(sim.st.locked)
        val trusted = sim.st.lastTrusted
        sim.elapsed = 30_000L; sim.wall = trusted - 3 * 24 * 3_600_000L   // «перезагрузка» + часы назад на 3 дня
        sim.tick()
        assertTrue(sim.st.locked); assertTrue(sim.st.lastTrusted >= trusted)
    }

    @Test fun rebootNormalNextDayResets() {
        val sim = Sim(Settings(limitMinutes = 1, warningsEnabled = false), wall(2026, 9, 28, 20, 0), zone)
        sim.tick(); sim.run(70_000L); assertTrue(sim.st.locked)
        sim.elapsed = 30_000L; sim.wall = wall(2026, 9, 29, 8, 0)
        sim.tick(counting = false)
        assertFalse(sim.st.locked); assertEquals(0L, sim.st.usedMs)
    }

    @Test fun smallDriftAccepted() {
        val sim = Sim(Settings(), wall(2026, 9, 28, 10, 0), zone)
        sim.tick(); sim.wall += 30_000; sim.elapsed += 5_000; sim.tick()
        assertFalse(sim.events.any { it is EngineEvent.ClockTamper })
    }

    @Test fun timezoneChangeHandled() {
        val s = Settings(limitMinutes = 60)
        var st = GuardState()
        val w = wall(2026, 9, 28, 12, 0)
        st = Engine.step(s, st, TickInput(1000, w, zone, true, true)).state
        val ps = st.periodStart
        // Смена пояса не меняет момент времени — не должно быть ни сброса, ни «подарка» времени.
        st = Engine.step(s, st, TickInput(6000, w + 5000, ZoneId.of("Europe/Moscow"), true, true)).state
        assertEquals(ps, st.periodStart); assertEquals(5000L, st.usedMs)
    }

    @Test fun grantExtraTimeAndRestOfDay() {
        val cfg = EngineConfig()
        val sim = Sim(Settings(limitMinutes = 1, warningsEnabled = true), wall(2026, 9, 28, 10, 0), zone, cfg)
        sim.tick(); sim.run(70_000L); assertTrue(sim.st.locked)
        sim.st = Engine.grantExit(sim.s, sim.st, ExitChoice.Extra(15 * 60_000L), sim.wall, zone, cfg)
        assertFalse(sim.st.locked)
        sim.events.clear()
        sim.run(5 * 60_000L + 5_000); assertFalse(sim.st.locked); assertEquals(listOf(10), sim.warnings())
        sim.run(10 * 60_000L); assertTrue(sim.st.locked)
        sim.st = Engine.grantExit(sim.s, sim.st, ExitChoice.RestOfDay, sim.wall, zone, cfg)
        sim.run(3 * 3_600_000L); assertFalse(sim.st.locked)
    }

    @Test fun scheduleWindowLocksAndWarnsWallClock() {
        val s = Settings(limitEnabled = false, scheduleEnabled = true, scheduleStartMin = 23 * 60, scheduleEndMin = 7 * 60)
        val sim = Sim(s, wall(2026, 9, 28, 22, 20), zone)
        sim.tick(counting = false)
        sim.run(10 * 60_000L + 1000, counting = false); assertEquals(listOf(30), sim.warnings())
        sim.run(10 * 60_000L, counting = false); assertEquals(listOf(30, 20), sim.warnings())
        sim.run(10 * 60_000L, counting = false); assertEquals(listOf(30, 20, 10), sim.warnings())
        sim.run(10 * 60_000L, counting = false); assertTrue(sim.st.locked); assertEquals(Engine.REASON_SCHEDULE, sim.st.lockReason)
        sim.wall = wall(2026, 9, 29, 7, 0, 5); sim.elapsed += 8 * 3_600_000L; sim.tick(false)
        assertFalse(sim.st.locked)
    }

    @Test fun scheduleExitOverridesUntilWindowEnd() {
        val s = Settings(limitEnabled = false, scheduleEnabled = true, scheduleStartMin = 23 * 60, scheduleEndMin = 7 * 60)
        val sim = Sim(s, wall(2026, 9, 28, 23, 30), zone)
        sim.tick(false); assertTrue(sim.st.locked)
        sim.st = Engine.grantExit(s, sim.st, ExitChoice.Extra(30 * 60_000L), sim.wall, zone)
        sim.run(20 * 60_000L, counting = false); assertFalse(sim.st.locked)
        sim.run(11 * 60_000L, counting = false); assertTrue(sim.st.locked)
    }

    @Test fun inWindowWrapAround() {
        assertTrue(Engine.inWindow(23 * 60 + 30, 23 * 60, 7 * 60)); assertTrue(Engine.inWindow(60, 23 * 60, 7 * 60))
        assertFalse(Engine.inWindow(12 * 60, 23 * 60, 7 * 60)); assertTrue(Engine.inWindow(10 * 60, 9 * 60, 17 * 60))
        assertFalse(Engine.inWindow(5, 5, 5))
    }

    @Test fun nextWakeCatchesThreshold() {
        val s = Settings(limitMinutes = 60)
        val st = GuardState(usedMs = 25 * 60_000L, periodEnd = Long.MAX_VALUE / 2)
        val d = Engine.nextWakeDelay(s, st, wall(2026, 9, 28, 10, 0), zone, true)
        assertEquals(5 * 60_000L, d)
    }
}
