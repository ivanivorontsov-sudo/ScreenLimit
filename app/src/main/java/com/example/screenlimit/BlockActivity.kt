package com.example.screenlimit

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.screenlimit.core.Engine
import com.example.screenlimit.core.Quotes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Полноэкранный экран блокировки (он же лаунчер во время паузы).
 * 1.0.3: оформление в стиле стартового экрана Windows Phone 8.1 (Metro):
 * чёрный фон, плоские квадратные плитки сплошных цветов без скруглений и теней, живые плитки часов и цитаты,
 * тонкие шрифты sans-serif-light/thin, лёгкий «наклон» плитки при нажатии.
 */
class BlockActivity : AppCompatActivity() {
    private lateinit var info: TextView
    private lateinit var grid: GridLayout
    private lateinit var dateView: TextView
    private lateinit var quoteText: TextView
    private lateinit var quoteAuthor: TextView
    private var quoteIdx = 0
    private var appsSig = ""
    private val h = Handler(Looper.getMainLooper())
    private var allowedLaunchAt = 0L

    private var cell = 0            // сторона базовой плитки 1x1 (px)
    private val gap get() = dp(4)   // зазор между плитками

    private val thin get() = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val light get() = Typeface.create("sans-serif-light", Typeface.NORMAL)

    // Палитра акцентов Windows Phone.
    private val WP = intArrayOf(
        0xFF0050EF.toInt(), // cobalt
        0xFF00ABA9.toInt(), // teal
        0xFFA4C400.toInt(), // lime
        0xFFD80073.toInt(), // magenta
        0xFFA20025.toInt(), // crimson
        0xFFFA6800.toInt(), // orange
        0xFF008A00.toInt(), // emerald
        0xFFAA00FF.toInt(), // violet
        0xFF1BA1E2.toInt(), // cyan
        0xFFE3C800.toInt(), // yellow
        0xFF60A917.toInt(), // green
        0xFF76608A.toInt(), // mauve
        0xFF647687.toInt(), // steel
        0xFFE51400.toInt(), // red
        0xFF6A00FF.toInt(), // indigo
        0xFF825A2C.toInt(), // sienna
    )
    private val cCobalt = 0xFF0050EF.toInt()
    private val cTeal = 0xFF00ABA9.toInt()
    private val cEmerald = 0xFF008A00.toInt()
    private val cCyan = 0xFF1BA1E2.toInt()
    private val cCrimson = 0xFFA20025.toInt()
    private val cSteel = 0xFF647687.toInt()

