package com.example.screenlimit

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Сервис спец. возможностей: мгновенно узнаёт приложение на переднем плане.
 * Во время блокировки всё, кроме разрешённого (звонки, SMS, это приложение, системное), —
 * возвращается на экран блокировки. Во время блокировки и в строгом режиме закрываются
 * страницы Настроек, через которые можно снять защиту (сведения о приложении, спец. возможности,
 * администраторы, удаление, «Для разработчиков»).
 */
class BlockerService : AccessibilityService() {
    private var lastBounce = 0L
    private var lastScan = 0L

    private val listener: () -> Unit = {
        android.os.Handler(android.os.Looper.getMainLooper()).post { if (!Store.get(this).state.locked) hideOverlay() }
    }

    override fun onServiceConnected() {
        running = true
        Guard.addListener(listener)
        GuardService.start(this)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val pkg = e.packageName?.toString() ?: return
        val type = e.eventType
        val isState = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (isState && pkg != Allowed.ime(this) && pkg != "com.android.systemui") {
            Guard.lastForeground = pkg; Guard.lastForegroundAt = System.currentTimeMillis()
        }
        val store = Store.get(this)
        val locked = store.state.locked
        val s = store.settings

        if (isState && pkg != "com.android.systemui" && pkg != Allowed.ime(this)) {
            if (locked && !Allowed.isAllowedDuringLock(this, pkg, s.whitelist)) { showOverlay(); bounce(false); return }
            if (pkg == packageName || !locked || Allowed.isAllowedDuringLock(this, pkg, s.whitelist)) hideOverlay()
        }
        // Защитные страницы: во время блокировки (Настройки и так запрещены, но whitelist мог их включить)
        // и в строгом режиме — всегда.
        if ((locked || s.strictMode) && pkg in GUARDED_PKGS && !grantWindowOpen()) {
            val now = SystemClock.uptimeMillis()
            if (!isState && now - lastScan < 350) return
            lastScan = now
            val root = rootInActiveWindow ?: return
            if (isSensitive(root)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                bounce(true)
            }
        }
    }

    private fun isSensitive(root: AccessibilityNodeInfo): Boolean {
        val label = getString(R.string.app_name)
        var hit = false
        var dev = false
        walk(root, 0) { t ->
            if (t.contains(label, ignoreCase = true)) hit = true
            if (DEV_TITLES.any { t.equals(it, ignoreCase = true) }) dev = true
            hit || dev
        }
        return hit || dev
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int, f: (String) -> Boolean): Boolean {
        if (n == null || depth > 30) return false
        val t = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
        if (t.isNotBlank() && f(t.trim())) return true
        for (i in 0 until n.childCount) if (walk(n.getChild(i), depth + 1, f)) return true
        return false
    }

    private fun bounce(tamper: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBounce < 400) return
        lastBounce = now
        if (tamper) Store.get(this).log(com.example.screenlimit.core.HistoryEntry.TAMPER, "Попытка открыть защищённую страницу настроек")
        if (Store.get(this).state.locked) Guard.showBlock(this)
        else performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // --- Мгновенная заглушка поверх запрещённого приложения (TYPE_ACCESSIBILITY_OVERLAY) ---
    private var overlay: android.view.View? = null

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(android.view.WindowManager::class.java)
        val v = android.view.LayoutInflater.from(this).inflate(R.layout.overlay_block, null)
        v.findViewById<android.view.View>(R.id.ovOpen).setOnClickListener { Guard.showBlock(this) }
        v.findViewById<android.view.View>(R.id.ovCall).setOnClickListener { hideOverlay(); runCatching { startActivity(Allowed.dialIntent()) } }
        v.findViewById<android.view.View>(R.id.ovSms).setOnClickListener { hideOverlay(); runCatching { startActivity(Allowed.smsIntent(this)) } }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.OPAQUE)
        runCatching { wm.addView(v, lp); overlay = v }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        overlay = null
        runCatching { getSystemService(android.view.WindowManager::class.java).removeView(v) }
    }

    override fun onInterrupt() {}

    override fun onDestroy() { running = false; Guard.removeListener(listener); hideOverlay(); super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { running = false; return super.onUnbind(intent) }

    companion object {
        @Volatile var running = false
        /** До этого момента (uptime) защитные страницы не закрываются — пользователь выдаёт недостающее разрешение из онбординга. */
        @Volatile var grantUntil = 0L
        fun grantWindowOpen() = SystemClock.uptimeMillis() < grantUntil

        val GUARDED_PKGS = setOf(
            "com.android.settings", "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.samsung.android.packageinstaller", "com.miui.securitycenter", "com.miui.packageinstaller",
            "com.huawei.systemmanager", "com.coloros.safecenter", "com.oplus.safecenter", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.samsung.accessibility", "com.google.android.accessibility.suite",
        )
        val DEV_TITLES = listOf("Для разработчиков", "Параметры разработчика", "Developer options", "Режим разработчика")
    }
}
