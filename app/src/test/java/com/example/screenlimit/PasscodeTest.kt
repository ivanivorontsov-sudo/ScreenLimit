package com.example.screenlimit

import com.example.screenlimit.core.Passcode
import org.junit.Assert.*
import org.junit.Test

class PasscodeTest {
    @Test fun hashIsDeterministicForSameSaltAndVerifies() {
        val salt = Passcode.newSalt()
        val h1 = Passcode.hash("Секрет123", salt)
        val h2 = Passcode.hash("Секрет123", salt)
        assertArrayEquals(h1, h2)
        assertTrue(Passcode.verify("Секрет123", salt, h1))
    }

    @Test fun wrongPasswordDoesNotVerify() {
        val salt = Passcode.newSalt()
        val h = Passcode.hash("correct horse", salt)
        assertFalse(Passcode.verify("wrong horse", salt, h))
        assertFalse(Passcode.verify("correct hors", salt, h))
    }

    @Test fun differentSaltGivesDifferentHash() {
        val h1 = Passcode.hash("pw", Passcode.newSalt())
        val h2 = Passcode.hash("pw", Passcode.newSalt())
        assertFalse(Passcode.constantTimeEquals(h1, h2))
    }

    @Test fun hashIs32Bytes() {
        assertEquals(32, Passcode.hash("x", Passcode.newSalt()).size)
    }

    @Test fun constantTimeEqualsBasics() {
        assertTrue(Passcode.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Passcode.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(Passcode.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }

    @Test fun minPasswordLength() {
        assertFalse(Passcode.isAcceptablePassword("12345"))
        assertTrue(Passcode.isAcceptablePassword("123456"))
    }

    @Test fun cooldownEscalatesAfterFiveFails() {
        assertEquals(0L, Passcode.cooldownMsForFails(0))
        assertEquals(0L, Passcode.cooldownMsForFails(4))
        assertEquals(Passcode.COOLDOWNS_MS[0], Passcode.cooldownMsForFails(5))
        assertEquals(Passcode.COOLDOWNS_MS[1], Passcode.cooldownMsForFails(6))
        // Монотонно не убывает и упирается в максимум.
        var prev = 0L
        for (n in 5..40) {
            val cd = Passcode.cooldownMsForFails(n)
            assertTrue(cd >= prev); prev = cd
        }
        assertEquals(Passcode.MAX_COOLDOWN_MS, Passcode.cooldownMsForFails(1000))
    }

    @Test fun recoveryCodeFormatAndNormalize() {
        val code = Passcode.newRecoveryCode()
        assertEquals(5, code.split("-").size)
        // Нормализация устойчива к регистру, разделителям и похожим символам.
        val norm = Passcode.normalizeRecovery(code)
        assertEquals(norm, Passcode.normalizeRecovery(code.lowercase()))
        assertEquals(norm, Passcode.normalizeRecovery(code.replace("-", " ")))
        assertEquals("ABC011V", Passcode.normalizeRecovery("abc o-l i u"))
    }

    @Test fun recoveryRoundTripVerifies() {
        val code = Passcode.newRecoveryCode()
        val salt = Passcode.newSalt()
        val h = Passcode.hash(Passcode.normalizeRecovery(code), salt)
        assertTrue(Passcode.verify(Passcode.normalizeRecovery(code.lowercase()), salt, h))
        assertFalse(Passcode.verify(Passcode.normalizeRecovery(Passcode.newRecoveryCode()), salt, h))
    }
}
