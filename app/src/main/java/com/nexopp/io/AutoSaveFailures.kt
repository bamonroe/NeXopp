package com.nexopp.io

/**
 * How many autosaves in a row have been refused by the same target, kept pure so the "say something
 * now" decision can be unit-tested away from `MainActivity` (which can't be).
 *
 * An autosave that fails is silent by design — [AutoSaveTimer] backs off and tries again, which is
 * the right answer to a transient hiccup and the wrong one to a target that will never accept a
 * write. A provider that hands back a read-only descriptor refuses every retry identically, so
 * without this the document simply stays dirty and nobody is told until the user saves deliberately.
 *
 * The rule is *once per target*: after [threshold] consecutive failures against the same URI, one
 * report goes out and the counter goes quiet again. It only speaks up a second time if the target
 * changes (a different tab, or a Save As somewhere new) or a save succeeds and then fails afresh —
 * so a file that is genuinely unwritable costs one snackbar, not one every interval.
 */
class AutoSaveFailures(private val threshold: Int = 3) {
    private var target: String? = null
    private var failures = 0
    private var reported = false

    /**
     * An autosave to [uri] failed. Returns true if this is the failure worth telling the user
     * about — the [threshold]th in a row against this target, and the first one reported for it.
     */
    fun noteFailure(uri: String): Boolean {
        if (uri != target) reset(uri)
        failures++
        if (reported || failures < threshold) return false
        reported = true
        return true
    }

    /** A save landed: the target is writable after all, so start counting from scratch. */
    fun noteSaved() = reset(null)

    private fun reset(uri: String?) {
        target = uri
        failures = 0
        reported = false
    }
}
