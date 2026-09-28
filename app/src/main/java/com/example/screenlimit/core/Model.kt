package com.example.screenlimit.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Пользовательские настройки. Ключи JSON — стабильные (sl_*), их нельзя переименовывать:
 * см. README «Обновления и данные». Новые поля добавляются только с дефолтами.
 */
data class Settings(
    val limitEnabled: Boolean = true,
    val limitMinutes: Int = 120,
    val resetMinuteOfDay: Int = 0,
    val scheduleEnabled: Boolean = false,
    val scheduleStartMin: Int = 23 * 60,
    val scheduleEndMin: Int = 7 * 60,
    val warningsEnabled: Boolean = true,
    val finalWarning: Boolean = true,
    val strictMode: Boolean = false,
    val whitelist: Set<String> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("limitEnabled", limitEnabled)
        put("limitMinutes", limitMinutes)
        put("resetMinuteOfDay", resetMinuteOfDay)
        put("scheduleEnabled", scheduleEnabled)
        put("scheduleStartMin", scheduleStartMin)
        put("scheduleEndMin", scheduleEndMin)
        put("warningsEnabled", warningsEnabled)
        put("finalWarning", finalWarning)
        put("strictMode", strictMode)
        put("whitelist", JSONArray(whitelist.sorted()))
    }

    companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 24 * 60

        /** Неизвестные/отсутствующие поля получают значения по умолчанию — старые экспорты читаются. */
        fun fromJson(o: JSONObject, base: Settings = Settings()): Settings {
            val wl = o.optJSONArray("whitelist")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            return Settings(
                limitEnabled = o.optBoolean("limitEnabled", base.limitEnabled),
                limitMinutes = o.optInt("limitMinutes", base.limitMinutes).coerceIn(MIN_LIMIT, MAX_LIMIT),
                resetMinuteOfDay = o.optInt("resetMinuteOfDay", base.resetMinuteOfDay).coerceIn(0, 24 * 60 - 1),
                scheduleEnabled = o.optBoolean("scheduleEnabled", base.scheduleEnabled),
                scheduleStartMin = o.optInt("scheduleStartMin", base.scheduleStartMin).coerceIn(0, 24 * 60 - 1),
                scheduleEndMin = o.optInt("scheduleEndMin", base.scheduleEndMin).coerceIn(0, 24 * 60 - 1),
                warningsEnabled = o.optBoolean("warningsEnabled", base.warningsEnabled),
                finalWarning = o.optBoolean("finalWarning", base.finalWarning),
                strictMode = o.optBoolean("strictMode", base.strictMode),
                whitelist = wl ?: base.whitelist,
            )
        }
    }
}

/** Внутреннее состояние учёта (не экспортируется). */
data class GuardState(
    // TrustedClock
    val anchorWall: Long = 0,
    val anchorElapsed: Long = 0,
    val lastTrusted: Long = 0,
    // Период (сутки) в «доверенном» времени
    val periodStart: Long = 0,
    val periodEnd: Long = 0,
    val usedMs: Long = 0,
    val lastTickElapsed: Long = 0,
    val bonusMs: Long = 0,
    val unlockedRestOfPeriod: Boolean = false,
    val firedWarnings: Set<Int> = emptySet(),
    val scheduleOverrideUntil: Long = 0,
    val scheduleFired: Set<String> = emptySet(),
    val locked: Boolean = false,
    val lockReason: String = "",
    val lockStartedAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("anchorWall", anchorWall); put("anchorElapsed", anchorElapsed); put("lastTrusted", lastTrusted)
        put("periodStart", periodStart); put("periodEnd", periodEnd); put("usedMs", usedMs)
        put("lastTickElapsed", lastTickElapsed); put("bonusMs", bonusMs)
        put("unlockedRestOfPeriod", unlockedRestOfPeriod)
        put("firedWarnings", JSONArray(firedWarnings.sorted()))
        put("scheduleOverrideUntil", scheduleOverrideUntil)
        put("scheduleFired", JSONArray(scheduleFired.sorted()))
        put("locked", locked); put("lockReason", lockReason); put("lockStartedAt", lockStartedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): GuardState = GuardState(
            anchorWall = o.optLong("anchorWall"), anchorElapsed = o.optLong("anchorElapsed"),
            lastTrusted = o.optLong("lastTrusted"),
            periodStart = o.optLong("periodStart"), periodEnd = o.optLong("periodEnd"),
            usedMs = o.optLong("usedMs"), lastTickElapsed = o.optLong("lastTickElapsed"),
            bonusMs = o.optLong("bonusMs"), unlockedRestOfPeriod = o.optBoolean("unlockedRestOfPeriod"),
            firedWarnings = o.optJSONArray("firedWarnings")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
            scheduleOverrideUntil = o.optLong("scheduleOverrideUntil"),
            scheduleFired = o.optJSONArray("scheduleFired")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
            locked = o.optBoolean("locked"), lockReason = o.optString("lockReason", ""),
            lockStartedAt = o.optLong("lockStartedAt"),
        )
    }
}

data class HistoryEntry(val ts: Long, val type: String, val detail: String) {
    fun toJson(): JSONObject = JSONObject().put("ts", ts).put("type", type).put("detail", detail)
    val key get() = "$ts|$type|$detail"

    companion object {
        const val EXIT = "exit"
        const val LOCK = "lock"
        const val SETTINGS = "settings"
        const val TAMPER = "tamper"
        const val OWNER = "owner"
        const val IMPORT = "import"
        const val RESET = "reset"

        fun fromJson(o: JSONObject) = HistoryEntry(o.optLong("ts"), o.optString("type"), o.optString("detail"))

        /** Аддитивное слияние истории: ничего не теряется, дубликаты отбрасываются. */
        fun merge(a: List<HistoryEntry>, b: List<HistoryEntry>): List<HistoryEntry> {
            val seen = HashSet<String>()
            return (a + b).filter { seen.add(it.key) }.sortedByDescending { it.ts }
        }
    }
}

object Backup {
    const val FORMAT = "screenlimit-backup"
    const val VERSION = 1

    fun export(settings: Settings, history: List<HistoryEntry>, exportedAt: Long): String =
        JSONObject().apply {
            put("format", FORMAT); put("version", VERSION); put("exportedAt", exportedAt)
            put("settings", settings.toJson())
            put("history", JSONArray().apply { history.forEach { put(it.toJson()) } })
        }.toString(2)

    data class Parsed(val settings: Settings?, val history: List<HistoryEntry>)

    fun parse(text: String, current: Settings): Parsed {
        val o = JSONObject(text)
        require(o.optString("format") == FORMAT) { "Это не файл резервной копии «Стоп-экран»" }
        val s = o.optJSONObject("settings")?.let { Settings.fromJson(it, current) }
        val h = o.optJSONArray("history")?.let { a -> (0 until a.length()).map { HistoryEntry.fromJson(a.getJSONObject(it)) } } ?: emptyList()
        return Parsed(s, h)
    }
}
