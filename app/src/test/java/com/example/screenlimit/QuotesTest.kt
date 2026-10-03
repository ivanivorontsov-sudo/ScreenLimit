package com.example.screenlimit

import com.example.screenlimit.core.Quotes
import org.junit.Assert.*
import org.junit.Test

class QuotesTest {
    @Test fun countInRange() {
        assertTrue("ожидалось 25–40 цитат, а их ${Quotes.ALL.size}", Quotes.ALL.size in 25..40)
    }

    @Test fun allNonBlankAndAttributed() {
        Quotes.ALL.forEach {
            assertTrue(it.text.isNotBlank())
            assertTrue(it.author.isNotBlank())
            assertTrue("цитата должна быть короткой для плитки: ${it.text}", it.text.length <= 160)
        }
    }

    @Test fun noDuplicateTexts() {
        assertEquals(Quotes.ALL.size, Quotes.ALL.map { it.text }.toSet().size)
    }

    @Test fun formatIncludesAuthor() {
        val q = Quotes.ALL.first()
        assertTrue(Quotes.format(q).contains(q.author))
    }
}
