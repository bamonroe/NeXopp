package com.nexopp.io

/**
 * When an unsaved document is next due to be written back, given the user's two autosave timers.
 *
 * This is the whole decision, kept pure (no Android, no clock of its own) so it can be unit-tested
 * against a fabricated timeline; [AutoSaveTimer] is the thin Handler wrapper that actually ticks.
 *
 * The two timers answer different questions and run at once:
 * - **idle** — "you stopped writing, put it away": fires [idleSeconds] after the *last edit*, so a
 *   pause in the handwriting commits the work. Every fresh edit pushes it back.
 * - **interval** — "never lose more than this much": fires every [intervalSeconds] since the *last
 *   save*, regardless of whether the pen ever stops. It bounds worst-case loss during a long
 *   continuous session, which the idle timer alone never would.
 *
 * Either is disabled by setting it to `0`, which is the factory default for both — an autosave
 * writes over the user's real `.xopp`, so it is opt-in rather than a surprise.
 *
 * A save is only ever due when the document is **dirty** (edited since it was last written). With
 * nothing to write, both timers stay quiet rather than rewriting identical bytes on a loop.
 *
 * @property idleSeconds Seconds of no editing before an autosave fires; `0` disables the idle timer.
 * @property intervalSeconds Seconds between autosaves during continuous editing; `0` disables it.
 */
data class AutoSavePolicy(
    val idleSeconds: Int = 0,
    val intervalSeconds: Int = 0,
) {
    /** True when at least one of the two timers is switched on. */
    val isEnabled: Boolean get() = idleSeconds > 0 || intervalSeconds > 0

    /**
     * The wall-clock millisecond at which an autosave becomes due, or null when none is — either
     * both timers are off, or the document is clean.
     *
     * @param lastEditMs When the document was last edited, or null if it has never been edited.
     * @param lastSaveMs When the document was last written to disk (its load time, for a document
     *   opened and not yet saved).
     */
    fun dueAt(lastEditMs: Long?, lastSaveMs: Long): Long? {
        // Clean document: an edit strictly after the last save is what makes a write worth doing.
        if (lastEditMs == null || lastEditMs <= lastSaveMs) return null
        val idle = idleSeconds.takeIf { it > 0 }?.let { lastEditMs + it * MILLIS_PER_SECOND }
        val interval = intervalSeconds.takeIf { it > 0 }?.let { lastSaveMs + it * MILLIS_PER_SECOND }
        // Whichever comes first wins; the other simply gets re-armed off the save that just landed.
        return listOfNotNull(idle, interval).minOrNull()
    }

    /**
     * How long from [nowMs] until the next autosave, or null when none is due. Never negative: an
     * already-overdue save reports `0` so a caller scheduling a delay fires it immediately.
     */
    fun delayUntilDue(nowMs: Long, lastEditMs: Long?, lastSaveMs: Long): Long? =
        dueAt(lastEditMs, lastSaveMs)?.let { (it - nowMs).coerceAtLeast(0L) }

    companion object {
        private const val MILLIS_PER_SECOND = 1000L

        /** Both timers off — the factory default, since autosave overwrites the user's own file. */
        val OFF = AutoSavePolicy()

        /** The idle-timer values the Autosave settings section offers, in seconds (`0` = off). */
        val IDLE_CHOICES: List<Int> = listOf(0, 5, 15, 30, 60, 300)

        /** The interval-timer values the Autosave settings section offers, in seconds (`0` = off). */
        val INTERVAL_CHOICES: List<Int> = listOf(0, 60, 120, 300, 600, 900)

        /** Human label for a timer value — "Off", or a whole number of seconds/minutes. */
        fun label(seconds: Int): String = when {
            seconds <= 0 -> "Off"
            seconds < 60 -> "$seconds seconds"
            seconds == 60 -> "1 minute"
            seconds % 60 == 0 -> "${seconds / 60} minutes"
            else -> "$seconds seconds"
        }
    }
}
