package com.nexopp.audio

import android.media.MediaPlayer
import java.io.File

/**
 * Plays a stroke's recording back from the moment that stroke was drawn.
 *
 * Only one clip plays at a time — tapping a second stroke replaces the first, which matches how
 * "play object" behaves on the desktop. The player owns a single [MediaPlayer] it recycles, so the
 * caller only has to remember to [release] it when the editor goes away.
 */
class AudioPlayer {

    private var player: MediaPlayer? = null

    /** Notified when playback starts or stops, so the chrome can show a "playing" state. */
    var onPlayingChanged: ((Boolean) -> Unit)? = null

    /** True while a clip is sounding. */
    val isPlaying: Boolean get() = player?.isPlaying == true

    /**
     * Play [file] from [startMs]. Returns false when the file can't be opened or decoded (a missing
     * sidecar is the common case), leaving any previous clip stopped.
     */
    fun play(file: File, startMs: Int): Boolean {
        stop()
        if (!file.isFile) return false
        val mp = MediaPlayer()
        val ok = runCatching {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            // Clamp: a `ts` past the end of a truncated sidecar would otherwise seek into nothing.
            mp.seekTo(startMs.coerceIn(0, mp.duration.coerceAtLeast(0)))
            mp.setOnCompletionListener { stop() }
            mp.start()
        }.isSuccess
        if (!ok) {
            runCatching { mp.release() }
            return false
        }
        player = mp
        onPlayingChanged?.invoke(true)
        return true
    }

    /** Stop and free whatever is playing; a no-op when idle. */
    fun stop() {
        val mp = player ?: return
        player = null
        runCatching { mp.stop() }
        runCatching { mp.release() }
        onPlayingChanged?.invoke(false)
    }

    /** Release the underlying player for good (call from the host's teardown). */
    fun release() = stop()
}
