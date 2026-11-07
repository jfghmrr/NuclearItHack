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
    const val ERROR_TITLE = "txt_error_title"
    const val ERROR_BODY = "txt_error_body"
    const val ERROR_DETAILS = "btn_error_details"
    const val ERROR_DETAILS_TEXT = "txt_error_details"
    const val COPY_DIAGNOSTICS = "btn_copy_diagnostics"
    const val OPEN_MODELS = "btn_models"
    const val OFFLINE = "txt_offline"
    const val PERMISSIONS = "btn_permissions"
    const val LIST_MODELS = "list_models"
    const val RTF_CORE = "txt_rtf_core"
    const val RTF_FULL = "txt_rtf_full"
    const val LIST_STAGE_METRICS = "list_stage_metrics"
    const val JSON_VALID = "txt_json_valid"
    const val JSON_SHA256 = "txt_json_sha256"
    const val JSON_PATH = "txt_json_path"
    const val EXPORT_ERROR = "txt_export_error"
    const val EXPORT_META = "btn_export_meta"
    const val NOTIFICATIONS = "row_notifications"
    const val LICENSES = "row_licenses"
    const val LIST_LICENSES = "list_licenses"
    const val LICENSE_TEXT = "txt_license"

    /** Строка найденного аудио. */
    fun rowAudio(index: Int) = "row_audio_$index"

    /** Строка истории. */
    fun rowHistory(index: Int) = "row_history_$index"

    /** Строка очереди. */
    fun rowQueue(index: Int) = "row_queue_$index"

    /** Кнопка удаления строки истории. */
    fun rowDelete(index: Int) = "btn_delete_$index"

    /** Строка модели на экране проверки. */
    fun rowModel(index: Int) = "row_model_$index"

    /** Строка лицензии. */
    fun rowLicense(index: Int) = "row_license_$index"

    /** Строка этапа на рельсе. */
    fun rowStage(name: String) = "row_stage_$name"
}
