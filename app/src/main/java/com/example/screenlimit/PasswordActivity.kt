package com.example.screenlimit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.screenlimit.core.Passcode
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Пароль защиты настроек (1.0.2).
 *
 * Идея: пароль задаёт ДРУГОЙ человек и хранит у себя (пользователь его не знает — фотографирует/прячет).
 *  - УЖЕСТОЧЕНИЕ ограничений пароль НЕ требует (применяется сразу — обрабатывается в вызывающих экранах).
 *  - ОСЛАБЛЕНИЕ требует сначала пароль (этот экран), а затем 10 подтверждений ([ExitActivity]).
 *
 * Пароль не хранится в открытом виде — только PBKDF2-SHA256 + соль (см. [Store]/[Passcode]).
 * После 5 неверных попыток включается эскалирующая пауза. Забытый пароль сбрасывается кодом восстановления,
 * который показан один раз при установке.
 */
class PasswordActivity : AppCompatActivity() {
    private lateinit var next: String
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        next = intent.getStringExtra(EXTRA_NEXT) ?: NEXT_NONE
        // Назад = отмена действия (ничего не ослабляем). Для P_SETTINGS подчистим отложенные настройки.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { abort() }
        })
        when (intent.getStringExtra(EXTRA_MODE)) {
            M_SET -> showSetPassword()
            else -> showVerify()
        }
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun abort() {
        if (next == ExitActivity.P_SETTINGS) Store.get(this).pendingSettings = null
        finish()
    }

    // ---------- Установка / смена пароля ----------
    private fun showSetPassword() {
        h.removeCallbacksAndMessages(null)
        var f1: TextInputEditText? = null
        var f2: TextInputEditText? = null
        lateinit var err: android.widget.TextView
        scrollPage {
            title("Пароль защиты настроек")
            text("Этот пароль задаёт и хранит другой человек. Пользователю его знать не нужно — запишите или сфотографируйте пароль и спрячьте. " +
                "Он потребуется, чтобы ОСЛАБИТЬ ограничения (увеличить лимит, добавить приложения, выключить защиту). " +
                "Чтобы УЖЕСТОЧИТЬ ограничения, пароль не нужен.", topDp = 8)
            f1 = passwordField("Новый пароль (не короче ${Passcode.MIN_PASSWORD_LEN})")
            f2 = passwordField("Повторите пароль")
            err = text("", 14f, color = getColor(R.color.danger), topDp = 8)
            button("Сохранить пароль") {
                val p1 = f1?.text?.toString().orEmpty()
                val p2 = f2?.text?.toString().orEmpty()
                when {
                    !Passcode.isAcceptablePassword(p1) -> err.text = "Пароль слишком короткий — минимум ${Passcode.MIN_PASSWORD_LEN} символов."
                    p1 != p2 -> err.text = "Пароли не совпадают."
                    else -> {
                        val code = Store.get(this@PasswordActivity).setPassword(p1)
                        showRecovery(code)
                    }
                }
            }
            button("Отмена", style = 2) { abort() }
        }
    }

    private fun showRecovery(code: String) {
        h.removeCallbacksAndMessages(null)
        scrollPage {
            title("Код восстановления")
            text("Запишите или сфотографируйте этот код и спрячьте его. Он показывается ТОЛЬКО СЕЙЧАС и больше нигде не хранится. " +
                "Этим кодом можно сбросить пароль, если он забыт.", color = getColor(R.color.danger), topDp = 8)
            card {
                addView(android.widget.TextView(context).apply {
                    text = code
                    textSize = 24f
                    setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextIsSelectable(true)
                    setTextColor(getColor(R.color.on_surface))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val chk = com.google.android.material.checkbox.MaterialCheckBox(this@PasswordActivity).apply {
                text = "Я записал(а) код восстановления в надёжном месте"; textSize = 15f
            }
            addView(chk, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            val done = button("Готово") {}
            done.isEnabled = false
            chk.setOnCheckedChangeListener { _, v -> done.isEnabled = v }
            done.setOnClickListener { if (chk.isChecked) afterPasswordSet() }
        }
    }

    private fun afterPasswordSet() {
        when (next) {
            ExitActivity.P_SETTINGS, ExitActivity.P_OWNER, ExitActivity.P_ADMIN -> {
                startActivity(Intent(this, ExitActivity::class.java).putExtra(ExitActivity.EXTRA_PURPOSE, next))
                finish()
            }
            else -> { toast("Пароль сохранён"); finish() }
        }
    }

    // ---------- Проверка пароля ----------
    private fun showVerify() {
        h.removeCallbacksAndMessages(null)
        var field: TextInputEditText? = null
        lateinit var err: android.widget.TextView
        lateinit var ok: com.google.android.material.button.MaterialButton
        scrollPage {
            title("Введите пароль")
            text(gateReason(next), topDp = 8)
            text("Пароль знает тот, кто настраивал защиту.", 14f, topDp = 6)
            field = passwordField("Пароль")
            err = text("", 14f, color = getColor(R.color.danger), topDp = 8)
            ok = button("Продолжить") {
                val rem = Store.get(this@PasswordActivity).pwLockRemainingMs()
                if (rem > 0) { err.text = lockMsg(rem); return@button }
                val pw = field?.text?.toString().orEmpty()
                val store = Store.get(this@PasswordActivity)
                if (store.verifyPassword(pw)) {
                    store.resetPwFails()
                    onVerified()
                } else {
                    store.recordPwFail()
                    field?.setText("")
                    val r = store.pwLockRemainingMs()
                    err.text = if (r > 0) lockMsg(r) else "Неверный пароль. Осталось попыток до паузы: " +
                        (Passcode.MAX_FAILS_BEFORE_LOCK - store.pwFailCount).coerceAtLeast(0)
                    refreshLock(ok, err)
                }
            }
            button("Забыли пароль?", style = 2) { showRecoveryEntry() }
            button("Отмена", style = 2) { abort() }
        }
        refreshLock(ok, err)
    }

    private fun onVerified() {
        when (next) {
            NEXT_CHANGE -> showSetPassword()
            ExitActivity.P_SETTINGS, ExitActivity.P_OWNER, ExitActivity.P_ADMIN -> {
                startActivity(Intent(this, ExitActivity::class.java).putExtra(ExitActivity.EXTRA_PURPOSE, next))
                finish()
            }
            else -> finish()
        }
    }

    private fun showRecoveryEntry() {
        h.removeCallbacksAndMessages(null)
        var field: TextInputEditText? = null
        lateinit var err: android.widget.TextView
        lateinit var ok: com.google.android.material.button.MaterialButton
        scrollPage {
            title("Код восстановления")
            text("Введите код восстановления, показанный при установке пароля. После этого можно задать новый пароль.", topDp = 8)
            field = textField("Код восстановления")
            err = text("", 14f, color = getColor(R.color.danger), topDp = 8)
            ok = button("Сбросить пароль") {
                val rem = Store.get(this@PasswordActivity).pwLockRemainingMs()
                if (rem > 0) { err.text = lockMsg(rem); return@button }
                val code = field?.text?.toString().orEmpty()
                val store = Store.get(this@PasswordActivity)
                if (store.verifyRecovery(code)) {
                    store.resetPwFails()
                    showSetPassword()
                } else {
                    store.recordPwFail()
                    val r = store.pwLockRemainingMs()
                    err.text = if (r > 0) lockMsg(r) else "Код неверный."
                    refreshLock(ok, err)
                }
            }
            button("Назад", style = 2) { showVerify() }
        }
        refreshLock(ok, err)
    }

    /** Пока действует пауза — блокируем кнопку и показываем обратный отсчёт. */
    private fun refreshLock(btn: com.google.android.material.button.MaterialButton, err: android.widget.TextView) {
        val rem = Store.get(this).pwLockRemainingMs()
        if (rem <= 0) { btn.isEnabled = true; return }
        btn.isEnabled = false
        err.text = lockMsg(rem)
        h.postDelayed({ refreshLock(btn, err) }, 500)
    }

    private fun lockMsg(remMs: Long) = "Слишком много неверных попыток. Подождите ${(remMs + 999) / 1000} с."

    private fun gateReason(next: String) = when (next) {
        ExitActivity.P_OWNER -> "Снятие максимальной защиты — это ослабление. Нужен пароль, затем 10 подтверждений."
        ExitActivity.P_ADMIN -> "Снятие прав администратора — это ослабление. Нужен пароль, затем 10 подтверждений."
        NEXT_CHANGE -> "Чтобы сменить пароль, введите текущий пароль."
        else -> "Ослабление ограничений требует пароль, а затем 10 подтверждений."
    }

    // ---------- Поля ввода ----------
    private fun LinearLayout.passwordField(hint: String): TextInputEditText {
        val til = TextInputLayout(this@PasswordActivity, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val et = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        til.addView(et)
        addView(til, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        return et
    }

    private fun LinearLayout.textField(hint: String): TextInputEditText {
        val til = TextInputLayout(this@PasswordActivity, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { this.hint = hint }
        val et = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        til.addView(et)
        addView(til, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        return et
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_NEXT = "next"
        const val M_SET = "set"
        const val M_VERIFY = "verify"
        const val NEXT_NONE = "none"
        const val NEXT_CHANGE = "change"

        /** Старт первичной установки пароля (из настроек). */
        fun startSet(ctx: Context) {
            ctx.startActivity(Intent(ctx, PasswordActivity::class.java).putExtra(EXTRA_MODE, M_SET).putExtra(EXTRA_NEXT, NEXT_NONE))
        }

        /** Старт смены пароля: проверка текущего (или кода восстановления), затем ввод нового. */
        fun startChange(ctx: Context) {
            ctx.startActivity(Intent(ctx, PasswordActivity::class.java).putExtra(EXTRA_MODE, M_VERIFY).putExtra(EXTRA_NEXT, NEXT_CHANGE))
        }

        /**
         * Ворота для ОСЛАБЛЕНИЯ. Если пароль задан — сначала просим пароль, затем [nextPurpose] (10 подтверждений).
         * Если пароль ещё не задан — сразу к 10 подтверждениям (поведение 1.0.1, обратная совместимость).
         */
        fun startGate(ctx: Context, nextPurpose: String) {
            if (Store.get(ctx).isPasswordSet())
                ctx.startActivity(Intent(ctx, PasswordActivity::class.java).putExtra(EXTRA_MODE, M_VERIFY).putExtra(EXTRA_NEXT, nextPurpose))
            else
                ctx.startActivity(Intent(ctx, ExitActivity::class.java).putExtra(ExitActivity.EXTRA_PURPOSE, nextPurpose))
        }
    }
}