    private fun launch(i: Intent) { allowedLaunchAt = SystemClock.uptimeMillis(); runCatching { startActivity(i) } }
    private val refresh = object : Runnable { override fun run() { render(); h.postDelayed(this, 5_000) } }
    private val quoteTick = object : Runnable { override fun run() { advanceQuote(true); h.postDelayed(this, QUOTE_PERIOD_MS) } }
    private val listener: () -> Unit = { h.post { if (!Store.get(this).state.locked) release() else render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        quoteIdx = if (Quotes.ALL.isEmpty()) 0 else Random.nextInt(Quotes.ALL.size)

        val pad = dp(6)
        val sideCol = dp(4)                       // вклад полей плитки в ширину столбца
        val colW = (resources.displayMetrics.widthPixels - pad * 2) / 4
        cell = colW - sideCol                      // ширина/высота плитки 1x1

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(pad, dp(20), pad, dp(16))
        }

        // ---- Верхняя решётка: живые плитки (часы 2x2, цитата 2x2) + связь (Звонок 2x1, SMS 1x1, 112 1x1) ----
        val topGrid = GridLayout(this).apply { columnCount = 4 }

        topGrid.addTile(clockTile(), row = 0, col = 0, colSpan = 2, rowSpan = 2)
        topGrid.addTile(quoteTile(), row = 0, col = 2, colSpan = 2, rowSpan = 2)
        topGrid.addTile(commTile("\uD83D\uDCDE", "Звонок", cEmerald, R.id.btnCall) { launch(Allowed.dialIntent()) },
            row = 2, col = 0, colSpan = 2, rowSpan = 1)
        topGrid.addTile(commTile("\u2709", "SMS", cCyan, R.id.btnSms) { launch(Allowed.smsIntent(this)) },
            row = 2, col = 2, colSpan = 1, rowSpan = 1)
        topGrid.addTile(commTile("\uD83D\uDEA8", "112", cCrimson, R.id.btnSos) { launch(Allowed.dialIntent("112")) },
            row = 2, col = 3, colSpan = 1, rowSpan = 1)
        content.addView(topGrid)

        // ---- Заголовок раздела (как строчные заголовки списков WP) ----
        content.addView(TextView(this).apply {
            text = "приложения"; id = R.id.allowedTitle
            setTextColor(Color.WHITE); typeface = light
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(dp(6), dp(22), 0, dp(8))
        })

        // ---- Плитки разрешённых приложений ----
        grid = GridLayout(this).apply { columnCount = 4; id = R.id.allowedGrid }
        content.addView(grid)
        renderApps()

        // ---- Широкая плитка выхода (через пароль, затем 10 подтверждений) ----
        val exit = FrameLayout(this).apply {
            setBackgroundColor(cSteel); id = R.id.btnExit
            addView(TextView(context).apply {
                text = "Выйти из блокировки"; setTextColor(Color.WHITE); typeface = light
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.CENTER })
            metroTile {
                allowedLaunchAt = SystemClock.uptimeMillis()
                // 1.0.3: сначала пароль (если задан), затем прежние 10 подтверждений.
                PasswordActivity.startGate(this@BlockActivity, ExitActivity.P_LOCK)
            }
        }
        content.addView(exit, LinearLayout.LayoutParams(4 * cell + 3 * gap, cell).apply {
            topMargin = dp(16); leftMargin = dp(2)
        })

        val root = ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(content) }
        setContentView(root)
        updateDate()
        handle(intent)
    }

    // ---------- Плитки ----------

    private fun GridLayout.addTile(v: View, row: Int, col: Int, colSpan: Int, rowSpan: Int) {
        val lp = GridLayout.LayoutParams(GridLayout.spec(row, rowSpan), GridLayout.spec(col, colSpan))
        lp.width = colSpan * cell + (colSpan - 1) * gap
        lp.height = rowSpan * cell + (rowSpan - 1) * gap
        lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
        addView(v, lp)
    }

    /** Живая плитка часов: тонкие крупные часы, дата, статус лимита. */
    private fun clockTile(): View {
        val fl = FrameLayout(this).apply { setBackgroundColor(cCobalt) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(14), dp(12), dp(12), dp(12))
        }
        col.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            setTextColor(Color.WHITE); typeface = thin; includeFontPadding = false
        })
        dateView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(0xFFE3E6FF.toInt()); typeface = light
            setPadding(0, dp(4), 0, 0)
        }
        col.addView(dateView)
        info = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); setTextColor(0xFFDDE2FF.toInt()); typeface = light
            setLineSpacing(0f, 1.1f); setPadding(0, dp(10), 0, 0)
        }
        col.addView(info)
        fl.addView(col, FrameLayout.LayoutParams(-1, -1))
        fl.metroTile(null)   // наклон при нажатии, без действия
        return fl
    }

    /** Живая плитка-цитата (меняется каждые ~10 с и по нажатию). */
    private fun quoteTile(): View {
        val fl = FrameLayout(this).apply { setBackgroundColor(cTeal) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(12), dp(12))
        }
        col.addView(TextView(this).apply {
            text = "о свободе и воле"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFDFFFFE.toInt()); typeface = light
        })
        quoteText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); setTextColor(Color.WHITE); typeface = light
            setLineSpacing(0f, 1.12f); setPadding(0, dp(10), 0, 0); maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        quoteAuthor = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(0xFFDFFFFE.toInt()); typeface = light
            setPadding(0, dp(8), 0, 0); gravity = Gravity.END
        }
        col.addView(quoteText); col.addView(quoteAuthor)
        fl.addView(col, FrameLayout.LayoutParams(-1, -1))
        fl.metroTile { advanceQuote(true); h.removeCallbacks(quoteTick); h.postDelayed(quoteTick, QUOTE_PERIOD_MS) }
        showQuote(animate = false)
        return fl
    }

    /** Плитка связи: эмодзи по центру + подпись снизу слева. */
    private fun commTile(glyph: String, label: String, color: Int, vId: Int, onClick: () -> Unit): View {
        val fl = FrameLayout(this).apply { setBackgroundColor(color); id = vId }
        fl.addView(TextView(this).apply {
            text = glyph; setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); setTextColor(Color.WHITE)
        }, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER })
        fl.addView(TextView(this).apply {
            text = label; setTextColor(Color.WHITE); typeface = light; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.START; setMargins(dp(10), 0, 0, dp(8)) })
        fl.metroTile { onClick() }
        return fl
    }

    /** Наклон/нажатие плитки в стиле Metro: лёгкое сжатие на касании. */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.metroTile(onTap: (() -> Unit)?) {
        isClickable = true; isFocusable = true
        onTap?.let { setOnClickListener { _ -> it() } }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
            }
            false
        }
    }

    private fun advanceQuote(animate: Boolean) {
        if (Quotes.ALL.isEmpty()) return
        quoteIdx = (quoteIdx + 1) % Quotes.ALL.size
        showQuote(animate)
    }

    private fun showQuote(animate: Boolean) {
        if (Quotes.ALL.isEmpty()) return
        val q = Quotes.ALL[quoteIdx % Quotes.ALL.size]
        val apply = { quoteText.text = "«${q.text}»"; quoteAuthor.text = "— ${q.author}" }
        val target = quoteText.parent as? View ?: quoteText
        if (!animate) { apply(); return }
        target.animate().alpha(0f).setDuration(240).withEndAction {
            apply(); target.alpha = 0f; target.animate().alpha(1f).setDuration(320).start()
        }.start()
    }

    private fun updateDate() {
        dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date())
            .replaceFirstChar { it.uppercase() }
    }

    /** Квадратные плитки всех разрешённых приложений: иконка по центру, подпись снизу слева. */
    private fun renderApps() {
        val wl = Store.get(this).settings.whitelist
        val apps = wl.filter { it != packageName }.mapNotNull { p -> Allowed.launchIntent(this, p)?.let { Triple(p, Allowed.label(this, p), it) } }
            .sortedBy { it.second.lowercase() }
        val sig = apps.joinToString { it.first }
        if (sig == appsSig && grid.childCount > 0) return
        appsSig = sig
        grid.removeAllViews()
        if (apps.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Нет разрешённых приложений. Добавить их можно вне паузы: Настройки → «Разрешённые приложения»."
                setTextColor(Color.WHITE); typeface = light; textSize = 13f; setPadding(dp(6), dp(10), dp(6), dp(10))
            }
            val lp = GridLayout.LayoutParams(GridLayout.spec(0, 1), GridLayout.spec(0, 4))
            lp.width = 4 * cell + 3 * gap; lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
            grid.addView(tv, lp)
            return
        }
        val iconSz = (cell * 0.46f).toInt()
        apps.forEachIndexed { i, (pkg, label, launchIntent) ->
            val color = WP[(i + 2) % WP.size]
            val fl = FrameLayout(this).apply {
                setBackgroundColor(color); contentDescription = label; tag = pkg
                addView(ImageView(context).apply {
                    setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
                }, FrameLayout.LayoutParams(iconSz, iconSz).apply { gravity = Gravity.CENTER; topMargin = -dp(6) })
                addView(TextView(context).apply {
                    text = label; setTextColor(Color.WHITE); typeface = light
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM or Gravity.START; setMargins(dp(8), 0, dp(6), dp(6)) })
                metroTile {
                    Guard.noteAllowedLaunch(pkg)
                    val ok = runCatching { allowedLaunchAt = SystemClock.uptimeMillis(); startActivity(launchIntent) }.isSuccess
                    if (!ok) android.widget.Toast.makeText(this@BlockActivity, "Не удалось открыть «$label»", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            val lp = GridLayout.LayoutParams(GridLayout.spec(i / 4, 1), GridLayout.spec(i % 4, 1))
            lp.width = cell; lp.height = cell; lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
            grid.addView(fl, lp)
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
        updateDate()
        val since = SimpleDateFormat("HH:mm", Locale("ru")).format(Date(st.lockStartedAt))
        info.text = if (st.lockReason == Engine.REASON_SCHEDULE) {
            val e = s.scheduleEndMin
            "Отдых по расписанию до %02d:%02d\nЗа экраном сегодня: %s".format(e / 60, e % 60, Guard.fmtDur(st.usedMs))
        } else "Дневной лимит исчерпан\nЗа экраном: ${Guard.fmtDur(st.usedMs)} из ${Guard.fmtDur(Engine.limitMs(s, store.engineConfig()) + st.bonusMs)}\nБлокировка с $since"
    }

    override fun onResume() {
        super.onResume()
        Guard.addListener(listener)
        runCatching { Guard.tick(this) }
        if (!Store.get(this).state.locked) { release(); return }
        renderApps()
        h.post(refresh)
        h.removeCallbacks(quoteTick); h.postDelayed(quoteTick, QUOTE_PERIOD_MS)
    }

    override fun onPause() {
        super.onPause(); Guard.removeListener(listener)
        h.removeCallbacks(refresh); h.removeCallbacks(quoteTick)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Нажата «Домой»/«Недавние» в базовом режиме: вернуть экран (AccessibilityService сделает это мгновенно).
        if (Store.get(this).state.locked && !Owner.maxProtection(this) && SystemClock.uptimeMillis() - allowedLaunchAt > 3_000)
            h.postDelayed({
                val fgPkg = Guard.foregroundPackage(this)
                if (Store.get(this).state.locked && !isFinishing && !Allowed.isAllowedDuringLock(this, fgPkg, Store.get(this).settings.whitelist)) Guard.showBlock(this)
            }, 1_500)
    }

    companion object {
        const val ACTION_RELEASE = "com.example.screenlimit.RELEASE"
        const val QUOTE_PERIOD_MS = 10_000L
    }
}
