package com.example.screenlimit

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.screenlimit.core.AppUsageCalc
import com.example.screenlimit.core.Backup
import com.example.screenlimit.core.Engine
import com.example.screenlimit.core.HistoryEntry
import com.example.screenlimit.core.Settings
import com.example.screenlimit.core.SettingsPolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable { override fun run() { render(); h.postDelayed(this, 10_000) } }
    private val listener: () -> Unit = { h.post { if (!isFinishing) render() } }
    private var lastSig = ""

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        if (Store.get(this).state.locked) return@registerForActivityResult
        runCatching {
            val s = Store.get(this)
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.export(s.settings, s.history(), System.currentTimeMillis()).toByteArray()) }
        }.onSuccess { toast("Сохранено") }.onFailure { toast("Ошибка: ${it.message}") }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
            .onSuccess { importText(it) }.onFailure { toast("Не удалось прочитать файл: ${it.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        if (!Store.get(this).onboardingDone) { startActivity(Intent(this, OnboardingActivity::class.java)) }
        GuardService.start(this)
    }

    override fun onResume() {
        super.onResume()
        lastSig = ""
        Guard.addListener(listener)
        runCatching { Guard.tick(this) }
        if (Store.get(this).state.locked) { Guard.showBlock(this) }
        h.post(refresh)
    }

    override fun onPause() { super.onPause(); Guard.removeListener(listener); h.removeCallbacks(refresh) }

    private fun render(force: Boolean = false) {
        val store = Store.get(this)
        val st = store.state; val s = store.settings; val cfg = store.engineConfig()
        // Перерисовываем только при видимых изменениях — иначе сбивается прокрутка и открытые диалоги.
        val sig = listOf(st.usedMs / 60_000, st.locked, st.unlockedRestOfPeriod, st.bonusMs, s, store.history().size, Perms.allRequired(this), Owner.isDeviceOwner(this)).joinToString()
        if (!force && sig == lastSig) return
        lastSig = sig
        val locked = st.locked
        val limit = Engine.limitMs(s, cfg) + st.bonusMs
        val rem = Engine.remainingMs(s, st, cfg).coerceAtLeast(0)
        val y = (window.decorView.findViewById<android.widget.ScrollView?>(R.id.mainScroll))?.scrollY ?: 0
        scrollPage {
            card {
                text("Сегодня за экраном", 14f)
                text(Guard.fmtDur(st.usedMs), 40f, bold = true, topDp = 0).id = R.id.usedToday
                val status = when {
                    locked -> "🔒 Телефон заблокирован"
                    !s.limitEnabled -> "Дневной лимит выключен"
                    st.unlockedRestOfPeriod -> "Лимит снят до конца суток"
                    else -> "Осталось: ${Guard.fmtDur(rem)} из ${Guard.fmtDur(limit)}"
                }
                text(status, 17f, bold = true).id = R.id.remaining
                if (s.limitEnabled) addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = if (limit > 0) ((st.usedMs * 1000) / limit).toInt().coerceIn(0, 1000) else 1000
                }, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
                if (s.scheduleEnabled) text("Расписание: блокировка %02d:%02d–%02d:%02d".format(s.scheduleStartMin / 60, s.scheduleStartMin % 60, s.scheduleEndMin / 60, s.scheduleEndMin % 60), 14f, topDp = 8)
                if (!Perms.allRequired(this@MainActivity)) {
                    text("⚠ Не все разрешения выданы — защита работает не полностью.", 14f, color = getColor(R.color.danger), topDp = 8)
                    button("Настроить разрешения", style = 1) { startActivity(Intent(this@MainActivity, OnboardingActivity::class.java)) }
                }
            }

            section("НАСТРОЙКИ")
            if (locked) text("Во время блокировки настройки менять нельзя.", 14f)
            card {
                row {
                    text("Дневной лимит", 16f, bold = true).weight()
                    addView(MaterialSwitch(context).apply { isChecked = s.limitEnabled; isEnabled = !locked; setOnCheckedChangeListener { _, v -> propose(s.copy(limitEnabled = v)) } })
                }
                button("Лимит: ${Guard.fmtDur(s.limitMinutes * 60_000L)}", style = 1) { pickLimit(s) }.apply { isEnabled = !locked && s.limitEnabled; id = R.id.btnLimit }
                button("Сброс счётчика в %02d:%02d".format(s.resetMinuteOfDay / 60, s.resetMinuteOfDay % 60), style = 2) {
                    pickTime("Время сброса счётчика", s.resetMinuteOfDay) { propose(s.copy(resetMinuteOfDay = it)) }
                }.isEnabled = !locked
                row {
                    text("Звонки-предупреждения за 30/20/10 мин", 15f).weight()
                    addView(MaterialSwitch(context).apply { isChecked = s.warningsEnabled; isEnabled = !locked; setOnCheckedChangeListener { _, v -> propose(s.copy(warningsEnabled = v)) } })
                }
                row {
                    text("Финальный звонок в момент блокировки", 15f).weight()
                    addView(MaterialSwitch(context).apply { isChecked = s.finalWarning; isEnabled = !locked; setOnCheckedChangeListener { _, v -> propose(s.copy(finalWarning = v)) } })
                }
            }
            card {
                row {
                    text("Блокировка по расписанию", 16f, bold = true).weight()
                    addView(MaterialSwitch(context).apply { isChecked = s.scheduleEnabled; isEnabled = !locked; setOnCheckedChangeListener { _, v -> propose(s.copy(scheduleEnabled = v)) } })
                }
                row {
                    button("С %02d:%02d".format(s.scheduleStartMin / 60, s.scheduleStartMin % 60), style = 1) {
                        pickTime("Начало блокировки", s.scheduleStartMin) { propose(s.copy(scheduleStartMin = it)) }
                    }.apply { isEnabled = !locked }.weight()
                    button("До %02d:%02d".format(s.scheduleEndMin / 60, s.scheduleEndMin % 60), style = 1) {
                        pickTime("Конец блокировки", s.scheduleEndMin) { propose(s.copy(scheduleEndMin = it)) }
                    }.apply { isEnabled = !locked }.weight()
                }
            }
            card {
                row {
                    text("Строгий режим", 16f, bold = true).weight()
                    addView(MaterialSwitch(context).apply { isChecked = s.strictMode; isEnabled = !locked; setOnCheckedChangeListener { _, v -> propose(s.copy(strictMode = v)) } })
                }
                text("Всегда (а не только во время блокировки) закрывает страницы настроек, через которые можно отключить или удалить «Стоп-экран». На разрешённые приложения не влияет.", 13f)
                button("Разрешённые приложения (${s.whitelist.size})", style = 1) { startActivity(Intent(this@MainActivity, WhitelistActivity::class.java)) }.isEnabled = !locked
                val maxOn = Owner.maxProtection(this@MainActivity)
                text(if (maxOn) "🛡 Максимальная защита активна: владелец устройства, режим киоска во время паузы."
                    else "Максимальная защита не активна: «Стоп-экран» не владелец устройства. Работают спец. возможности" +
                        (if (Owner.isAdmin(this@MainActivity)) " и администратор устройства." else ".") , 13f, topDp = 8).id = R.id.maxStatus
                button(if (maxOn) "Максимальная защита: включена" else "Максимальная защита: выключена (как включить)", style = 1) { startActivity(Intent(this@MainActivity, ProtectionActivity::class.java)) }.isEnabled = !locked
            }

            section("ПО ПРИЛОЖЕНИЯМ СЕГОДНЯ")
            card { appBreakdown(this, st.periodStart) }

            section("ИСТОРИЯ")
            card {
                val hist = store.history().filter { it.type in setOf(HistoryEntry.EXIT, HistoryEntry.LOCK, HistoryEntry.TAMPER, HistoryEntry.OWNER, HistoryEntry.SETTINGS, HistoryEntry.IMPORT) }
                val exits = hist.count { it.type == HistoryEntry.EXIT }
                text("Выходов из блокировки всего: $exits", 15f, bold = true)
                if (hist.isEmpty()) text("Пока пусто", 14f)
                val fmt = SimpleDateFormat("dd.MM HH:mm", Locale("ru"))
                hist.take(40).forEach { e ->
                    val icon = when (e.type) { HistoryEntry.EXIT -> "🔓"; HistoryEntry.LOCK -> "🔒"; HistoryEntry.TAMPER -> "⚠"; HistoryEntry.OWNER -> "🛡"; else -> "•" }
                    text("$icon ${fmt.format(Date(e.ts))} — ${e.detail}", 13f, topDp = 6)
                }
            }

            section("РЕЗЕРВНАЯ КОПИЯ")
            card {
                text("Экспорт и импорт настроек и истории в JSON. Доступно только вне блокировки. Импорт ничего не удаляет: история объединяется, а более мягкие настройки требуют 10 подтверждений.", 13f)
                row {
                    button("Экспорт", style = 1) { exportLauncher.launch("stop-ekran-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json") }.apply { isEnabled = !locked }.weight()
                    button("Импорт", style = 1) { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }.apply { isEnabled = !locked }.weight()
                }
            }
            text("Версия ${BuildConfig.VERSION_NAME}", 12f, topDp = 16)
        }.also { col ->
            (col.parent as android.widget.ScrollView).apply { id = R.id.mainScroll; post { scrollTo(0, y) } }
        }
    }

    private fun appBreakdown(col: LinearLayout, since: Long) {
        if (!Perms.usage(this)) { col.text("Нужен доступ к истории использования.", 14f); return }
        val end = System.currentTimeMillis()
        val begin = if (since in 1 until end) since else end - 24 * 3_600_000L
        val list = runCatching {
            val usm = getSystemService(UsageStatsManager::class.java)
            val ev = usm.queryEvents(begin, end); val e = UsageEvents.Event(); val out = ArrayList<AppUsageCalc.Ev>()
            while (ev.hasNextEvent()) { ev.getNextEvent(e)
                val t = when (e.eventType) { UsageEvents.Event.MOVE_TO_FOREGROUND -> AppUsageCalc.RESUMED; UsageEvents.Event.MOVE_TO_BACKGROUND -> AppUsageCalc.PAUSED; 16 -> AppUsageCalc.SCREEN_OFF; else -> 0 }
                if (t != 0) out += AppUsageCalc.Ev(e.timeStamp, e.packageName ?: "", t)
            }
            AppUsageCalc.compute(out, begin, end)
        }.getOrDefault(emptyMap())
            .filterKeys { it != packageName && it != "com.android.systemui" }
            .filter { it.value >= 30_000 }
            .entries.sortedByDescending { it.value }.take(10)
        if (list.isEmpty()) { col.text("Нет данных за сегодня", 14f); return }
        val max = list.first().value.toFloat()
        list.forEach { (pkg, ms) ->
            col.row {
                text(Allowed.label(this@MainActivity, pkg), 14f).weight(0.6f)
                text(Guard.fmtDur(ms), 14f, bold = true, gravity = android.view.Gravity.END).weight(0.4f)
            }
            col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { this.max = 1000; progress = (ms / max * 1000).toInt() },
                LinearLayout.LayoutParams(-1, dp(4)))
        }
    }

    /** Любое изменение: ужесточение — сразу; ослабление — через 10 подтверждений; во время блокировки — нельзя. */
    fun propose(new: Settings) {
        val store = Store.get(this)
        if (store.state.locked) { toast("Во время блокировки менять настройки нельзя"); render(true); return }
        val old = store.settings
        if (new == old) return
        val why = SettingsPolicy.loosenings(old, new)
        if (why.isEmpty()) {
            store.settings = new
            store.log(HistoryEntry.SETTINGS, "Настройки изменены")
            Owner.ensureLockPolicies(this, store.state.locked)
            Guard.tick(this, "settings"); render(true)
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Это ослабление ограничений")
                .setMessage("Изменение (${why.joinToString(", ")}) делает ограничения мягче. Чтобы применить его, нужно пройти 10 подтверждений.")
                .setPositiveButton("Пройти 10 подтверждений") { _, _ ->
                    store.pendingSettings = new
                    startActivity(Intent(this, ExitActivity::class.java).putExtra(ExitActivity.EXTRA_PURPOSE, ExitActivity.P_SETTINGS))
                }
                .setNegativeButton("Отмена") { _, _ -> render(true) }
                .setOnCancelListener { render(true) }
                .show()
        }
    }

    private fun pickLimit(s: Settings) {
        val opts = listOf(15, 30, 45, 60, 90, 120, 150, 180, 240, 300, 360)
        val labels = opts.map { Guard.fmtDur(it * 60_000L) }.toMutableList().apply { add("Другое…") }
        MaterialAlertDialogBuilder(this).setTitle("Дневной лимит")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < opts.size) propose(s.copy(limitMinutes = opts[i]))
                else pickTime("Лимит (часы:минуты)", s.limitMinutes.coerceAtMost(23 * 60 + 59)) { propose(s.copy(limitMinutes = it.coerceAtLeast(1))) }
            }.show()
    }

    private fun pickTime(title: String, minute: Int, cb: (Int) -> Unit) {
        val p = MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H).setHour(minute / 60).setMinute(minute % 60).setTitleText(title).build()
        p.addOnPositiveButtonClickListener { cb(p.hour * 60 + p.minute) }
        p.show(supportFragmentManager, "time")
    }

    private fun importText(text: String) {
        val store = Store.get(this)
        if (store.state.locked) { toast("Во время блокировки импорт недоступен"); return }
        val parsed = runCatching { Backup.parse(text, store.settings) }.getOrElse { toast(it.message ?: "Ошибка формата"); return }
        store.setHistory(HistoryEntry.merge(store.history(), parsed.history))
        store.log(HistoryEntry.IMPORT, "Импорт резервной копии: ${parsed.history.size} записей истории")
        parsed.settings?.let { propose(it) }
        toast("Импорт выполнен"); render(true)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
