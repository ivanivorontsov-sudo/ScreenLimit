package com.example.screenlimit

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.screenlimit.core.ExitChoice
import com.example.screenlimit.core.ExitFlow
import com.example.screenlimit.core.HistoryEntry
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.security.SecureRandom

/**
 * 10 подтверждений намерения. Используется для: выхода из блокировки, ослабления настроек,
 * снятия режима владельца, снятия прав администратора.
 */
class ExitActivity : AppCompatActivity() {
    private lateinit var flow: ExitFlow
    private lateinit var purpose: String
    private var seed = 0L
    private val h = Handler(Looper.getMainLooper())
    private var programmaticText = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        purpose = intent.getStringExtra(EXTRA_PURPOSE) ?: P_LOCK
        val store = Store.get(this)
        // 1.0.3: выход из блокировки защищён паролем. Если пароль задан, экран 10 подтверждений (и фреш-старт,
        // и восстановление прогресса) допустим ТОЛЬКО через действительные ворота пароля (см. PasswordActivity.startGate).
        // Любой прямой/обходной запуск P_LOCK без недавнего ввода пароля отклоняется -> назад на экран блокировки.
        if (purpose == P_LOCK && store.isPasswordSet() && !store.lockGateValid(SystemClock.elapsedRealtime())) {
            if (store.exitProgress?.purpose == P_LOCK) store.exitProgress = null
            if (store.state.locked) Guard.showBlock(this)
            finish(); return
        }
        // Случайное пересоздание экрана (клавиатура/системный диалог/возврат сторожем/смерть процесса) не сбрасывает прогресс.
        val saved = store.exitProgress?.takeIf {
            it.validFor(purpose, SystemClock.elapsedRealtime()) && (purpose != P_LOCK || store.state.locked) &&
                (purpose != P_SETTINGS || store.pendingSettings != null)
        }
        seed = saved?.seed ?: SecureRandom().nextLong()
        flow = ExitFlow(seed, delayMs = delay())
        saved?.let { flow.restore(it.step) }
        Guard.exitFlowRef = java.lang.ref.WeakReference(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = cancel() })
        showStep()
    }

    private fun delay(): Long = if (BuildConfig.DEBUG && intent.getBooleanExtra("fast", false)) 1_000L else ExitFlow.DEFAULT_DELAY_MS

    private fun saveProgress() {
        val store = Store.get(this)
        val old = store.exitProgress
        // Тот же шаг после восстановления — время не обновляем (восстановление не продлевает тайм-аут шага).
        if (old != null && old.seed == seed && old.step == flow.current && old.purpose == purpose) return
        store.exitProgress = ExitProgress(purpose, seed, flow.current, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        // Ворота пароля живут ровно столько же, сколько прогресс (тот же момент времени) — продление невозможно в обход пароля.
        if (purpose == P_LOCK && store.isPasswordSet()) store.markLockGate()
    }

    private fun forgetFlow() {
        Store.get(this).exitProgress = null
        Store.get(this).clearLockGate()
        if (Guard.exitFlowRef?.get() === this) Guard.exitFlowRef = null
    }

    private fun cancel() {
        flow.cancel()
        h.removeCallbacksAndMessages(null)
        forgetFlow()
        if (purpose == P_LOCK && Store.get(this).state.locked) Guard.showBlock(this)
        finish()
    }

    private fun showStep() {
        h.removeCallbacksAndMessages(null)
        val step = flow.step
        saveProgress()
        // 1.0.1: всё — одной прокручиваемой колонкой. Раньше кнопки лежали в FrameLayout поверх колонки
        // (gravity CENTER/BOTTOM + отступы) и при открытии клавиатуры (adjustResize) съезжали ПРЯМО НА поле фразы:
        // касание поля попадало в «Отмена — остаться» → выход отменялся → экран блокировки.
        val frame = ScrollView(this).apply { isFillViewport = true; id = R.id.exitScroll }
        // Нижний запас — чтобы кнопки можно было прокрутить выше полосы подсказок клавиатуры.
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(96)) }
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.text(purposeTitle(), 14f, color = getColor(R.color.primary), bold = true)
        col.text("Подтверждение ${step.index + 1} из ${ExitFlow.TOTAL}", 20f, bold = true, topDp = 6).id = R.id.stepCounter
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = ExitFlow.TOTAL; progress = step.index }
        col.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(8) })
        col.text(step.question, 22f, topDp = 24).id = R.id.stepQuestion

        var input: TextInputEditText? = null
        if (step.phrase != null) {
            col.text("Введите дословно:\n«${step.phrase}»", 16f, topDp = 16)
            val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "Фраза" }
            input = TextInputEditText(til.context).apply {
                id = R.id.stepPhrase
                // 1.0.1: обычное текстовое поле (НЕ пароль): поля паролей вызывают на части телефонов отдельную
                // «безопасную клавиатуру» OEM и латинскую раскладку. Подсказки и обучение клавиатуры выключены.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                isLongClickable = false; setTextIsSelectable(false)   // без вставки из буфера
                customInsertionActionModeCallback = NoPaste; customSelectionActionModeCallback = NoPaste
                // Вставка через панель буфера обмена клавиатуры: не принимаем вставку длиннее слова за один раз.
                filters = arrayOf(android.text.InputFilter { src, st, en, _, _, _ -> if (!programmaticText && en - st > MAX_INSERT) "" else null })
            }
            til.addView(input)
            col.addView(til, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        val err = col.text("", 14f, color = getColor(R.color.danger), topDp = 8)
        if (BuildConfig.DEBUG) input?.let { et -> Guard.debugTypeSink = { t -> h.post { programmaticText = true; et.setText(t); programmaticText = false } } }

        // Кнопки: позиция меняется от шага к шагу (отступ сверху, слева/справа, порядок), но всегда ПОД полем ввода.
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val confirm = MaterialButton(this).apply { id = R.id.btnConfirm; isAllCaps = false; isEnabled = false }
        if (BuildConfig.DEBUG) Guard.debugStateSource = { debugState(step, input, confirm) }
        val cancelBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = R.id.btnCancel; isAllCaps = false; text = "Отмена — остаться"; setOnClickListener { cancel() }
        }
        val lpBtn = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) } }
        if (step.confirmFirst) { btns.addView(confirm, lpBtn()); btns.addView(cancelBtn, lpBtn()) } else { btns.addView(cancelBtn, lpBtn()); btns.addView(confirm, lpBtn()) }
        val side = if (step.position >= 2) dp(40) else 0
        col.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(if (step.phrase != null) when (step.position) { 0 -> 12; 1 -> 28; 2 -> 44; else -> 20 }
                else when (step.position) { 0 -> 16; 1 -> 64; 2 -> 112; else -> 40 })
            marginStart = if (step.position == 3) side else 0; marginEnd = if (step.position == 2) side else 0
        })
        setContentView(frame)
        // Клавиатура открыта (поле в фокусе) → прокрутить так, чтобы кнопки были видны над клавиатурой, не пряча поле.
        input?.let { et ->
            frame.viewTreeObserver.addOnGlobalLayoutListener {
                if (!et.hasFocus()) return@addOnGlobalLayoutListener
                val til = et.parent?.parent as? View ?: et
                val want = btns.bottom + dp(72) - frame.height
                val target = minOf(want, til.top - dp(8)).coerceAtLeast(0)
                if (target > frame.scrollY) frame.scrollTo(0, target)
            }
        }

        flow.onShown(SystemClock.uptimeMillis())
        val tick = object : Runnable {
            override fun run() {
                val left = flow.activatesAt() - SystemClock.uptimeMillis()
                if (left > 0) { confirm.isEnabled = false; confirm.text = "Подождите ${(left + 999) / 1000} с"; h.postDelayed(this, 200) }
                else { confirm.isEnabled = true; confirm.text = step.confirmLabel }
            }
        }
        h.post(tick)
        confirm.setOnClickListener {
            when (val r = flow.confirm(SystemClock.uptimeMillis(), input?.text?.toString())) {
                ExitFlow.Result.Advanced -> showStep()
                ExitFlow.Result.Completed -> onCompleted()
                ExitFlow.Result.Cancelled -> cancel()
                is ExitFlow.Result.Rejected -> err.text = if (r.why == "phrase mismatch") "Фраза введена неверно. Введите её точно." else "Подождите, пока кнопка станет активной."
            }
        }
    }

    private fun debugState(step: ExitFlow.Step, input: TextInputEditText?, confirm: MaterialButton): String {
        fun b(v: View): String { val l = IntArray(2); v.getLocationOnScreen(l); return "[${l[0]},${l[1]},${l[0] + v.width},${l[1] + v.height}]" }
        return org.json.JSONObject().put("step", step.index + 1).put("phrase", step.phrase ?: "")
            .put("typed", input?.text?.toString() ?: "").put("field", input?.let { b(it) } ?: "")
            .put("focused", input?.hasFocus() == true).put("confirm", b(confirm)).put("enabled", confirm.isEnabled)
            .put("resumed", resumed).put("alive", Guard.exitFlowAlive()).toString()
    }

    private fun purposeTitle() = when (purpose) {
        P_SETTINGS -> "Ослабление настроек"
        P_OWNER -> "Снятие максимальной защиты"
        P_ADMIN -> "Снятие защиты от удаления"
        else -> "Выход из блокировки"
    }

    private fun onCompleted() {
        h.removeCallbacksAndMessages(null)
        val store = Store.get(this)
        when (purpose) {
            P_LOCK -> showChoice()
            P_SETTINGS -> {
                forgetFlow()
                val p = store.pendingSettings
                if (p != null && !store.state.locked) {
                    store.settings = p; store.pendingSettings = null
                    store.log(HistoryEntry.SETTINGS, "Настройки ослаблены после 10 подтверждений")
                    Guard.tick(this, "settings")
                    Owner.ensureLockPolicies(this, store.state.locked)   // список Lock Task = новый список разрешённых
                }
                finish()
            }
            P_OWNER -> { forgetFlow(); Owner.removeDeviceOwner(this); finish() }
            P_ADMIN -> { forgetFlow(); Owner.removeAdmin(this); finish() }
        }
    }

    private fun showChoice() {
        scrollPage {
            title("10 из 10 подтверждений")
            text("Выберите, как долго телефон будет разблокирован. Выход запишется в историю.", topDp = 8)
            section("ДОПОЛНИТЕЛЬНОЕ ВРЕМЯ")
            listOf(15, 30, 60).forEach { m -> button("+$m минут") { exit(ExitChoice.Extra(m * 60_000L)) }.id = when (m) { 15 -> R.id.btnExtra15; 30 -> R.id.btnExtra30; else -> R.id.btnExtra60 } }
            section("ИЛИ")
            button("До конца суток", style = 1) { exit(ExitChoice.RestOfDay) }.id = R.id.btnRestOfDay
            button("Передумал — остаться в блокировке", style = 2) { cancel() }
        }
    }

    private fun exit(c: ExitChoice) {
        forgetFlow()
        Guard.grantExit(this, c)
        finish()
    }

    // 1.0.1: onPause больше НЕ снимает защиту экрана подтверждений (клавиатура, выбор клавиатуры, системные диалоги
    // могут ставить активность на паузу). Экран считается «живым» до onDestroy — см. Guard.exitFlowAlive().
    override fun onResume() { super.onResume(); resumed = true; BlockerService.coverDone(); Guard.exitFlowRef = java.lang.ref.WeakReference(this) }
    override fun onPause() { super.onPause(); resumed = false }
    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        if (Guard.exitFlowRef?.get() === this) { Guard.exitFlowRef = null; resumed = false }
        Guard.debugTypeSink = null; Guard.debugStateSource = null
        super.onDestroy()
    }

    private object NoPaste : android.view.ActionMode.Callback {
        override fun onCreateActionMode(m: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onPrepareActionMode(m: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onActionItemClicked(m: android.view.ActionMode?, item: android.view.MenuItem?) = false
        override fun onDestroyActionMode(m: android.view.ActionMode?) {}
    }

    companion object {
        const val EXTRA_PURPOSE = "purpose"
        const val P_LOCK = "lock"
        const val P_SETTINGS = "settings"
        const val P_OWNER = "owner"
        const val P_ADMIN = "admin"
        const val MAX_INSERT = 12
        @Volatile var resumed = false
    }
}
