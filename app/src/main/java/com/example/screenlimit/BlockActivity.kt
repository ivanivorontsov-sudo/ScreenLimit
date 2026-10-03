package com.example.screenlimit

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
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
import androidx.core.content.ContextCompat
import com.example.screenlimit.core.Engine
import com.example.screenlimit.core.Quotes
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Полноэкранный экран блокировки (он же лаунчер во время паузы). 1.0.3: новый дизайн + плитка-цитата. */
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
    private fun launch(i: Intent) { allowedLaunchAt = SystemClock.uptimeMillis(); runCatching { startActivity(i) } }
    private val refresh = object : Runnable { override fun run() { render(); h.postDelayed(this, 5_000) } }
    private val quoteTick = object : Runnable { override fun run() { advanceQuote(true); h.postDelayed(this, QUOTE_PERIOD_MS) } }
    private val listener: () -> Unit = { h.post { if (!Store.get(this).state.locked) release() else render() } }

    private val fg get() = ContextCompat.getColor(this, R.color.block_fg)
    private val accent get() = Color.parseColor("#C5CAE9")
    private val faint = 0x1FFFFFFF
    private val faint2 = 0x14FFFFFF

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        quoteIdx = if (Quotes.ALL.isEmpty()) 0 else Random.nextInt(Quotes.ALL.size)

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0C0E24"), Color.parseColor("#1A1C3A"), Color.parseColor("#2A1E40"))
            )
            setPadding(dp(18), dp(22), dp(18), dp(14))
        }

        // ---- Шапка: слева большие часы + дата, справа плитка-цитата ----
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val clockCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockCol.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 58f)
            setTextColor(fg); setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.02f
        })
        dateView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(accent)
        }
        clockCol.addView(dateView)
        clockCol.addView(TextView(this).apply {
            text = "⏸  Телефон на паузе"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(fg); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, 0)
        })
        header.addView(clockCol, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(buildQuoteTile(), LinearLayout.LayoutParams(dp(172), -2).apply { marginStart = dp(12) })
        screen.addView(header, LinearLayout.LayoutParams(-1, -2))

        // ---- Карточка статуса (остаток/использовано) ----
        val statusCard = MaterialCardView(this).apply {
            radius = dp(22).toFloat(); cardElevation = 0f
            setCardBackgroundColor(faint); strokeWidth = 0
        }
        val statusInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        info = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(fg); setLineSpacing(0f, 1.2f) }
        statusInner.addView(info)
        statusInner.addView(TextView(this).apply {
            text = "Доступны звонки, SMS и разрешённые приложения."; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(accent); setPadding(0, dp(8), 0, 0)
        })
        statusCard.addView(statusInner)
        screen.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        // ---- Разрешённые приложения ----
        screen.addView(TextView(this).apply {
            text = "РАЗРЕШЁННЫЕ ПРИЛОЖЕНИЯ"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(accent); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.08f
            id = R.id.allowedTitle
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); leftMargin = dp(4) })

        grid = GridLayout(this).apply { columnCount = 4; id = R.id.allowedGrid }
        val sv = ScrollView(this).apply { isFillViewport = false; addView(grid, FrameLayout.LayoutParams(-1, -2)) }
        screen.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        renderApps()

        // ---- Нижние кнопки ----
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        screen.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        fun pill(t: String, id: Int, bg: Int, fgc: Int, onClick: () -> Unit) = MaterialButton(this).apply {
            text = t; isAllCaps = false; this.id = id
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            cornerRadius = dp(18); setBackgroundColor(bg); setTextColor(fgc)
            insetTop = 0; insetBottom = 0
            setOnClickListener { onClick() }
            row.addView(this, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        pill("📞 Звонок", R.id.btnCall, Color.parseColor("#3D4376"), fg) { launch(Allowed.dialIntent()) }
        pill("✉ SMS", R.id.btnSms, Color.parseColor("#3D4376"), fg) { launch(Allowed.smsIntent(this)) }
        pill("🚨 112", R.id.btnSos, ContextCompat.getColor(this, R.color.danger), Color.WHITE) { launch(Allowed.dialIntent("112")) }

        screen.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Выйти из блокировки"; isAllCaps = false; id = R.id.btnExit
            setTextColor(fg); cornerRadius = dp(18)
            strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#4A4F86"))
            setOnClickListener {
                launch(Intent(this@BlockActivity, ExitActivity::class.java).putExtra(ExitActivity.EXTRA_PURPOSE, ExitActivity.P_LOCK))
            }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })

        setContentView(screen)
        updateDate()
        handle(intent)
    }

    /** Плитка-цитата (справа вверху): меняется каждые ~10 с и по нажатию. */
    private fun buildQuoteTile(): View {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat(); cardElevation = 0f
            setCardBackgroundColor(faint2); strokeWidth = dp(1)
            strokeColor = Color.parseColor("#33FFFFFF")
            isClickable = true; isFocusable = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(TextView(this).apply {
            text = "💡 О свободе и воле"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(accent); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.04f
        })
        quoteText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); setTextColor(fg)
            setTypeface(Typeface.create("sans-serif", Typeface.ITALIC)); setLineSpacing(0f, 1.12f)
            setPadding(0, dp(8), 0, 0)
        }
        quoteAuthor = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); setTextColor(accent)
            setPadding(0, dp(8), 0, 0); gravity = Gravity.END
        }
        col.addView(quoteText); col.addView(quoteAuthor)
        card.addView(col)
        card.setOnClickListener { advanceQuote(true); h.removeCallbacks(quoteTick); h.postDelayed(quoteTick, QUOTE_PERIOD_MS) }
        showQuote(animate = false)
        return card
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
        target.animate().alpha(0f).setDuration(260).withEndAction {
            apply(); target.alpha = 0f; target.animate().alpha(1f).setDuration(320).start()
        }.start()
    }

    private fun updateDate() {
        dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date())
            .replaceFirstChar { it.uppercase() }
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
        if (apps.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "Нет разрешённых приложений. Добавить их можно вне паузы: Настройки → «Разрешённые приложения»."
                setTextColor(accent); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(8), dp(16), dp(8), dp(8))
            }, GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0, 4, 1f)).apply { width = 0 })
            return
        }
        apps.forEach { (pkg, label, launchIntent) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(12), dp(4), dp(12))
                background = tileBg()
                isClickable = true; isFocusable = true
                contentDescription = label; tag = pkg
                val tv = TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                foreground = ContextCompat.getDrawable(context, tv.resourceId)
                addView(ImageView(context).apply {
                    setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
                }, LinearLayout.LayoutParams(dp(50), dp(50)))
                addView(TextView(context).apply {
                    text = label; setTextColor(fg); textSize = 11.5f; gravity = Gravity.CENTER; maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
                }, LinearLayout.LayoutParams(-1, -2))
                setOnClickListener {
                    Guard.noteAllowedLaunch(pkg)
                    val ok = runCatching { allowedLaunchAt = SystemClock.uptimeMillis(); startActivity(launchIntent) }.isSuccess
                    if (!ok) android.widget.Toast.makeText(this@BlockActivity, "Не удалось открыть «$label»", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
        repeat((4 - apps.size % 4) % 4) {
            grid.addView(android.widget.Space(this), GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = 1 })
        }
    }

    private fun tileBg() = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat(); setColor(faint)
        setStroke(dp(1), 0x1AFFFFFF)
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
