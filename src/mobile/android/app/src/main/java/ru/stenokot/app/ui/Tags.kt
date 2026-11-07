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

    /** Реплика стенограммы. Номер считается по всей записи и не меняется при сужении списка. */
    fun rowUtt(index: Int) = "row_utt_$index"

    /** Значок говорящего в фильтре. */
    fun chipSpeaker(number: Int) = "chip_speaker_$number"

    /** Глава оглавления. */
    fun rowChapter(index: Int) = "row_chapter_$index"

    /** Пункт протокола. */
    fun rowProtocol(index: Int) = "row_protocol_$index"
}
