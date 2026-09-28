package com.example.screenlimit.core

import kotlin.random.Random

/**
 * Машина состояний выхода из блокировки: ровно 10 последовательных подтверждений.
 * Каждый шаг: свой вопрос, случайное положение кнопки, задержка активации,
 * часть шагов требует ввести фразу. Подтверждение до активации кнопки отклоняется,
 * любая «Отмена» — возврат к блокировке (прогресс сбрасывается).
 */
class ExitFlow(
    seed: Long,
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val stepTimeoutMs: Long = STEP_TIMEOUT_MS,
) {
    enum class Phase { IN_PROGRESS, COMPLETED, CANCELLED }

    data class Step(
        val index: Int,             // 0..9
        val question: String,
        val confirmLabel: String,
        val phrase: String?,        // если не null — нужно ввести дословно
        val position: Int,          // 0..3 — вариант раскладки кнопок
        val confirmFirst: Boolean,  // порядок кнопок «Подтвердить»/«Отмена»
    )

    sealed class Result {
        object Advanced : Result()
        object Completed : Result()
        data class Rejected(val why: String) : Result()
        object Cancelled : Result()
    }

    val steps: List<Step>
    var phase = Phase.IN_PROGRESS; private set
    var current = 0; private set
    private var shownAt = Long.MIN_VALUE

    init {
        val rnd = Random(seed)
        val q = QUESTIONS.shuffled(rnd)
        var lastPos = -1
        steps = (0 until TOTAL).map { i ->
            var pos: Int
            do { pos = rnd.nextInt(4) } while (pos == lastPos)
            lastPos = pos
            val phrase = if (i in TYPED_STEPS) PHRASES[(i + rnd.nextInt(PHRASES.size)) % PHRASES.size] else null
            Step(i, q[i % q.size], LABELS[rnd.nextInt(LABELS.size)], phrase, pos, rnd.nextBoolean())
        }
    }

    val step: Step get() = steps[current]

    /** Экран шага отрисован — с этого момента отсчитывается задержка. */
    fun onShown(now: Long) { if (shownAt == Long.MIN_VALUE) shownAt = now }

    fun activatesAt(): Long = if (shownAt == Long.MIN_VALUE) Long.MAX_VALUE else shownAt + delayMs

    fun isActive(now: Long) = phase == Phase.IN_PROGRESS && now >= activatesAt()

    fun confirm(now: Long, typed: String? = null): Result {
        if (phase != Phase.IN_PROGRESS) return Result.Rejected("flow finished")
        if (shownAt == Long.MIN_VALUE || now < activatesAt()) return Result.Rejected("too early")
        if (now - shownAt > stepTimeoutMs) { cancel(); return Result.Cancelled }
        val p = step.phrase
        if (p != null && normalize(typed) != normalize(p)) return Result.Rejected("phrase mismatch")
        current++
        shownAt = Long.MIN_VALUE
        return if (current >= TOTAL) { phase = Phase.COMPLETED; current = TOTAL - 1; Result.Completed } else Result.Advanced
    }

    fun cancel(): Result { phase = Phase.CANCELLED; return Result.Cancelled }

    val completedCount: Int get() = if (phase == Phase.COMPLETED) TOTAL else current

    companion object {
        const val TOTAL = 10
        const val DEFAULT_DELAY_MS = 4_000L
        const val STEP_TIMEOUT_MS = 3 * 60_000L
        val TYPED_STEPS = setOf(2, 5, 7, 9)

        fun normalize(s: String?) = (s ?: "").trim().lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ")

        val PHRASES = listOf(
            "Я осознанно хочу выйти",
            "Я понимаю, что нарушаю своё решение",
            "Я осознанно хочу выйти из блокировки",
            "Это моё взвешенное решение",
        )
        val LABELS = listOf("Да, продолжить", "Подтверждаю", "Да, я уверен", "Продолжить выход")
        val QUESTIONS = listOf(
            "Вы действительно хотите выйти из блокировки?",
            "Вы сами поставили этот лимит. Всё ещё хотите выйти?",
            "Что вы собираетесь делать в телефоне? Это правда срочно?",
            "Может, лучше отложить телефон и заняться чем-то другим?",
            "Звонки и SMS доступны и так. Вам точно нужно больше?",
            "Через 10 минут это желание может пройти. Продолжить?",
            "Вспомните, зачем вы включили ограничение. Продолжаем?",
            "Выход будет записан в историю. Продолжить?",
            "Вы уверены, что это не привычка, а необходимость?",
            "Последний шанс остаться в режиме отдыха. Выйти?",
            "Сделайте глубокий вдох. Всё ещё хотите выйти?",
            "Завтра вы скажете себе спасибо, если останетесь. Выйти всё равно?",
        )
    }
}
