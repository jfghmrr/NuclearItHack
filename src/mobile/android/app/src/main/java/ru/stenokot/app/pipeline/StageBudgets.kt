package ru.stenokot.app.pipeline

import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.Stage

/** Память и коды ошибок по стадиям. Значения бюджета взяты из раздела 7.11 спецификации. */
object StageBudgets {
    private const val MIB = 1024L * 1024

    /**
     * Свободная память, при которой распознаватель можно держать наготове: бюджет стадии плюс сама
     * модель. После загрузки на устройстве всё ещё остаётся бюджет стадии.
     */
    const val WARM_ASR_BYTES: Long = (850L + 600L) * MIB

    /** Сколько памяти стадии нужно сверх занятого. Ниже бюджета запуск всё равно идёт. */
    fun memoryBytes(stage: Stage): Long = when (stage) {
        Stage.IMPORT -> 0
        Stage.DECODE -> 170 * MIB
        Stage.VAD -> 190 * MIB
        Stage.LANG_ID -> 850 * MIB
        Stage.ASR -> 850 * MIB
        Stage.DIARIZE -> 450 * MIB
        Stage.MERGE -> 100 * MIB
        Stage.VALIDATE -> 50 * MIB
        Stage.LLM -> LlmRules.MIN_MEMORY_BYTES
    }

    /** Код для неожиданного сбоя стадии: кодов ровно столько, сколько есть в экране ошибок. */
    fun failureCode(stage: Stage): ErrorCode = when (stage) {
        Stage.IMPORT -> ErrorCode.IMPORT_READ_FAILED
        Stage.DECODE -> ErrorCode.DECODE_CORRUPT
        Stage.VAD, Stage.LANG_ID, Stage.ASR, Stage.DIARIZE -> ErrorCode.MODEL_LOAD_FAILED
        Stage.MERGE, Stage.VALIDATE, Stage.LLM -> ErrorCode.CONTRACT_INVALID
    }
}
