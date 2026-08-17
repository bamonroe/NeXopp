package com.nexopp.io

import android.os.Handler
import android.os.Looper

/**
 * Drives the two autosave timers described by [AutoSavePolicy], on the main thread.
 *
 * The activity feeds it three facts — an edit happened ([noteEdit]), a save landed ([noteSaved]),
 * the settings changed ([configure]) — and it calls [onDue] back when a write is owed. All of the
 * *when* lives in [AutoSavePolicy]; this class is only the clock and the `postDelayed` re-arming,
 * which is why the policy is what the unit tests exercise.
 *
 * One pending callback exists at a time: every state change cancels it and re-arms from the new
 * state, so a burst of strokes costs one message, not one per stroke.
 *
 * @param onDue Invoked on the main thread when an autosave is owed. The caller decides whether it
 *   can actually be honoured — [com.nexopp.MainActivity] skips a tab with no writable target rather
 *   than throwing a file picker at someone who is mid-sentence.
 * @param now The clock, injectable for tests; wall-clock milliseconds.
 */
class AutoSaveTimer(
    private val onDue: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val handler = Handler(Looper.getMainLooper())

    /** The user's two timer settings. Off until [configure] says otherwise. */
    private var policy: AutoSavePolicy = AutoSavePolicy.OFF

    /** When the document was last edited, or null if it hasn't been since the last [noteSaved]. */
    private var lastEditMs: Long? = null

    /** When a save last landed — the baseline the interval timer counts from. */
    private var lastSaveMs: Long = now()

    /** When [onDue] last fired, so a save that keeps failing retries on a floor, not in a spin. */
    private var lastFireMs: Long = 0L

    /** Holds a save that comes due mid-stroke until the pen lifts. */
    private val gate = AutoSaveGate()

    private val tick = Runnable {
        if (gate.request()) fire()
        // Re-arm regardless of what the save did: on success [noteSaved] has already reset the
        // baseline and this schedules nothing, and on failure the backoff floor below applies.
        schedule()
    }

    private fun fire() {
        lastFireMs = now()
        onDue()
    }

    /**
     * Tell the timer whether a draw/erase gesture is on the canvas. An autosave that comes due
     * while the pen is down is held and runs the instant the stroke ends, so a save never lands on
     * a half-drawn document.
     */
    fun setStrokeInProgress(active: Boolean) {
        if (gate.setStrokeInProgress(active)) fire()
        schedule()
    }

    /** Adopt [next] (from the settings screen) and re-arm against it. */
    fun configure(next: AutoSavePolicy) {
        policy = next
        schedule()
    }

    /** Record that the document just changed, restarting the idle countdown. */
    fun noteEdit() {
        lastEditMs = now()
        schedule()
    }

    /** Record that the document just landed on disk: it is clean, and the interval starts over. */
    fun noteSaved() {
        lastSaveMs = now()
        lastEditMs = null
        schedule()
    }

    /**
     * Reset to a freshly-loaded, clean document — a tab switch or an open. Without this the incoming
     * document would inherit the outgoing one's dirty state and get written straight back out.
     */
    fun reset() {
        lastSaveMs = now()
        lastEditMs = null
        lastFireMs = 0L
        schedule()
    }

    /** Drop any pending autosave. The timer stays configured; [arm] or the next edit brings it back. */
    fun cancel() = handler.removeCallbacks(tick)

    /**
     * Re-arm against the state as it stands — how the activity picks the timers back up on resume
     * after [cancel]. Without it a document left dirty on the way out would sit unsaved until the
     * next stroke happened to restart the countdown.
     */
    fun arm() = schedule()

    /** Cancel the pending callback and post a new one, if the policy says a save is owed at all. */
    private fun schedule() {
        handler.removeCallbacks(tick)
        // A save already held for the end of the stroke needs no timer: the pen lift releases it.
        // Re-arming here would instead spin on an always-overdue policy for the length of the stroke.
        if (gate.isPending) return
        if (!policy.isEnabled) return
        val at = now()
        val due = policy.delayUntilDue(at, lastEditMs, lastSaveMs) ?: return
        // A save that failed leaves the document dirty, so the policy would say "due" on every
        // re-arm; this keeps a broken target (a revoked grant, a full disk) to one attempt a minute
        // instead of a tight loop of failing writes and toasts.
        val floor = (lastFireMs + RETRY_BACKOFF_MS - at).coerceAtLeast(0L)
        handler.postDelayed(tick, maxOf(due, floor))
    }

    private companion object {
        /** Shortest gap between two autosave attempts when the document stays dirty (a failing save). */
        const val RETRY_BACKOFF_MS = 60_000L
    }
}
