package com.example.screenlimit

import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.example.screenlimit.core.HistoryEntry
import com.example.screenlimit.core.SettingsPolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Приложения, доступные во время блокировки (карты, такси, банк…). Добавление = ослабление → 10 подтверждений. */
class WhitelistActivity : AppCompatActivity() {
    private val selected = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Разрешённые приложения"
        if (Store.get(this).state.locked) { finish(); return }
        selected += Store.get(this).settings.whitelist
        render()
    }

    private fun render() {
        // Все приложения с иконкой (QUERY_ALL_PACKAGES + <queries> — Android 11+), плюс уже отмеченные, даже если без иконки.
        val apps = (Allowed.launchablePackages(this) + selected.filter { Allowed.installed(this, it) }).distinct()
            .filter { it != packageName }
            .map { it to Allowed.label(this, it) }.sortedBy { it.second.lowercase() }
        scrollPage {
            text("Всегда доступны без списка: телефон, SMS, экстренный вызов и сам «Стоп-экран». Отметьте, что ещё разрешить во время блокировки — эти приложения появятся на экране паузы, их можно открыть оттуда. Добавление приложений — ослабление, оно потребует 10 подтверждений; удаление из списка применяется сразу.", 14f)
            text("Если разрешённое приложение открывает браузер (3-D Secure), Google Pay, выбор банка СБП, камеру для QR или выбор файла — это допускается в течение 2 минут после работы в разрешённом приложении.", 13f)
            button("Сохранить") { save() }
            apps.forEach { (pkg, label) ->
                addView(CheckBox(context).apply {
                    text = label; isChecked = pkg in selected; textSize = 15f
                    setOnCheckedChangeListener { _, v -> if (v) selected += pkg else selected -= pkg }
                })
            }
            button("Сохранить") { save() }
        }
    }

    private fun save() {
        val store = Store.get(this)
        if (store.state.locked) { finish(); return }
        val old = store.settings
        val new = old.copy(whitelist = selected.toSet())
        if (!SettingsPolicy.isLoosening(old, new)) {
            store.settings = new; store.log(HistoryEntry.SETTINGS, "Список разрешённых приложений сокращён")
            Owner.ensureLockPolicies(this, store.state.locked); finish(); return
        }
        MaterialAlertDialogBuilder(this).setTitle("Расширение списка")
            .setMessage("Добавление приложений в разрешённые ослабляет блокировку. Пройти 10 подтверждений?")
            .setPositiveButton("Пройти") { _, _ ->
                store.pendingSettings = new
                startActivity(Intent(this, ExitActivity::class.java).putExtra(ExitActivity.EXTRA_PURPOSE, ExitActivity.P_SETTINGS))
                finish()
            }.setNegativeButton("Отмена", null).show()
    }
}
