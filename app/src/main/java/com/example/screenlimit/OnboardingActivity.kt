package com.example.screenlimit

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** Пошаговая выдача разрешений со статусами и советами по производителю. */
class OnboardingActivity : AppCompatActivity() {
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Настройка «Стоп-экран»"
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val items = Perms.items(this)
        val done = items.count { it.granted }
        scrollPage {
            title("Настройка защиты")
            text("«Стоп-экран» — родительский контроль для самого себя. Когда дневной лимит исчерпан, телефон блокируется: доступны только звонки, SMS и экстренный вызов. Выйти можно, только 10 раз подтвердив намерение.", 15f, topDp = 8)
            text("Выдано разрешений: $done из ${items.size}", 16f, bold = true, topDp = 12).id = R.id.permCount

            if (Build.VERSION.SDK_INT >= 33) card {
                text("⚠ Android 13+ — «Ограниченные настройки»", 15f, bold = true, color = getColor(R.color.danger))
                text("Если приложение установлено из APK-файла, Android не даст включить спец. возможности (появится «Настройка заблокирована / Ограниченная настройка»). Как разрешить:\n" +
                    "1. Сначала попробуйте включить «Стоп-экран» в спец. возможностях — получите отказ (это обязательно).\n" +
                    "2. Откройте Настройки → Приложения → «Стоп-экран» (кнопка ниже).\n" +
                    "3. Нажмите ⋮ в правом верхнем углу → «Разрешить ограниченные настройки» и подтвердите PIN-кодом.\n" +
                    "4. Вернитесь сюда и снова включите спец. возможности.", 14f)
                button("Открыть «О приложении»", style = 1) { open("appinfo") }
            }

            items.forEach { item ->
                card {
                    text((if (item.granted) "✅ " else "⬜ ") + item.title, 16f, bold = true)
                    text(item.why, 13f)
                    if (!item.granted) button("Выдать") { request(item.id) }
                }
            }
            card {
                text("🔋 Советы для вашего телефона (${Build.MANUFACTURER})", 15f, bold = true)
                text(Perms.oemTips(), 14f)
            }
            card {
                text("🛡 Максимальная защита (по желанию)", 15f, bold = true)
                text("Через компьютер и adb можно сделать «Стоп-экран» владельцем устройства: тогда во время блокировки работает режим киоска, запрещены удаление, безопасный режим и сброс.", 14f)
                button("Подробнее", style = 1) { startActivity(Intent(this@OnboardingActivity, ProtectionActivity::class.java)) }
            }
            button(if (Perms.allRequired(this@OnboardingActivity)) "Готово" else "Продолжить без части разрешений") {
                Store.get(this@OnboardingActivity).onboardingDone = true
                GuardService.start(this@OnboardingActivity)
                finish()
            }.id = R.id.btnOnboardDone
        }
    }

    private fun request(id: String) {
        if (id == "notif" && Build.VERSION.SDK_INT >= 33) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); return }
        if (id == "a11y") Toast.makeText(this, "Найдите «Стоп-экран» в списке (Скачанные/Установленные приложения) и включите", Toast.LENGTH_LONG).show()
        open(id)
    }

    private fun open(id: String) {
        // Пользователь сам выдаёт разрешение — на 2 минуты не закрываем страницы настроек (строгий режим).
        BlockerService.grantUntil = SystemClock.uptimeMillis() + 120_000
        val i = Perms.intentFor(this, id) ?: return
        runCatching { startActivity(i) }.onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
    }
}
