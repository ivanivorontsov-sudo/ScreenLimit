package com.example.screenlimit

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.screenlimit.core.Engine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Полноэкранный экран блокировки. В режиме владельца закрепляется через Lock Task. */
class BlockActivity : AppCompatActivity() {
    private lateinit var info: TextView
    private val h = Handler(Looper.getMainLooper())
    private var allowedLaunchAt = 0L
    private fun launch(i: Intent) { allowedLaunchAt = SystemClock.uptimeMillis(); runCatching { startActivity(i) } }
    private val refresh = object : Runnable { override fun run() { render(); h.postDelayed(this, 5_000) } }
    private val listener: () -> Unit = { h.post { if (!Store.get(this).state.locked) release() else render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setBackgroundColor(ContextCompat.getColor(this@BlockActivity, R.color.block_bg))
        }
        val white = ContextCompat.getColor(this, R.color.block_fg)
        root.text("⏸", 64f, gravity = Gravity.CENTER, color = white)
        root.text("Телефон на паузе", 28f, bold = true, gravity = Gravity.CENTER, color = white, topDp = 8)
        info = root.text("", 17f, gravity = Gravity.CENTER, color = white, topDp = 12)
        root.text("Доступны звонки и SMS. Остальное — после паузы.", 15f, gravity = Gravity.CENTER, color = white, topDp = 12)
        val spacer = android.view.View(this); root.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        root.button("📞  Позвонить") { launch(Allowed.dialIntent()) }.id = R.id.btnCall
        root.button("✉  SMS") { launch(Allowed.smsIntent(this)) }.id = R.id.btnSms
        root.button("🚨  Экстренный вызов 112") { launch(Allowed.dialIntent("112")) }.apply {
            id = R.id.btnSos; setBackgroundColor(ContextCompat.getColor(this@BlockActivity, R.color.danger))
        }
        root.button("Выйти из блокировки", style = 2) {
            launch(Intent(this, ExitActivity::class.java).putExtra(ExitActivity.EXTRA_PURPOSE, ExitActivity.P_LOCK))
        }.apply { id = R.id.btnExit; setTextColor(white) }
        setContentView(root)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        if (i?.action == ACTION_RELEASE || !Store.get(this).state.locked) { release(); return }
        if (Owner.maxProtection(this)) {
            Owner.ensureLockPolicies(this, true)
            val am = getSystemService(ActivityManager::class.java)
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) runCatching { startLockTask() }
        }
    }

    private fun release() {
        runCatching { if (getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask() }
        finishAndRemoveTask()
    }

    private fun render() {
        val store = Store.get(this)
        val st = store.state
        if (!st.locked) { release(); return }
        val s = store.settings
        val since = SimpleDateFormat("HH:mm", Locale("ru")).format(Date(st.lockStartedAt))
        info.text = if (st.lockReason == Engine.REASON_SCHEDULE) {
            val e = s.scheduleEndMin
            "Режим отдыха по расписанию до %02d:%02d.\nСегодня за экраном: %s".format(e / 60, e % 60, Guard.fmtDur(st.usedMs))
        } else "Дневной лимит исчерпан.\nСегодня за экраном: ${Guard.fmtDur(st.usedMs)} из ${Guard.fmtDur(Engine.limitMs(s, store.engineConfig()) + st.bonusMs)}\nБлокировка с $since"
    }

    override fun onResume() {
        super.onResume()
        Guard.addListener(listener)
        runCatching { Guard.tick(this) }
        if (!Store.get(this).state.locked) { release(); return }
        h.post(refresh)
    }

    override fun onPause() { super.onPause(); Guard.removeListener(listener); h.removeCallbacks(refresh) }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Нажата «Домой»/«Недавние» в базовом режиме: вернуть экран (AccessibilityService сделает это мгновенно).
        if (Store.get(this).state.locked && !Owner.maxProtection(this) && SystemClock.uptimeMillis() - allowedLaunchAt > 3_000)
            h.postDelayed({
                val fg = Guard.foregroundPackage(this)
                if (Store.get(this).state.locked && !isFinishing && !Allowed.isAllowedDuringLock(this, fg, Store.get(this).settings.whitelist)) Guard.showBlock(this)
            }, 1_500)
    }

    companion object { const val ACTION_RELEASE = "com.example.screenlimit.RELEASE" }
}
