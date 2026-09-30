package com.example.screenlimit

import com.example.screenlimit.core.ExitFlow
import org.junit.Assert.*
import org.junit.Test

class ExitFlowTest {
    private fun pass(f: ExitFlow, t0: Long): Long {
        var t = t0
        while (f.phase == ExitFlow.Phase.IN_PROGRESS) {
            f.onShown(t); t += 4_000
            val r = f.confirm(t, f.step.phrase)
            if (r is ExitFlow.Result.Completed) break
            assertEquals(ExitFlow.Result.Advanced, r)
        }
        return t
    }

    @Test fun exactlyTenConfirmations() {
        val f = ExitFlow(42)
        var t = 0L; var n = 0
        while (true) {
            f.onShown(t); t += 4_000
            val r = f.confirm(t, f.step.phrase); n++
            if (r == ExitFlow.Result.Completed) break
            assertEquals(ExitFlow.Result.Advanced, r)
        }
        assertEquals(10, n); assertEquals(ExitFlow.Phase.COMPLETED, f.phase)
    }

    @Test fun rejectsBeforeDelay() {
        val f = ExitFlow(1); f.onShown(1000)
        assertTrue(f.confirm(1000 + 3_999) is ExitFlow.Result.Rejected)
        assertEquals(0, f.current)
        assertFalse(f.isActive(4_999)); assertTrue(f.isActive(5_000))
    }

    @Test fun rejectsIfNotShown() {
        val f = ExitFlow(1)
        assertTrue(f.confirm(1_000_000) is ExitFlow.Result.Rejected)
    }

    @Test fun burstClicksCannotSkipSteps() {
        val f = ExitFlow(7); f.onShown(0)
        assertEquals(ExitFlow.Result.Advanced, f.confirm(4_000, f.steps[0].phrase))
        // Сразу следующий клик (без показа/ожидания) — отклонён
        repeat(20) { assertTrue(f.confirm(4_001L + it, f.step.phrase) is ExitFlow.Result.Rejected) }
        assertEquals(1, f.current)
    }

    @Test fun typedPhrasesRequired() {
        val f = ExitFlow(3)
        val typed = f.steps.filter { it.phrase != null }.map { it.index }
        assertEquals(ExitFlow.TYPED_STEPS, typed.toSet())
        var t = 0L
        while (f.current < 2) { f.onShown(t); t += 4000; f.confirm(t, null) }
        f.onShown(t); t += 4000
        assertTrue(f.confirm(t, "не то") is ExitFlow.Result.Rejected)
        assertTrue(f.confirm(t, null) is ExitFlow.Result.Rejected)
        assertEquals(ExitFlow.Result.Advanced, f.confirm(t, "  " + f.step.phrase!!.uppercase().replace('е', 'ё') + " "))
    }

    @Test fun cancelAtAnyStepEndsFlow() {
        for (k in 0 until 10) {
            val f = ExitFlow(k.toLong()); var t = 0L
            repeat(k) { f.onShown(t); t += 4000; f.confirm(t, f.step.phrase) }
            assertEquals(ExitFlow.Result.Cancelled, f.cancel())
            f.onShown(t); assertTrue(f.confirm(t + 5000, f.step.phrase) is ExitFlow.Result.Rejected)
            assertEquals(ExitFlow.Phase.CANCELLED, f.phase)
        }
    }

    @Test fun positionsChangeBetweenSteps() {
        val f = ExitFlow(99)
        f.steps.zipWithNext().forEach { (a, b) -> assertNotEquals(a.position, b.position) }
        assertTrue(f.steps.map { it.question }.toSet().size >= 8)
    }

    @Test fun stepTimeoutCancels() {
        val f = ExitFlow(5); f.onShown(0)
        assertEquals(ExitFlow.Result.Cancelled, f.confirm(ExitFlow.STEP_TIMEOUT_MS + 1))
    }

    @Test fun fullFlowTakesAtLeast40Seconds() {
        val f = ExitFlow(11)
        assertTrue(pass(f, 0) >= 40_000)
    }

    @Test fun restoreKeepsSameStepsAndStillRequiresDelayAndPhrase() {
        val a = ExitFlow(777); var t = 0L
        repeat(3) { a.onShown(t); t += 4_000; assertEquals(ExitFlow.Result.Advanced, a.confirm(t, a.step.phrase)) }
        val b = ExitFlow(777); b.restore(a.current)
        assertEquals(3, b.current); assertEquals(a.step, b.step)
        b.onShown(t)
        assertTrue(b.confirm(t + 100, b.step.phrase) is ExitFlow.Result.Rejected)          // задержка снова
        if (b.step.phrase != null) assertTrue(b.confirm(t + 4_000, "не то") is ExitFlow.Result.Rejected)
        assertEquals(ExitFlow.Result.Advanced, b.confirm(t + 4_000, b.step.phrase))
        val c = ExitFlow(1); c.cancel(); c.restore(5); assertEquals(0, c.current)            // отменённый не восстанавливается
    }

    @Test fun exitProgressValidity() {
        val p = ExitProgress("lock", 5, 4, savedElapsed = 1_000_000, savedWall = 0)
        assertTrue(p.validFor("lock", 1_000_000 + 60_000))
        assertFalse(p.validFor("settings", 1_000_000 + 60_000))
        assertFalse(p.validFor("lock", 1_000_000 + ExitProgress.MAX_AGE_MS + 1))
        assertFalse(p.validFor("lock", 500))                                              // перезагрузка
        assertEquals(p, ExitProgress.fromJson(p.toJson()))
    }
}
