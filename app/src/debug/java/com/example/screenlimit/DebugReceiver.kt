package com.example.screenlimit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.screenlimit.core.GuardState

/**
 * ТОЛЬКО debug-сборка (src/debug) — в release-APK этого класса нет.
 *   adb shell am broadcast -n com.example.screenlimit/.DebugReceiver --el warn_unit_ms 10000 --el limit_ms 120000
 *   adb shell am broadcast -n com.example.screenlimit/.DebugReceiver --ez reset_usage true
 *   adb shell am broadcast -n com.example.screenlimit/.DebugReceiver --ez clear_debug true
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val store = Store.get(ctx)
        i.getStringExtra("type")?.let { Guard.debugTypeSink?.invoke(it); resultData = "typed"; return }
        if (i.getBooleanExtra("clear_debug", false)) store.setDebug(0, 0)
        if (i.hasExtra("warn_unit_ms") || i.hasExtra("limit_ms"))
            store.setDebug(if (i.hasExtra("warn_unit_ms")) i.getLongExtra("warn_unit_ms", 0) else null,
                if (i.hasExtra("limit_ms")) i.getLongExtra("limit_ms", 0) else null)
        if (i.getBooleanExtra("reset_usage", false)) {
            val st = store.state
            store.state = st.copy(usedMs = 0, bonusMs = 0, unlockedRestOfPeriod = false, firedWarnings = emptySet(), periodEnd = 0,
                locked = st.locked)
        }
        if (i.getBooleanExtra("wipe_state", false)) store.state = GuardState()
        if (i.hasExtra("warn")) Guard.onWarning(ctx, i.getIntExtra("warn", 30), "limit")
        val st = Guard.tick(ctx, "debug")
        val cfg = store.engineConfig()
        val msg = "DEBUG cfg=$cfg used=${st.usedMs} locked=${st.locked} fired=${st.firedWarnings} settings=${store.settings}"
        Log.i("ScreenLimit", msg)
        resultData = msg
    }
}
