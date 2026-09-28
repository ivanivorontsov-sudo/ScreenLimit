package com.example.screenlimit

import com.example.screenlimit.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PolicyAndDataTest {
    @Test fun tighteningIsNotLoosening() {
        val a = Settings(limitMinutes = 120, whitelist = setOf("a", "b"))
        assertFalse(SettingsPolicy.isLoosening(a, a.copy(limitMinutes = 60)))
        assertFalse(SettingsPolicy.isLoosening(a, a.copy(whitelist = setOf("a"))))
        assertFalse(SettingsPolicy.isLoosening(a, a.copy(strictMode = true)))
        assertFalse(SettingsPolicy.isLoosening(a.copy(scheduleEnabled = false), a.copy(scheduleEnabled = true)))
    }

    @Test fun looseningDetected() {
        val a = Settings(limitMinutes = 120, scheduleEnabled = true, strictMode = true)
        assertTrue(SettingsPolicy.isLoosening(a, a.copy(limitMinutes = 121)))
        assertTrue(SettingsPolicy.isLoosening(a, a.copy(limitEnabled = false)))
        assertTrue(SettingsPolicy.isLoosening(a, a.copy(scheduleEnabled = false)))
        assertTrue(SettingsPolicy.isLoosening(a, a.copy(strictMode = false)))
        assertTrue(SettingsPolicy.isLoosening(a, a.copy(whitelist = setOf("x"))))
        assertTrue(SettingsPolicy.isLoosening(a, a.copy(scheduleStartMin = 23 * 60 + 30)))
        assertFalse(SettingsPolicy.isLoosening(a, a.copy(scheduleStartMin = 22 * 60)))
        assertTrue(SettingsPolicy.isLoosening(a, a.copy(resetMinuteOfDay = 60)))
    }

    @Test fun settingsJsonRoundTripAndForwardCompat() {
        val s = Settings(limitMinutes = 95, scheduleEnabled = true, whitelist = setOf("ru.yandex.taxi"))
        assertEquals(s, Settings.fromJson(JSONObject(s.toJson().toString())))
        // Старый/урезанный JSON с лишними полями — не падает, недостающее = дефолт
        val old = Settings.fromJson(JSONObject("""{"limitMinutes":30,"unknownFutureField":true}"""))
        assertEquals(30, old.limitMinutes); assertEquals(Settings().resetMinuteOfDay, old.resetMinuteOfDay)
        assertEquals(Settings.MAX_LIMIT, Settings.fromJson(JSONObject("""{"limitMinutes":99999}""")).limitMinutes)
    }

    @Test fun stateJsonRoundTrip() {
        val st = GuardState(anchorWall = 5, usedMs = 123, firedWarnings = setOf(30, 20), scheduleFired = setOf("1-30"), locked = true, lockReason = "limit")
        assertEquals(st, GuardState.fromJson(JSONObject(st.toJson().toString())))
    }

    @Test fun backupExportImportMergesHistoryWithoutLoss() {
        val h1 = listOf(HistoryEntry(3, "exit", "a"), HistoryEntry(1, "lock", "b"))
        val text = Backup.export(Settings(limitMinutes = 45), h1, 10)
        val p = Backup.parse(text, Settings())
        assertEquals(45, p.settings!!.limitMinutes)
        val local = listOf(HistoryEntry(2, "exit", "c"), HistoryEntry(3, "exit", "a"))
        val merged = HistoryEntry.merge(local, p.history)
        assertEquals(listOf(3L, 2L, 1L), merged.map { it.ts })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsForeignJson() { Backup.parse("""{"format":"other"}""", Settings()) }

    @Test fun appUsageBreakdown() {
        val R = AppUsageCalc.RESUMED; val P = AppUsageCalc.PAUSED
        val ev = listOf(
            AppUsageCalc.Ev(100, "a", R), AppUsageCalc.Ev(400, "a", P),
            AppUsageCalc.Ev(400, "b", R), AppUsageCalc.Ev(1000, "b", P),
            AppUsageCalc.Ev(1200, "a", R), AppUsageCalc.Ev(1300, "x", AppUsageCalc.SCREEN_OFF),
            AppUsageCalc.Ev(2000, "c", R),
        )
        val m = AppUsageCalc.compute(ev, 0, 2500)
        assertEquals(400L, m["a"]); assertEquals(600L, m["b"]); assertEquals(500L, m["c"])
    }

    @Test fun appUsageAppOpenBeforeWindow() {
        val m = AppUsageCalc.compute(listOf(AppUsageCalc.Ev(300, "a", AppUsageCalc.PAUSED)), 0, 1000)
        assertEquals(300L, m["a"])
    }
}
