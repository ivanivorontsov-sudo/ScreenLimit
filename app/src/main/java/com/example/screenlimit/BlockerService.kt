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
        // Служба спец. возможностей поднимается системой раньше BOOT_COMPLETED — восстанавливаем блокировку сразу.
        // 1.0.1: не выбрасывать из разрешённого приложения, если служба просто переподключилась (перезапуск процесса и т.п.).
        if (Store.get(this).state.locked) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val store = Store.get(this)
            if (store.state.locked) {
                val fg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
                if (fg == null || fg == packageName || !Allowed.isAllowedDuringLock(this, fg, store.settings.whitelist) || Allowed.isNeutral(this, fg)) Guard.showBlock(this)
            }
        }, 1_500)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val pkg = e.packageName?.toString() ?: return
        val type = e.eventType
        val isState = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val store = Store.get(this)
        val locked = store.state.locked
        val s = store.settings

        if (isState) {
            // 1.0.1: клавиатуры (любые установленные IME, включая «безопасные» OEM), systemui и наше собственное
            // окно-заглушка НИКОГДА не считаются чужим приложением и не меняют «переднее приложение».
            val neutral = Allowed.isNeutral(this, pkg) || isNeutralWindow(e.windowId)
            if (BuildConfig.DEBUG) android.util.Log.d("SLA11y", "state pkg=$pkg cls=${e.className} neutral=$neutral locked=$locked " +
                "exitAlive=${Guard.exitFlowAlive()} helperWin=${Guard.helperWindowOpen()} primary=${Guard.lastPrimary} win=${e.windowId}")
            if (!neutral) {
                Guard.lastForeground = pkg; Guard.lastForegroundAt = System.currentTimeMillis()
                Guard.noteForeground(this, pkg, s.whitelist)
                if (locked && !Allowed.isAllowedDuringLock(this, pkg, s.whitelist)) {
                    // Экран подтверждений всё ещё resumed → это не смена приложения, а окно поверх (клавиатура
                    // без сведений об окне, всплывающее окно). Не перекрывать экран подтверждений заглушкой.
                    if (ExitActivity.resumed) { if (BuildConfig.DEBUG) android.util.Log.d("SLA11y", "ignore $pkg: exit flow resumed"); return }
                    // 1.1.0: our own pause/password screens are already on top (e.g. the launcher/recents window
                    // briefly reported during the home gesture) — nothing to do, no second popup.
                    if (pauseScreenResumed()) { if (BuildConfig.DEBUG) android.util.Log.d("SLA11y", "ignore $pkg: pause screen resumed"); return }
                    if (BuildConfig.DEBUG) android.util.Log.d("SLA11y", "BOUNCE $pkg")
                    showOverlay(); bounce(false); return
                }
                hideOverlay()
            }
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

    /** Окно события — клавиатура или наша заглушка (нужен flagRetrieveInteractiveWindows). */
    private fun isNeutralWindow(windowId: Int): Boolean {
        if (windowId < 0) return false
        return runCatching {
            windows.firstOrNull { it.id == windowId }?.type.let {
                it == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    it == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            }
        }.getOrDefault(false)
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

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val reveal = Runnable { overlay?.findViewById<android.view.View>(R.id.ovContent)?.visibility = android.view.View.VISIBLE }

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(android.view.WindowManager::class.java)
        val v = android.view.LayoutInflater.from(this).inflate(R.layout.overlay_block, null)
        // 1.0.2 fix: кнопка «Открыть экран блокировки» раньше вызывала только showBlock, но НЕ убирала
        // саму заглушку. Экран блокировки уже лежит под ней (запущен при показе заглушки), поэтому новое
        // окно-состояние не менялось и слушатель спец.возможностей не вызывал hideOverlay — непрозрачная
        // заглушка оставалась сверху и казалось, что кнопка «не работает» (а «Позвонить»/«SMS» срабатывали,
        // потому что они сами звали hideOverlay). Теперь кнопка тоже снимает заглушку сразу.
        v.findViewById<android.view.View>(R.id.ovOpen).setOnClickListener { hideOverlay(); Guard.showBlock(this) }
        v.findViewById<android.view.View>(R.id.ovCall).setOnClickListener { hideOverlay(); runCatching { startActivity(Allowed.dialIntent()) } }
        v.findViewById<android.view.View>(R.id.ovSms).setOnClickListener { hideOverlay(); runCatching { startActivity(Allowed.smsIntent(this)) } }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.OPAQUE)
        runCatching { wm.addView(v, lp); overlay = v }
        // 1.1.0: the overlay is a silent cover (no text, no buttons) that hides the launcher/blocked app for the
        // fraction of a second until the pause screen comes up, and is removed by the pause screen itself
        // (BlockActivity/ExitActivity/PasswordActivity.onResume -> coverDone). Before 1.1.0 it was a second
        // visible "popup" that stayed on top of the pause screen and needed an extra tap.
        ui.removeCallbacks(reveal); ui.postDelayed(reveal, 2_500)
    }

    private fun hideOverlay() {
        ui.removeCallbacks(reveal)
        val v = overlay ?: return
        overlay = null
        runCatching { getSystemService(android.view.WindowManager::class.java).removeView(v) }
    }

    override fun onInterrupt() {}

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onDestroy() { instance = null; running = false; Guard.removeListener(listener); hideOverlay(); super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { running = false; return super.onUnbind(intent) }

    companion object {
        @Volatile var running = false
        @Volatile private var instance: BlockerService? = null
        /** Our pause screen (or the exit/password flow on top of it) is resumed right now. */
        @Volatile var pauseResumed = false
        fun pauseScreenResumed() = pauseResumed || ExitActivity.resumed
        /** Called by the pause screens when they are on screen: drop the silent cover. */
        fun coverDone() { android.os.Handler(android.os.Looper.getMainLooper()).post { instance?.hideOverlay() } }
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
