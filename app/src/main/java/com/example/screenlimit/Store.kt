package com.example.screenlimit

import android.content.Context
import android.content.SharedPreferences
import com.example.screenlimit.core.EngineConfig
import com.example.screenlimit.core.GuardState
import com.example.screenlimit.core.HistoryEntry
import com.example.screenlimit.core.Passcode
import com.example.screenlimit.core.Settings
import org.json.JSONArray
import org.json.JSONObject
import android.os.SystemClock
import android.util.Base64

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

    // ---- Пароль защиты настроек (1.0.2). Аддитивные ключи, миграция не нужна. ----
    // Хранится только PBKDF2-SHA256(пароль, соль) + соль; открытого пароля нет. Отдельно — хеш кода восстановления.
    @Synchronized fun isPasswordSet(): Boolean = p.contains(K_PW_HASH) && p.contains(K_PW_SALT)

    /**
     * Задать (или сменить) пароль защиты настроек. Возвращает НОВЫЙ код восстановления — он показывается
     * пользователю один раз и больше нигде не хранится в открытом виде. Сбрасывает счётчик неверных попыток.
     */
    @Synchronized fun setPassword(password: String): String {
        val salt = Passcode.newSalt()
        val hash = Passcode.hash(password, salt)
        val recovery = Passcode.newRecoveryCode()
        val recSalt = Passcode.newSalt()
        val recHash = Passcode.hash(Passcode.normalizeRecovery(recovery), recSalt)
        p.edit()
            .putString(K_PW_HASH, b64(hash)).putString(K_PW_SALT, b64(salt)).putInt(K_PW_ITERS, Passcode.ITERATIONS)
            .putString(K_REC_HASH, b64(recHash)).putString(K_REC_SALT, b64(recSalt))
            .putInt(K_PW_FAILS, 0).putLong(K_PW_LOCK_UNTIL, 0)
            .commit()
        return recovery
    }

    @Synchronized fun verifyPassword(password: String): Boolean {
        val hash = p.getString(K_PW_HASH, null)?.let(::unb64) ?: return false
        val salt = p.getString(K_PW_SALT, null)?.let(::unb64) ?: return false
        val iters = p.getInt(K_PW_ITERS, Passcode.ITERATIONS)
        return Passcode.verify(password, salt, hash, iters)
    }

    @Synchronized fun verifyRecovery(code: String): Boolean {
        val hash = p.getString(K_REC_HASH, null)?.let(::unb64) ?: return false
        val salt = p.getString(K_REC_SALT, null)?.let(::unb64) ?: return false
        return Passcode.verify(Passcode.normalizeRecovery(code), salt, hash)
    }

    /** Сколько ещё миллисекунд действует блокировка ввода пароля (0 — можно вводить). Устойчиво к перезагрузке. */
    @Synchronized fun pwLockRemainingMs(): Long {
        val until = p.getLong(K_PW_LOCK_UNTIL, 0)
        val rem = until - SystemClock.elapsedRealtime()
        return if (rem in 1..Passcode.MAX_COOLDOWN_MS) rem else 0L
    }

    @get:Synchronized val pwFailCount: Int get() = p.getInt(K_PW_FAILS, 0)

    /** Зафиксировать неверную попытку; при достижении порога включить эскалирующую паузу. Возвращает паузу (мс). */
    @Synchronized fun recordPwFail(): Long {
        val fails = pwFailCount + 1
        val cd = Passcode.cooldownMsForFails(fails)
        p.edit().putInt(K_PW_FAILS, fails)
            .putLong(K_PW_LOCK_UNTIL, if (cd > 0) SystemClock.elapsedRealtime() + cd else 0L)
            .commit()
        return cd
    }

    @Synchronized fun resetPwFails() { p.edit().putInt(K_PW_FAILS, 0).putLong(K_PW_LOCK_UNTIL, 0).commit() }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

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
        const val K_PW_HASH = "sl_pw_hash"               // 1.0.2
        const val K_PW_SALT = "sl_pw_salt"
        const val K_PW_ITERS = "sl_pw_iters"
        const val K_REC_HASH = "sl_pw_recovery_hash"
        const val K_REC_SALT = "sl_pw_recovery_salt"
        const val K_PW_FAILS = "sl_pw_fail_count"
        const val K_PW_LOCK_UNTIL = "sl_pw_lock_until_elapsed"
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
