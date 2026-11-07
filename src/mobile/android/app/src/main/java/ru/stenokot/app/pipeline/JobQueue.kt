package ru.stenokot.app.pipeline

import ru.stenokot.app.data.JobPhase
import ru.stenokot.app.data.JobRecord

/** Правила очереди: один тяжёлый конвейер и строгий порядок поступления. */
object JobQueue {
    /** Задачи, ждущие конвейера, в порядке поступления. */
    fun waiting(jobs: Collection<JobRecord>): List<JobRecord> =
        jobs.filter { it.awaitsWorker }.sortedBy { it.seq }

    /** Следующая задача для конвейера. */
    fun next(jobs: Collection<JobRecord>): JobRecord? = waiting(jobs).firstOrNull()

    /** Номер задачи [id] среди ожидающих, начиная с единицы. */
    fun position(jobs: Collection<JobRecord>, id: String): Int? {
        val index = jobs.filter { it.phase == JobPhase.QUEUED }.sortedBy { it.seq }
            .indexOfFirst { it.id == id }
        return if (index < 0) null else index + 1
    }

    /** Самая старая задача с положенным повтором языковой модели. */
    fun nextRetry(jobs: Collection<JobRecord>): JobRecord? =
        jobs.filter { LlmRules.retryEligible(it) }.minByOrNull { it.seq }

    /** Задача сейчас занимает конвейер или вот-вот его займёт. */
    fun isActive(job: JobRecord): Boolean = when (job.phase) {
        JobPhase.RUNNING, JobPhase.PAUSED, JobPhase.TRANSCRIPT_READY, JobPhase.WAITING_MODEL -> true
        else -> false
    }
}
