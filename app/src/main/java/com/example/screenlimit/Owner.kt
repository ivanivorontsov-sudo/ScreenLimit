package com.example.screenlimit

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.example.screenlimit.core.HistoryEntry

class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        if (Guard.isLocked(context)) Guard.showBlock(context)
        return "Отключение администратора ослабит защиту «Стоп-экран». Правильный путь — кнопка в приложении с 10 подтверждениями."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Store.get(context).log(HistoryEntry.TAMPER, "Права администратора устройства отключены")
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Store.get(context).log(HistoryEntry.OWNER, "Права администратора устройства включены")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {}
    override fun onLockTaskModeExiting(context: Context, intent: Intent) {}
}

/**
 * Уровни защиты.
 *  - Администратор устройства: удаление приложения требует сначала снять админа (а эта страница
 *    блокируется AccessibilityService во время блокировки/строгого режима).
 *  - Владелец устройства (Device Owner, ставится через adb): Lock Task (киоск), запрет удаления,
 *    запрет безопасного режима, сброса, смены времени, остановки/очистки данных приложения.
 */
object Owner {
    private const val TAG = "ScreenLimit"
    const val ADB_COMMAND = "adb shell dpm set-device-owner com.example.screenlimit/.AdminReceiver"

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isAdmin(ctx: Context) = dpm(ctx).isAdminActive(admin(ctx))
    fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /** Включена ли «максимальная защита» (владелец + флаг в настройках). */
    fun maxProtection(ctx: Context) = isDeviceOwner(ctx) && Store.get(ctx).ownerProtection

    private val ALWAYS = listOf(UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_ADD_USER)
    private val DURING_LOCK = listOf(UserManager.DISALLOW_CONFIG_DATE_TIME, UserManager.DISALLOW_APPS_CONTROL,
        UserManager.DISALLOW_INSTALL_APPS, UserManager.DISALLOW_UNINSTALL_APPS)

    /** Идемпотентно применяет политики владельца для текущего состояния. */
    fun ensureLockPolicies(ctx: Context, locked: Boolean) {
        if (!isDeviceOwner(ctx)) return
        val dpm = dpm(ctx); val a = admin(ctx)
        val on = Store.get(ctx).ownerProtection
        runCatching {
            val wl = Store.get(ctx).settings.whitelist
            dpm.setLockTaskPackages(a, if (on) Allowed.lockTaskPackages(ctx, wl) else arrayOf(ctx.packageName))
            if (Build.VERSION.SDK_INT >= 28) dpm.setLockTaskFeatures(a,
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS)
            dpm.setUninstallBlocked(a, ctx.packageName, on)
            if (Build.VERSION.SDK_INT >= 30) dpm.setUserControlDisabledPackages(a, if (on) listOf(ctx.packageName) else emptyList())
            ALWAYS.forEach { r -> if (on) dpm.addUserRestriction(a, r) else dpm.clearUserRestriction(a, r) }
            DURING_LOCK.forEach { r -> if (on && locked) dpm.addUserRestriction(a, r) else dpm.clearUserRestriction(a, r) }
            val adb = UserManager.DISALLOW_DEBUGGING_FEATURES
            if (on && locked && Store.get(ctx).blockAdbDuringLock) dpm.addUserRestriction(a, adb) else dpm.clearUserRestriction(a, adb)
            // Разрешаем только наш сервис спец. возможностей? Нет — это отключило бы сервисы для людей с ОВЗ.
        }.onFailure { Log.w(TAG, "ensureLockPolicies", it) }
    }

    /** Полное снятие режима владельца (вызывается ТОЛЬКО после 10 подтверждений). */
    fun removeDeviceOwner(ctx: Context): Boolean {
        if (!isDeviceOwner(ctx)) return false
        val dpm = dpm(ctx); val a = admin(ctx)
        return runCatching {
            (ALWAYS + DURING_LOCK + UserManager.DISALLOW_DEBUGGING_FEATURES).forEach { dpm.clearUserRestriction(a, it) }
            dpm.setUninstallBlocked(a, ctx.packageName, false)
            if (Build.VERSION.SDK_INT >= 30) dpm.setUserControlDisabledPackages(a, emptyList())
            dpm.setLockTaskPackages(a, arrayOf())
            @Suppress("DEPRECATION") dpm.clearDeviceOwnerApp(ctx.packageName)
            Store.get(ctx).log(HistoryEntry.OWNER, "Режим владельца устройства снят (после 10 подтверждений)")
            true
        }.getOrElse { Log.w(TAG, "removeDeviceOwner", it); false }
    }

    /** Снять права администратора (чтобы можно было удалить приложение) — только после 10 подтверждений. */
    fun removeAdmin(ctx: Context) {
        if (isDeviceOwner(ctx)) removeDeviceOwner(ctx)
        runCatching { dpm(ctx).removeActiveAdmin(admin(ctx)) }
        Store.get(ctx).log(HistoryEntry.OWNER, "Права администратора сняты из приложения (после 10 подтверждений)")
    }

    fun addAdminIntent(ctx: Context) = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Права администратора не дают удалить «Стоп-экран» в момент слабости: сначала нужно снять их, а во время блокировки эта страница закрыта.")
}
