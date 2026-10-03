package com.example.screenlimit.core

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Чистая логика пароля защиты настроек (1.0.2) — тестируется на JVM.
 *
 * Пароль НЕ хранится в открытом виде: держим только PBKDF2-SHA256(пароль, соль) и саму соль.
 * Сравнение — в постоянное время. Отдельно хранится «код восстановления» (тоже только его хеш),
 * который показывается один раз при установке и позволяет сбросить забытый пароль.
 *
 * Эскалация блокировки: первые [MAX_FAILS_BEFORE_LOCK] неверных попыток — без задержки,
 * далее каждая новая ошибка увеличивает паузу по таблице [COOLDOWNS_MS].
 */
object Passcode {
    const val PBKDF2_ALG = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 120_000
    const val KEY_LEN_BITS = 256
    const val SALT_LEN = 16
    const val MIN_PASSWORD_LEN = 6
    const val MAX_FAILS_BEFORE_LOCK = 5

    /** Эскалирующие паузы, начиная с 5-й неверной попытки: 30с, 1м, 2м, 5м, 15м, 30м, 60м. */
    val COOLDOWNS_MS = longArrayOf(30_000L, 60_000L, 2 * 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L)
    val MAX_COOLDOWN_MS = COOLDOWNS_MS.last()

    private val rnd = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }
    fun newSalt(): ByteArray = randomBytes(SALT_LEN)

    fun pbkdf2(password: CharArray, salt: ByteArray, iters: Int = ITERATIONS, keyLenBits: Int = KEY_LEN_BITS): ByteArray {
        val spec = PBEKeySpec(password, salt, iters, keyLenBits)
        try {
            return SecretKeyFactory.getInstance(PBKDF2_ALG).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun hash(password: String, salt: ByteArray, iters: Int = ITERATIONS): ByteArray =
        pbkdf2(password.toCharArray(), salt, iters)

    /** Сравнение в постоянное время — не выдаёт по времени, где именно разошлись байты. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }

    fun verify(password: String, salt: ByteArray, expectedHash: ByteArray, iters: Int = ITERATIONS): Boolean =
        constantTimeEquals(hash(password, salt, iters), expectedHash)

    fun isAcceptablePassword(password: String): Boolean = password.length >= MIN_PASSWORD_LEN

    // ---- Код восстановления ----
    // Крокфордовский Base32 без похожих символов (без I,L,O,U) — удобно списать/сфотографировать.
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 20 символов энтропии (~100 бит) группами по 4: XXXX-XXXX-XXXX-XXXX-XXXX. */
    fun newRecoveryCode(groups: Int = 5, groupLen: Int = 4): String {
        val sb = StringBuilder()
        for (g in 0 until groups) {
            if (g > 0) sb.append('-')
            for (i in 0 until groupLen) sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)])
        }
        return sb.toString()
    }

    /** Приводим ввод кода к канону: верхний регистр, без разделителей, частые замены похожих символов. */
    fun normalizeRecovery(s: String): String {
        val sb = StringBuilder()
        for (ch in s.uppercase()) {
            val c = when (ch) {
                'O' -> '0'; 'I', 'L' -> '1'; 'U' -> 'V'
                else -> ch
            }
            if (c in ALPHABET) sb.append(c)
        }
        return sb.toString()
    }

    /** Пауза после [fails]-й неверной попытки (0 — блокировки ещё нет). */
    fun cooldownMsForFails(fails: Int): Long {
        if (fails < MAX_FAILS_BEFORE_LOCK) return 0L
        val idx = (fails - MAX_FAILS_BEFORE_LOCK).coerceIn(0, COOLDOWNS_MS.size - 1)
        return COOLDOWNS_MS[idx]
    }
}
