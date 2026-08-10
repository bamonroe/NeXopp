package com.nexopp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.nexopp.audio.AudioSession
import com.nexopp.render.DrawingSurfaceView
import com.nexopp.ui.AudioUiState

// --- audio: record onto strokes, replay from them, keep sidecars beside the .xopp -----------
//
// [MainActivity]'s audio half, kept out of the activity file itself.
//
// A stroke drawn while recording is stamped with the current file and offset ([AudioSession.stamp]),
// and tapping it later replays from that offset. The recordings themselves are plain `.wav` sidecar
// files that live *beside* the `.xopp` rather than inside it, because the format only stores the
// filename and timestamp — so keeping them in step with the document is a copy in
// ([pullAudioSidecars]) and a copy out ([pushAudioSidecars]) against the folder the user nominated.
//
// Without a nominated folder audio still records and plays, but never leaves the app's own storage.

/** Wire the canvas to the audio session: stamp new strokes while recording, replay on a tap. */
internal fun MainActivity.attachAudio(view: DrawingSurfaceView) {
    view.audioStamp = { audio.stamp() }
    view.onAudioTap = { ref ->
        when {
            ref == null -> toast("That stroke has no recording")
            !audio.play(ref) -> toast("Recording not found: ${ref.filename}")
            else -> Unit
        }
    }
}

/** The audio slot's current state, read fresh on every recomposition [MainActivity.audioTick] triggers. */
internal fun MainActivity.audioUiState(): AudioUiState {
    audioTick.value // read so Compose re-invokes this when the session changes
    return AudioUiState(
        recording = audio.isRecording,
        playing = audio.isPlaying,
        folderChosen = audioFolder != null,
        onToggleRecord = ::toggleRecording,
        onStopPlayback = { audio.stopPlayback() },
        onChooseFolder = { audioFolderLauncher.launch(null) },
    )
}

/** Stop a running recording, or ask for the microphone and start one. */
internal fun MainActivity.toggleRecording() {
    if (audio.isRecording) {
        val file = audio.stopRecording()
        toast(if (file != null) "Recording saved: ${file.name}" else "Nothing was recording")
        pushAudioSidecars()
        return
    }
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) beginRecording() else recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
}

/** Start recording, reporting either the file we opened or the microphone we could not. */
internal fun MainActivity.beginRecording() {
    val name = audio.startRecording()
    toast(if (name != null) "Recording — strokes will replay from here" else "Could not open the microphone")
}

/**
 * Adopt a newly picked audio folder, holding onto the grant across restarts so sidecars keep
 * resolving, and immediately pull in whatever the open document references.
 */
internal fun MainActivity.adoptAudioFolder(uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
    audioFolder = uri
    settingsStore.save(settingsStore.load().copy(audioFolderUri = uri.toString()))
    audioTick.value++
    pullAudioSidecars()
}

/** Copy the sidecars the open document references out of the nominated folder, so they replay. */
internal fun MainActivity.pullAudioSidecars() {
    val folder = audioFolder ?: return
    val doc = surface?.toDocument() ?: return
    val pulled = audio.importSidecars(folder, doc)
    val missing = audio.missingFor(doc).size
    if (pulled > 0) toast("Loaded $pulled recording(s)")
    else if (missing > 0) toast("$missing recording(s) referenced but not in the audio folder")
}

/** Copy the sidecars the open document references into the nominated folder, beside the .xopp. */
internal fun MainActivity.pushAudioSidecars() {
    val folder = audioFolder ?: return
    val doc = surface?.toDocument() ?: return
    audio.exportSidecars(folder, doc)
}
