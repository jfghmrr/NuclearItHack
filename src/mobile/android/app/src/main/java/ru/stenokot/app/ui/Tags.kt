package ru.stenokot.app.ui

/** Стабильные testTag. Это договор с автоматической проверкой, менять их нельзя. */
object Tags {
    const val STATUS = "txt_status"
    const val STATUS_HINT = "txt_status_hint"
    const val BACK = "btn_back"
    const val IMPORT = "btn_import"
    const val IMPORT_ALL = "btn_import_all"
    const val START = "btn_start"
    const val CANCEL = "btn_cancel"
    const val RESUME = "btn_resume"
    const val MODELS = "txt_models"
    const val LIST_AUDIO = "list_audio"
    const val LIST_HISTORY = "list_history"
    const val LIST_QUEUE = "list_queue"
    const val ROW_AUDIO_PERMISSION = "row_audio_permission"
    const val CARD_FILE = "card_file"
    const val CARD_JOB = "card_job"
    const val FILE_NAME = "txt_file_name"
    const val PREFLIGHT = "txt_preflight"
    const val STAGE = "txt_stage"
    const val STAGE_INDEX = "txt_stage_index"
    const val STAGE_PERCENT = "txt_stage_percent"
    const val STAGE_COUNTER = "txt_stage_counter"
    const val STAGE_PROGRESS = "progress_stage"
    const val ELAPSED = "txt_elapsed"
    const val REMAINING = "txt_remaining"
    const val LIST_STAGES = "list_stages"
    const val DELETE_CONFIRM = "btn_delete_confirm"
    const val DELETE_DISMISS = "btn_delete_dismiss"
    const val DELETE_EXPORTED = "row_delete_exported"

    /** Строка найденного аудио. */
    fun rowAudio(index: Int) = "row_audio_$index"

    /** Строка истории. */
    fun rowHistory(index: Int) = "row_history_$index"

    /** Строка очереди. */
    fun rowQueue(index: Int) = "row_queue_$index"

    /** Кнопка удаления строки истории. */
    fun rowDelete(index: Int) = "btn_delete_$index"

    /** Строка этапа на рельсе. */
    fun rowStage(name: String) = "row_stage_$name"
}
