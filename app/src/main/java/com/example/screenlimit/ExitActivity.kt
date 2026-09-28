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
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        purpose = intent.getStringExtra(EXTRA_PURPOSE) ?: P_LOCK
        flow = ExitFlow(SecureRandom().nextLong(), delayMs = delay())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = cancel() })
        showStep()
    }

    private fun delay(): Long = if (BuildConfig.DEBUG && intent.getBooleanExtra("fast", false)) 1_000L else ExitFlow.DEFAULT_DELAY_MS

    private fun cancel() {
        flow.cancel()
        h.removeCallbacksAndMessages(null)
        Guard.exitFlowVisible = false
        if (purpose == P_LOCK && Store.get(this).state.locked) Guard.showBlock(this)
        finish()
    }

    private fun showStep() {
        h.removeCallbacksAndMessages(null)
        val step = flow.step
        val frame = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(20)) }
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                isLongClickable = false; setTextIsSelectable(false)   // без вставки из буфера
                customInsertionActionModeCallback = NoPaste; customSelectionActionModeCallback = NoPaste
            }
            til.addView(input)
            col.addView(til, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        val err = col.text("", 14f, color = getColor(R.color.danger), topDp = 8)
        if (BuildConfig.DEBUG) input?.let { et -> Guard.debugTypeSink = { t -> h.post { et.setText(t) } } }

        // Кнопки: позиция меняется от шага к шагу (верх/низ, слева/справа, порядок).
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val confirm = MaterialButton(this).apply { id = R.id.btnConfirm; isAllCaps = false; isEnabled = false }
        val cancelBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = R.id.btnCancel; isAllCaps = false; text = "Отмена — остаться"; setOnClickListener { cancel() }
        }
        val lpBtn = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) } }
        if (step.confirmFirst) { btns.addView(confirm, lpBtn()); btns.addView(cancelBtn, lpBtn()) } else { btns.addView(cancelBtn, lpBtn()); btns.addView(confirm, lpBtn()) }
        val grav = when (step.position) { 0 -> Gravity.BOTTOM; 1 -> Gravity.CENTER_VERTICAL; 2 -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL }
        val side = if (step.position >= 2) dp(40) else 0
        frame.addView(btns, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, grav).apply {
            bottomMargin = dp(if (step.position == 2) 120 else 32); topMargin = dp(if (grav == Gravity.CENTER_VERTICAL) 220 else 0)
            marginStart = if (step.position == 3) side else dp(16); marginEnd = if (step.position == 2) side else dp(16)
        })
        setContentView(frame)

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
                val p = store.pendingSettings
                if (p != null && !store.state.locked) {
                    store.settings = p; store.pendingSettings = null
                    store.log(HistoryEntry.SETTINGS, "Настройки ослаблены после 10 подтверждений")
                    Guard.tick(this, "settings")
                }
                finish()
            }
            P_OWNER -> { Owner.removeDeviceOwner(this); finish() }
            P_ADMIN -> { Owner.removeAdmin(this); finish() }
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
        Guard.grantExit(this, c)
        finish()
    }

    override fun onResume() { super.onResume(); Guard.exitFlowVisible = true }
    override fun onPause() { super.onPause(); Guard.exitFlowVisible = false }
    override fun onDestroy() { h.removeCallbacksAndMessages(null); Guard.debugTypeSink = null; super.onDestroy() }

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
    }
}
