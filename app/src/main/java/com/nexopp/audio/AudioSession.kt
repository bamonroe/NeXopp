package com.nexopp.audio

import android.content.Context
import android.net.Uri
import com.nexopp.format.model.Document
import java.io.File

/**
 * The editor's one audio façade: recording, playback, and getting sidecars on and off disk.
 *
 * It exists so nothing else has to know how the three parts fit together — the surface asks it for a
 * stamp, the chrome asks it to start/stop, and the host asks it to sync sidecars around open/save.
 *
 * @param clock supplies "now" for naming a recording; injected so the naming is testable.
 */
class AudioSession(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val recorder = AudioRecorder()
    private val player = AudioPlayer()
    private val store = AudioStore(context)

    /** The bare file name of the recording in progress, or null when idle. */
    var currentFile: String? = null
        private set

    /** True while the microphone is being captured. */
    val isRecording: Boolean get() = recorder.isRecording

    /** True while a clip is sounding. */
    val isPlaying: Boolean get() = player.isPlaying

    /** Notified whenever recording starts or stops, so the chrome can re-render its state. */
    var onStateChanged: (() -> Unit)? = null

    init {
        player.onPlayingChanged = { onStateChanged?.invoke() }
    }

    /**
     * Start a new recording. Returns the sidecar's name, or null when the microphone can't be opened
     * (denied permission, no mic, another app holding it) — the caller reports that to the user.
     */
    fun startRecording(): String? {
        if (recorder.isRecording) return currentFile
        val name = audioFileName(clock())
        if (!recorder.start(store.local(name))) return null
        currentFile = name
        onStateChanged?.invoke()
        return name
    }

    /** Finish the recording and return the file written, or null if none was running. */
    fun stopRecording(): File? {
        val file = recorder.stop() ?: return null
        currentFile = null
        onStateChanged?.invoke()
        return file
    }

    /**
     * The link to stamp on a stroke committed right now, or null when nothing is recording. This is
     * what [com.nexopp.render.DrawingSurfaceView.audioStamp] is wired to.
     */
    fun stamp(): AudioRef? {
        val name = currentFile?.takeIf { recorder.isRecording } ?: return null
        return AudioRef(name, recorder.elapsedMs())
    }

    /** Play [ref]'s recording from its offset; false when the sidecar isn't available locally. */
    fun play(ref: AudioRef): Boolean = player.play(store.local(ref.filename), ref.startMs)

    /** Stop whatever is playing. */
    fun stopPlayback() = player.stop()

    /** The sidecar names [doc] references that are **not** available locally (so won't replay). */
    fun missingFor(doc: Document): Set<String> {
        val referenced = documentAudioFiles(doc)
        return referenced - store.present(referenced)
    }

    /**
     * Pull the sidecars [doc] references out of the nominated [folder] into local storage, so a
     * document authored elsewhere replays here. Returns how many arrived.
     */
    fun importSidecars(folder: Uri, doc: Document): Int =
        store.importFrom(folder, documentAudioFiles(doc)).size

    /**
     * Push the sidecars [doc] references out to the nominated [folder], so the saved `.xopp` has its
     * audio beside it. Returns how many were written.
     */
    fun exportSidecars(folder: Uri, doc: Document): Int =
        store.exportTo(folder, documentAudioFiles(doc)).size

    /** Tear everything down (the host's `onDestroy`); a running recording is finalised first. */
    fun release() {
        stopRecording()
        player.release()
    }
}
