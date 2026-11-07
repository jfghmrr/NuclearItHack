package ru.stenokot.app.ui

/** Стабильные testTag. Это договор с автоматической проверкой, менять их нельзя. */
object Tags {
    const val STATUS = "txt_status"
    const val STATUS_HINT = "txt_status_hint"
    const val BACK = "btn_back"
    const val IMPORT = "btn_import"
    const val IMPORT_ALL = "btn_import_all"
    const val RECORD = "btn_record"
    const val MIC_NOTICE = "txt_mic_notice"
    const val MIC_SETTINGS = "btn_mic_settings"
    const val MIC_DISMISS = "btn_mic_dismiss"
    const val REC_ELAPSED = "txt_rec_elapsed"
    const val REC_LEVEL = "progress_rec_level"
    const val REC_BUSY = "txt_rec_busy"
    const val REC_STOP = "btn_rec_stop"
    const val REC_CANCEL = "btn_rec_cancel"
    const val REC_CANCEL_CONFIRM = "btn_rec_cancel_confirm"
    const val REC_CANCEL_DISMISS = "btn_rec_cancel_dismiss"
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
    const val ERROR_TITLE = "txt_error_title"
    const val ERROR_BODY = "txt_error_body"
    const val ERROR_DETAILS = "btn_error_details"
    const val ERROR_DETAILS_TEXT = "txt_error_details"
    const val COPY_REPORT = "btn_copy_diagnostics"
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
    const val META_EXPORT_ERROR = "txt_export_error"
    const val EXPORT_META = "btn_export_meta"
    const val NOTIFICATIONS = "row_notifications"
    const val LICENSES = "row_licenses"
    const val LIST_LICENSES = "list_licenses"
    const val LICENSE_TEXT = "txt_license"
    const val DELETE_CONFIRM = "btn_delete_confirm"
    const val DELETE_DISMISS = "btn_delete_dismiss"
    const val DELETE_EXPORTED = "row_delete_exported"
    const val AUDIO_META = "txt_audio_meta"
    const val TAB_TRANSCRIPT = "tab_transcript"
    const val TAB_PROTOCOL = "tab_protocol"
    const val BANNER_LLM = "banner_llm"
    const val LIST_SPEAKERS = "list_speakers"
    const val UTT_COUNT = "txt_utt_count"
    const val LANG_HINT = "txt_lang_hint"
    const val LIST_TRANSCRIPT = "list_transcript"
    const val PLAY = "btn_play"
    const val SLIDER_PLAYER = "slider_player"
    const val PLAYER_TIME = "txt_player_time"
    const val PLAYER_HINT = "txt_player_hint"
    const val OUTPUT_PATH = "txt_output_path"
    const val OUTPUT_META = "txt_output_meta"
    const val EXPORT = "btn_export"
    const val EXPORT_PROTOCOL = "btn_export_protocol"
    const val EXPORT_ERROR = "txt_export_error"
    const val EXPORT_PANEL = "panel_export"
    const val EXPORT_PANEL_OPEN = "btn_export_panel"
    const val SHARE = "btn_share"
    const val COPY_PATH = "btn_copy_path"
    const val OPEN_FOLDER = "btn_open_folder"
    const val SEARCH_OPEN = "btn_search"
    const val SEARCH_CLOSE = "btn_search_close"
    const val SEARCH_CLEAR = "btn_search_clear"
    const val SEARCH_RESET = "btn_search_reset"
    const val SEARCH_FIELD = "txt_search"
    const val SEARCH_COUNT = "txt_search_count"
    const val PICK_OTHER = "btn_pick_other"
    const val LLM_DRAFT = "txt_llm_draft"
    const val LLM_COVERED = "txt_llm_covered"
    const val LIST_CHAPTERS = "list_chapters"
    const val LIST_PROTOCOL = "list_protocol"
    const val PROTOCOL_STATE = "txt_protocol_state"
    const val OPEN_TRANSCRIPT = "btn_open_transcript"
    const val DETAILS = "btn_details"
    const val DETAILS_CODE = "txt_details_code"
    const val COPY_DIAGNOSTICS = "btn_copy_diagnostics"
    const val SETTINGS = "btn_settings"
    const val LIBRARY_SEARCH = "txt_library_search"
    const val LIBRARY_SEARCH_CLEAR = "btn_library_search_clear"
    const val LIBRARY_RESET = "btn_library_reset"
    const val MIC_CONTINUE = "btn_mic_continue"
    const val REC_DISCARD = "btn_rec_discard"
    const val PROCESSING_MENU = "btn_processing_menu"
    const val CANCEL_CONFIRM = "btn_cancel_confirm"
    const val CANCEL_DISMISS = "btn_cancel_dismiss"
    const val TO_LIBRARY = "btn_to_library"
    const val DONE_TITLE = "txt_done_title"
    const val THEME_ROW = "row_theme"
    const val MOTION_ROW = "row_motion"
    const val DIAGNOSTICS_ROW = "row_diagnostics"
    const val ABOUT_ROW = "row_about"
    const val MODELS_ROW = "row_models"

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

    /** Реплика стенограммы. Номер считается по всей записи и не меняется при сужении списка. */
    fun rowUtt(index: Int) = "row_utt_$index"

    /** Фильтр библиотеки. */
    fun chipFilter(name: String) = "chip_filter_$name"

    /** Вариант темы в настройках. */
    fun themeOption(name: String) = "row_theme_$name"

    /** Значок говорящего в фильтре. */
    fun chipSpeaker(number: Int) = "chip_speaker_$number"

    /** Глава оглавления. */
    fun rowChapter(index: Int) = "row_chapter_$index"

    /** Пункт протокола. */
    fun rowProtocol(index: Int) = "row_protocol_$index"
}
