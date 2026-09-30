package com.example.screenlimit

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

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

    /**
     * Системные окна, которые НИКОГДА не считаются «чужим приложением»: шторка/навбар, клавиатура.
     * (Клавиатуры определяются динамически — см. [isIme].)
     */
    private val NEUTRAL = setOf("com.android.systemui", "com.android.keyguard")

    /**
     * «Помощники» — пакеты, которые разрешённое приложение законно открывает поверх себя:
     * браузер/Custom Tabs (3-D Secure, оплата), Google Play services (Google Pay, NFC, вход, согласие на SMS-код),
     * системный выбор приложения (СБП, «Поделиться»), контроллер разрешений, камера (QR), выбор документов/фото,
     * NFC-сервис. Они допускаются ТОЛЬКО в «окне помощников» — см. [Guard.helperWindowOpen].
     * Дополнительно в помощники динамически попадают: браузер по умолчанию, все обработчики https,
     * камера по умолчанию, обработчик выбора файлов.
     */
    private val HELPERS_STATIC = setOf(
        "android", "com.android.intentresolver", "com.google.android.intentresolver",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.android.chrome", "com.chrome.beta", "com.google.android.webview", "com.android.webview",
        "com.yandex.browser", "ru.yandex.searchplugin", "com.sec.android.app.sbrowser", "com.mi.globalbrowser",
        "com.android.browser", "org.mozilla.firefox", "com.opera.browser", "com.microsoft.emmx", "com.huawei.browser",
        "com.android.documentsui", "com.google.android.documentsui", "com.android.providers.media.module",
        "com.google.android.providers.media.module", "com.android.providers.downloads.ui",
        "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera",
        "com.huawei.camera", "com.oplus.camera", "com.oneplus.camera",
        "com.android.nfc", "com.google.android.apps.walletnfcrel", "com.samsung.android.spay",
        "com.google.android.apps.nbu.paisa.user",
        "com.android.htmlviewer", "com.google.android.apps.docs", "com.google.android.apps.photos",
        "com.android.gallery3d", "com.miui.gallery", "com.sec.android.gallery3d", "com.google.android.gallery3d",
    )

    fun dialer(ctx: Context): String? = runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
    fun sms(ctx: Context): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()
    fun ime(ctx: Context): String? = runCatching {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    }.getOrNull()

    // ---- Клавиатуры: текущая + все включённые + все установленные IME-сервисы (в т.ч. «безопасные клавиатуры» OEM) ----
    @Volatile private var imeCache: Set<String> = emptySet()
    @Volatile private var imeCacheAt = 0L

    fun imePackages(ctx: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - imeCacheAt < 30_000 && imeCache.isNotEmpty()) return imeCache
        val out = HashSet<String>()
        ime(ctx)?.let { out += it }
        runCatching { ctx.getSystemService(InputMethodManager::class.java).enabledInputMethodList.forEach { out += it.packageName } }
        runCatching { ctx.getSystemService(InputMethodManager::class.java).inputMethodList.forEach { out += it.packageName } }
        runCatching {
            ctx.packageManager.queryIntentServices(Intent("android.view.InputMethod"), PackageManager.GET_META_DATA)
                .forEach { out += it.serviceInfo.packageName }
        }
        // «Безопасные клавиатуры» OEM для полей ввода паролей.
        out += setOf("com.miui.securityinputmethod", "com.huawei.secime", "com.oplus.securitykeyboard", "com.coloros.securekeyboard",
            "com.samsung.android.honeyboard", "com.google.android.inputmethod.latin", "com.touchtype.swiftkey", "ru.yandex.androidkeyboard")
        imeCache = out; imeCacheAt = now
        return out
    }

    fun isIme(ctx: Context, pkg: String?) = pkg != null && pkg in imePackages(ctx)

    /** Окна, которые не меняют «переднее приложение»: системный UI и любые клавиатуры. */
    fun isNeutral(ctx: Context, pkg: String?) = pkg != null && (pkg in NEUTRAL || isIme(ctx, pkg))

    // ---- Помощники ----
    @Volatile private var helperCache: Set<String> = emptySet()
    @Volatile private var helperCacheAt = 0L

    fun helperPackages(ctx: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - helperCacheAt < 60_000 && helperCache.isNotEmpty()) return helperCache
        val pm = ctx.packageManager
        val out = HashSet(HELPERS_STATIC)
        fun resolveAll(i: Intent) = runCatching { pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).forEach { out += it.activityInfo.packageName } }
        resolveAll(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE))
        resolveAll(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        resolveAll(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE))   // системный выбор файлов
        // Сервис Custom Tabs
        runCatching { pm.queryIntentServices(Intent("android.support.customtabs.action.CustomTabsService"), 0).forEach { out += it.serviceInfo.packageName } }
        out -= ctx.packageName
        helperCache = out; helperCacheAt = now
        return out
    }

    fun isHelper(ctx: Context, pkg: String?) = pkg != null && pkg in helperPackages(ctx)

    /** Базово разрешённые (без списка пользователя): звонки, SMS, контакты, само приложение, системное. */
    fun isBaseAllowed(ctx: Context, pkg: String): Boolean =
        pkg == ctx.packageName || pkg in CALL_SYSTEM || pkg in SYSTEM_ESSENTIAL || pkg == dialer(ctx) || pkg == sms(ctx) || isNeutral(ctx, pkg)

    /** Пакеты для Lock Task (Device Owner): наши, звонки/SMS, список пользователя и помощники (их запуск возможен только из разрешённых). */
    @Volatile private var ltCache: Pair<Set<String>, Array<String>>? = null
    @Volatile private var ltCacheAt = 0L
    fun lockTaskPackages(ctx: Context, whitelist: Set<String>): Array<String> {
        val now = SystemClock.elapsedRealtime()
        ltCache?.let { (wl, arr) -> if (wl == whitelist && now - ltCacheAt < 60_000) return arr }
        val arr = (setOf(ctx.packageName) + CALL_SYSTEM + listOfNotNull(dialer(ctx), sms(ctx)) + whitelist + helperPackages(ctx) - "android")
            .filter { installed(ctx, it) }.toTypedArray()
        ltCache = whitelist to arr; ltCacheAt = now
        return arr
    }

    /**
     * Можно ли этому пакету быть на переднем плане во время блокировки прямо сейчас.
     * Помощники допускаются только в окне после разрешённого приложения ([Guard.helperWindowOpen]).
     */
    fun isAllowedDuringLock(ctx: Context, pkg: String?, whitelist: Set<String>): Boolean {
        if (pkg.isNullOrEmpty()) return true
        if (pkg in whitelist || isBaseAllowed(ctx, pkg)) return true
        return isHelper(ctx, pkg) && Guard.helperWindowOpen()
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

    /** Интент запуска приложения: getLaunchIntentForPackage, затем явный поиск LAUNCHER-активности, затем Leanback. */
    fun launchIntent(ctx: Context, pkg: String): Intent? {
        val pm = ctx.packageManager
        val i = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
            ?: runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0)
                    .firstOrNull()?.activityInfo?.let { Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(it.packageName, it.name) }
            }.getOrNull()
            ?: runCatching { pm.getLeanbackLaunchIntentForPackage(pkg) }.getOrNull()
        return i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    /** Все приложения с иконкой в лаунчере (нужен QUERY_ALL_PACKAGES / <queries> на Android 11+). */
    fun launchablePackages(ctx: Context): List<String> =
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.distinct().filter { it != ctx.packageName }

    fun dialIntent(number: String? = null) = Intent(Intent.ACTION_DIAL, if (number != null) Uri.parse("tel:$number") else null)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun smsIntent(ctx: Context): Intent {
        val p = sms(ctx)
        val launch = p?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
        return (launch ?: Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
