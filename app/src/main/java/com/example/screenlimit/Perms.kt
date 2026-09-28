package com.example.screenlimit

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/** Проверка и запрос всех разрешений базового режима. */
object Perms {
    data class Item(val id: String, val title: String, val why: String, val granted: Boolean, val required: Boolean = true)

    fun usage(ctx: Context): Boolean {
        val ao = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun accessibility(ctx: Context): Boolean {
        val want = ComponentName(ctx, BlockerService::class.java)
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { ComponentName.unflattenFromString(it) == want }
    }

    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun notifications(ctx: Context) = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun battery(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    fun exactAlarms(ctx: Context) = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun fullScreen(ctx: Context) = Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun items(ctx: Context) = listOf(
        Item("usage", "Доступ к истории использования", "Считать экранное время и показывать статистику по приложениям.", usage(ctx)),
        Item("a11y", "Спец. возможности «Стоп-экран»", "Мгновенно замечать запрещённые приложения во время блокировки и закрывать страницы, через которые можно снять защиту. Экранный текст никуда не отправляется.", accessibility(ctx)),
        Item("admin", "Администратор устройства", "Защита от удаления в момент слабости.", Owner.isAdmin(ctx)),
        Item("overlay", "Поверх других приложений", "Показывать экран блокировки и будильник-предупреждение поверх всего.", overlay(ctx)),
        Item("notif", "Уведомления", "Постоянное уведомление с остатком времени и предупреждения.", notifications(ctx)),
        Item("battery", "Без ограничений батареи", "Чтобы система не выгружала сервис учёта.", battery(ctx)),
        Item("exact", "Точные будильники", "Предупреждения ровно за 30/20/10 минут.", exactAlarms(ctx)),
        Item("fsi", "Полноэкранные уведомления", "Будильник-предупреждение на заблокированном экране (Android 14+).", fullScreen(ctx)),
    )

    fun allRequired(ctx: Context) = items(ctx).all { it.granted || !it.required }

    fun intentFor(ctx: Context, id: String): Intent? {
        val pkg = Uri.parse("package:${ctx.packageName}")
        return when (id) {
            "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(pkg).takeIf { it.resolveActivity(ctx.packageManager) != null }
                ?: Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "a11y" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "admin" -> Owner.addAdminIntent(ctx)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            "notif" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            "battery" -> @Suppress("BatteryLife") Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            "exact" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            "fsi" -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
            "appinfo" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            else -> null
        }
    }

    /** Советы по оболочкам производителей (dontkillmyapp.com). */
    fun oemTips(): String {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in m ->
                "Xiaomi / Redmi / POCO (MIUI / HyperOS):\n• Настройки → Приложения → «Стоп-экран» → Автозапуск — включить.\n• Там же → Контроль активности / Экономия батареи → «Нет ограничений».\n• В недавних приложениях потяните карточку «Стоп-экран» вниз и нажмите замок, чтобы закрепить.\n• Другие разрешения → «Отображать всплывающие окна в фоне» и «Экран блокировки» — разрешить."
            "huawei" in m || "honor" in m ->
                "Huawei / Honor (EMUI / MagicOS):\n• Настройки → Батарея → Запуск приложений → «Стоп-экран» → выключить «Управлять автоматически» и включить все три переключателя (автозапуск, косвенный запуск, работа в фоне).\n• Настройки → Приложения → «Стоп-экран» → Батарея → «Не оптимизировать».\n• Закрепите приложение в недавних (замок)."
            "samsung" in m ->
                "Samsung (One UI):\n• Настройки → Батарея → Ограничения фонового использования → уберите «Стоп-экран» из «Спящих» и «Глубоко спящих», добавьте в «Приложения, которые никогда не спят».\n• Настройки → Приложения → «Стоп-экран» → Батарея → «Без ограничений».\n• Отключите «Переводить неиспользуемые приложения в спящий режим»."
            "oppo" in m || "realme" in m || "oneplus" in m || "vivo" in m ->
                "OPPO / realme / OnePlus / vivo:\n• Настройки → Батарея → «Стоп-экран» → разрешить фоновую активность / «Без ограничений».\n• Приложения → Автозапуск — включить.\n• Закрепите приложение в недавних."
            else ->
                "Если оболочка производителя «убивает» фоновые приложения, найдите в настройках пункты «Автозапуск», «Работа в фоне», «Без ограничений батареи» и включите их для «Стоп-экран». Подробные инструкции для вашей модели: dontkillmyapp.com"
        }
    }
}
