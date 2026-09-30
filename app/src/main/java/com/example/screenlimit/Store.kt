package com.example.screenlimit

import android.content.Context
import android.content.SharedPreferences
import com.example.screenlimit.core.EngineConfig
import com.example.screenlimit.core.GuardState
import com.example.screenlimit.core.HistoryEntry
import com.example.screenlimit.core.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Единственное хранилище приложения. ПРАВИЛА (см. README «Обновления и данные»):
 *  - имена файла и ключей стабильны и никогда не переименовываются/не удаляются;
 *  - схема только расширяется (новые поля с дефолтами), миграции только аддитивные;
 *  - «сиды» (значения по умолчанию) пишутся только если ключа ещё нет.
 */
class Store private constructor(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init { migrate() }

    private fun migrate() {
        val v = p.getInt(K_SCHEMA, 0)
        val e = p.edit()
        if (v < 1) {
            if (!p.contains(K_SETTINGS)) e.putString(K_SETTINGS, Settings().toJson().toString())
            if (!p.contains(K_HISTORY)) e.putString(K_HISTORY, "[]")
            if (!p.contains(K_INSTALLED_AT)) e.putLong(K_INSTALLED_AT, System.currentTimeMillis())
        }
        // Будущие версии: if (v < 2) { ...только добавление ключей... }
        if (v < SCHEMA) e.putInt(K_SCHEMA, SCHEMA)
        e.apply()
    }

    @get:Synchronized @set:Synchronized
    var settings: Settings
        get() = runCatching { Settings.fromJson(JSONObject(p.getString(K_SETTINGS, "{}")!!)) }.getOrDefault(Settings())
        set(v) { p.edit().putString(K_SETTINGS, v.toJson().toString()).commit() }

    @get:Synchronized @set:Synchronized
    var state: GuardState
        get() = runCatching { GuardState.fromJson(JSONObject(p.getString(K_STATE, "{}")!!)) }.getOrDefault(GuardState())
        set(v) { p.edit().putString(K_STATE, v.toJson().toString()).commit() }

    @Synchronized fun history(): List<HistoryEntry> = runCatching {
        val a = JSONArray(p.getString(K_HISTORY, "[]"))
        (0 until a.length()).map { HistoryEntry.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized fun setHistory(list: List<HistoryEntry>) {
        val a = JSONArray(); list.take(MAX_HISTORY).forEach { a.put(it.toJson()) }
        p.edit().putString(K_HISTORY, a.toString()).commit()
    }

    @Synchronized fun log(type: String, detail: String, ts: Long = System.currentTimeMillis()) {
        setHistory(listOf(HistoryEntry(ts, type, detail)) + history())
    }

    var onboardingDone: Boolean
        get() = p.getBoolean(K_ONBOARDED, false)
        set(v) { p.edit().putBoolean(K_ONBOARDED, v).apply() }

    var ownerProtection: Boolean
        get() = p.getBoolean(K_OWNER_PROTECTION, true)
        set(v) { p.edit().putBoolean(K_OWNER_PROTECTION, v).commit() }

    var blockAdbDuringLock: Boolean
        get() = p.getBoolean(K_BLOCK_ADB, false)
        set(v) { p.edit().putBoolean(K_BLOCK_ADB, v).commit() }

    /** Настройки, ожидающие 10 подтверждений (ослабление). */
    var pendingSettings: Settings?
        get() = p.getString(K_PENDING, null)?.let { runCatching { Settings.fromJson(JSONObject(it)) }.getOrNull() }
        set(v) { p.edit().apply { if (v == null) remove(K_PENDING) else putString(K_PENDING, v.toJson().toString()) }.commit() }

    /**
     * Прогресс экрана подтверждений (1.0.1+, аддитивный ключ): случайный перезапуск экрана возвращает на тот же шаг.
     * Действителен [ExitProgress.MAX_AGE_MS] с момента последнего сохранения и только до перезагрузки.
     */
    var exitProgress: ExitProgress?
        get() = p.getString(K_EXIT_PROGRESS, null)?.let { runCatching { ExitProgress.fromJson(JSONObject(it)) }.getOrNull() }
        set(v) { p.edit().apply { if (v == null) remove(K_EXIT_PROGRESS) else putString(K_EXIT_PROGRESS, v.toJson().toString()) }.commit() }

    // --- Только для debug-сборки (adb broadcast). В release не читаются. ---
    fun engineConfig(): EngineConfig {
        if (!BuildConfig.DEBUG) return EngineConfig()
        val unit = p.getLong(K_DBG_WARN_UNIT, 60_000L)
        val lim = p.getLong(K_DBG_LIMIT_MS, -1L)
        return EngineConfig(warnUnitMs = unit, limitMsOverride = if (lim > 0) lim else null)
    }
    fun setDebug(warnUnit: Long?, limitMs: Long?) {
        if (!BuildConfig.DEBUG) return
        p.edit().apply {
            if (warnUnit != null) { if (warnUnit > 0) putLong(K_DBG_WARN_UNIT, warnUnit) else remove(K_DBG_WARN_UNIT) }
            if (limitMs != null) { if (limitMs > 0) putLong(K_DBG_LIMIT_MS, limitMs) else remove(K_DBG_LIMIT_MS) }
        }.commit()
    }

    companion object {
        const val FILE = "sl_store"          // НЕ МЕНЯТЬ
        const val SCHEMA = 1
        const val K_SCHEMA = "sl_schema"
        const val K_SETTINGS = "sl_settings"
        const val K_STATE = "sl_state"
        const val K_HISTORY = "sl_history"
        const val K_INSTALLED_AT = "sl_installed_at"
        const val K_ONBOARDED = "sl_onboarding_done"
        const val K_OWNER_PROTECTION = "sl_owner_protection"
        const val K_BLOCK_ADB = "sl_block_adb_during_lock"
        const val K_PENDING = "sl_pending_settings"
        const val K_EXIT_PROGRESS = "sl_exit_progress"   // 1.0.1
        const val K_DBG_WARN_UNIT = "sl_debug_warn_unit_ms"
        const val K_DBG_LIMIT_MS = "sl_debug_limit_ms"
        const val MAX_HISTORY = 2000

        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }
    }
}

data class ExitProgress(val purpose: String, val seed: Long, val step: Int, val savedElapsed: Long, val savedWall: Long) {
    fun toJson(): JSONObject = JSONObject().put("purpose", purpose).put("seed", seed).put("step", step)
        .put("savedElapsed", savedElapsed).put("savedWall", savedWall)

    /** Годен ли для восстановления: та же цель, не старше MAX_AGE_MS, без перезагрузки между сохранением и сейчас. */
    fun validFor(purpose: String, nowElapsed: Long): Boolean =
        purpose == this.purpose && nowElapsed >= savedElapsed && nowElapsed - savedElapsed <= MAX_AGE_MS

    companion object {
        const val MAX_AGE_MS = 3 * 60_000L   // = ExitFlow.STEP_TIMEOUT_MS
        fun fromJson(o: JSONObject) = ExitProgress(o.getString("purpose"), o.getLong("seed"), o.optInt("step", 0),
            o.optLong("savedElapsed", 0), o.optLong("savedWall", 0))
    }
}
