package ru.stenokot.app.ui.result

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlinx.coroutines.delay

private const val TICK_MS = 100L
private const val NO_PREVIEW = -1L

/** Адрес записи: путь к файлу или готовый адрес от системы. */
internal fun audioUri(path: String): Uri =
    if (path.contains("://")) Uri.parse(path) else Uri.fromFile(File(path))

/**
 * Плеер экрана результата. Движок создаётся при первом воспроизведении: чтение стенограммы не
 * должно платить за него памятью. Сеть не нужна, запись лежит на устройстве. Позиция живёт в
 * состоянии и читается только на этапе рисования, поэтому список от неё не перекомпоновывается.
 */
@Stable
class ResultPlayer internal constructor(
    private val appContext: Context,
    private val source: Uri?,
    startMs: Long,
    private val resumeOnStart: Boolean,
) {
    private var engine: ExoPlayer? = null
    private val committed = mutableLongStateOf(startMs)
    private val preview = mutableLongStateOf(NO_PREVIEW)

    /** Звук идёт или вот-вот пойдёт: кнопка отвечает сразу, а не после буферизации. */
    var isPlaying by mutableStateOf(false)
        private set

    /** Движок не смог открыть запись. */
    var failed by mutableStateOf(false)
        private set

    /** Пользователь уже обращался к записи. До этого ни одна реплика не считается звучащей. */
    var engaged by mutableStateOf(startMs > 0)
        private set

    /** Есть что воспроизводить. */
    val available: Boolean get() = source != null && !failed

    /** Текущая позиция с учётом перетаскивания. Читать только на этапе рисования или через derived. */
    val positionMs: Long
        get() {
            val dragged = preview.longValue
            return if (dragged != NO_PREVIEW) dragged else committed.longValue
        }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refresh()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) engine?.let { committed.longValue = it.duration.coerceAtLeast(0) }
            refresh()
        }

        override fun onPlayerError(error: PlaybackException) {
            failed = true
            release()
        }
    }

    private fun refresh() {
        val player = engine
        isPlaying = player != null && player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE
    }

    private fun ensureEngine(): ExoPlayer? {
        engine?.let { return it }
        val uri = source?.takeIf { !failed } ?: return null
        val speech = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        return ExoPlayer.Builder(appContext)
            .setAudioAttributes(speech, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { player ->
                player.addListener(listener)
                player.setMediaItem(MediaItem.fromUri(uri), committed.longValue)
                player.prepare()
                engine = player
            }
    }

    /** Кнопка плеера. После конца записи начинает сначала. */
    fun toggle() {
        if (isPlaying) {
            pause()
            return
        }
        val player = ensureEngine() ?: return
        engaged = true
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    /** Останавливает звук, позиция остаётся. */
    fun pause() {
        engine?.pause()
        syncPosition()
    }

    /** Переходит к [positionMs]. Если [play], запускает звук с этого места. */
    fun seekTo(positionMs: Long, play: Boolean) {
        engaged = true
        preview.longValue = NO_PREVIEW
        committed.longValue = positionMs.coerceAtLeast(0)
        val player = if (play) ensureEngine() else engine
        player?.seekTo(committed.longValue)
        if (play) player?.play()
    }

    /** Показывает позицию под пальцем, пока запись не перематывается. */
    fun previewAt(positionMs: Long) {
        engaged = true
        preview.longValue = positionMs.coerceAtLeast(0)
    }

    /** Завершает перетаскивание и перематывает запись. */
    fun commitPreview() {
        val target = preview.longValue
        if (target != NO_PREVIEW) seekTo(target, play = isPlaying)
    }

    /** Снимает позицию с движка. */
    internal fun syncPosition() {
        engine?.let { committed.longValue = it.currentPosition }
    }

    /** Возобновляет звук после поворота экрана. */
    internal fun resumeIfRequested() {
        if (resumeOnStart) seekTo(committed.longValue, play = true)
    }

    /** Освобождает кодек. Позиция остаётся, движок создастся заново при следующем запуске. */
    fun release() {
        engine?.let { player ->
            committed.longValue = player.currentPosition
            player.removeListener(listener)
            player.release()
        }
        engine = null
        isPlaying = false
    }

    internal companion object {
        /** Позиция и признак воспроизведения переживают поворот экрана. */
        fun saver(context: Context, source: Uri?): Saver<ResultPlayer, LongArray> = Saver(
            save = { longArrayOf(it.positionMs, if (it.isPlaying) 1 else 0) },
            restore = { ResultPlayer(context, source, it[0], it[1] == 1L) },
        )
    }
}

/**
 * Плеер живёт вместе с экраном. Уход с экрана освобождает его, а сворачивание ставит на паузу:
 * уведомления с управлением нет, и звук без экрана нечем было бы остановить. Поворот паузой не
 * считается.
 */
@Composable
fun rememberResultPlayer(path: String?): ResultPlayer {
    val context = LocalContext.current.applicationContext
    val activity = LocalActivity.current
    val source = path?.let(::audioUri)
    val player = rememberSaveable(path, saver = ResultPlayer.saver(context, source)) {
        ResultPlayer(context, source, 0, false)
    }
    LaunchedEffect(player) { player.resumeIfRequested() }
    LaunchedEffect(player, player.isPlaying) {
        while (player.isPlaying) {
            player.syncPosition()
            delay(TICK_MS)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) player.pause()
    }
    return player
}
