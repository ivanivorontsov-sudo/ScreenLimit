package com.example.screenlimit

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
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
    private lateinit var grid: GridLayout
    private var appsSig = ""
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
            setPadding(dp(16), dp(28), dp(16), dp(12))
            setBackgroundColor(ContextCompat.getColor(this@BlockActivity, R.color.block_bg))
        }
        val white = ContextCompat.getColor(this, R.color.block_fg)
        root.text("⏸", 44f, gravity = Gravity.CENTER, color = white, topDp = 0)
        root.text("Телефон на паузе", 26f, bold = true, gravity = Gravity.CENTER, color = white, topDp = 4)
        info = root.text("", 15f, gravity = Gravity.CENTER, color = white, topDp = 8)
        root.text("Доступны звонки, SMS и разрешённые приложения.", 14f, gravity = Gravity.CENTER, color = white, topDp = 8)

        // 1.0.1: сетка «Разрешённые приложения» — единственный путь открыть исключения во время паузы
        // (лаунчер во время паузы закрыт сторожем).
        root.text("РАЗРЕШЁННЫЕ ПРИЛОЖЕНИЯ", 12f, bold = true, gravity = Gravity.CENTER, color = white, topDp = 14).id = R.id.allowedTitle
        grid = GridLayout(this).apply { columnCount = 4; id = R.id.allowedGrid }
        val sv = ScrollView(this).apply { isFillViewport = false; addView(grid, FrameLayout.LayoutParams(-1, -2)) }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        renderApps()

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        fun rowBtn(t: String, id: Int, onClick: () -> Unit) = MaterialButton(this).apply {
            text = t; isAllCaps = false; this.id = id; setOnClickListener { onClick() }; setPadding(dp(4), paddingTop, dp(4), paddingBottom)
            row.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        rowBtn("📞 Звонок", R.id.btnCall) { launch(Allowed.dialIntent()) }
        rowBtn("✉ SMS", R.id.btnSms) { launch(Allowed.smsIntent(this)) }
        rowBtn("🚨 112", R.id.btnSos) { launch(Allowed.dialIntent("112")) }.setBackgroundColor(ContextCompat.getColor(this, R.color.danger))
        root.button("Выйти из блокировки", style = 2) {
            launch(Intent(this, ExitActivity::class.java).putExtra(ExitActivity.EXTRA_PURPOSE, ExitActivity.P_LOCK))
        }.apply { id = R.id.btnExit; setTextColor(white) }
        setContentView(root)
        handle(intent)
    }

    /** Иконки и названия всех разрешённых приложений; нажатие открывает приложение. */
    private fun renderApps() {
        val wl = Store.get(this).settings.whitelist
        val apps = wl.filter { it != packageName }.mapNotNull { p -> Allowed.launchIntent(this, p)?.let { Triple(p, Allowed.label(this, p), it) } }
            .sortedBy { it.second.lowercase() }
        val sig = apps.joinToString { it.first }
        if (sig == appsSig && grid.childCount > 0) return
        appsSig = sig
        grid.removeAllViews()
        val white = ContextCompat.getColor(this, R.color.block_fg)
        if (apps.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "Нет разрешённых приложений. Добавить их можно вне паузы: Настройки → «Разрешённые приложения»."
                setTextColor(white); textSize = 13f; gravity = Gravity.CENTER
            }, GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0, 4, 1f)).apply { width = 0 })
            return
        }
        apps.forEach { (pkg, label, launchIntent) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(6), dp(2), dp(6)); isClickable = true; isFocusable = true
                contentDescription = label; tag = pkg
                val tv = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                addView(ImageView(context).apply {
                    setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(TextView(context).apply {
                    text = label; setTextColor(white); textSize = 12f; gravity = Gravity.CENTER; maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                setOnClickListener {
                    Guard.noteAllowedLaunch(pkg)
                    val ok = runCatching { allowedLaunchAt = SystemClock.uptimeMillis(); startActivity(launchIntent) }.isSuccess
                    if (!ok) android.widget.Toast.makeText(this@BlockActivity, "Не удалось открыть «$label»", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        // Пустые ячейки до полной строки — чтобы иконки всегда занимали 1/4 ширины.
        repeat((4 - apps.size % 4) % 4) {
            grid.addView(android.widget.Space(this), GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = 1 })
        }
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
        renderApps()
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
