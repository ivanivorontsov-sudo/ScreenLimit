package com.example.screenlimit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

/** Что разрешено во время блокировки. */
object Allowed {
    private val CALL_SYSTEM = setOf(
        "com.android.server.telecom", "com.android.phone", "com.android.incallui",
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.samsung.android.incallui", "com.android.incallui.call.dialog", "com.samsung.android.app.telephonyui",
        "com.android.emergency", "com.google.android.apps.safetyhub", "com.android.stk",
        "com.android.contacts", "com.google.android.contacts", "com.samsung.android.app.contacts",
        "com.android.mms", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.cellbroadcastreceiver", "com.google.android.cellbroadcastreceiver",
        "com.android.cellbroadcastreceiver.module", "com.google.android.cellbroadcastreceiver.module",
        "com.miui.securitycenter.incallui", "com.huawei.contacts", "com.huawei.message",
    )
    private val SYSTEM_ESSENTIAL = setOf("android", "com.android.systemui", "com.android.keyguard",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller")

    fun dialer(ctx: Context): String? = runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
    fun sms(ctx: Context): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()
    fun ime(ctx: Context): String? = runCatching {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    }.getOrNull()

    /** Пакеты для Lock Task (Device Owner). */
    fun lockTaskPackages(ctx: Context, whitelist: Set<String>): Array<String> =
        (setOf(ctx.packageName) + CALL_SYSTEM + listOfNotNull(dialer(ctx), sms(ctx)) + whitelist)
            .filter { installed(ctx, it) }.toTypedArray()

    fun isAllowedDuringLock(ctx: Context, pkg: String?, whitelist: Set<String>): Boolean {
        if (pkg.isNullOrEmpty()) return true
        return pkg == ctx.packageName || pkg in CALL_SYSTEM || pkg in SYSTEM_ESSENTIAL ||
            pkg == dialer(ctx) || pkg == sms(ctx) || pkg == ime(ctx) || pkg in whitelist
    }

    /** Не засчитываются в экранное время: само приложение и звонки. */
    fun isExcludedFromCounting(ctx: Context, pkg: String?): Boolean {
        if (pkg == null) return false
        return pkg == ctx.packageName || pkg == dialer(ctx) || pkg in setOf(
            "com.android.server.telecom", "com.android.incallui", "com.samsung.android.incallui", "com.android.phone")
    }

    fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun dialIntent(number: String? = null) = Intent(Intent.ACTION_DIAL, if (number != null) Uri.parse("tel:$number") else null)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun smsIntent(ctx: Context): Intent {
        val p = sms(ctx)
        val launch = p?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
        return (launch ?: Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
