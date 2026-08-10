package com.nexopp.io

/**
 * Picks the document URI out of an incoming launch intent — the handover another app makes when it
 * asks us to view or edit a file (a PDF, per the manifest's filters).
 *
 * Kept as a pure string-in/string-out helper rather than taking an [android.content.Intent] so the
 * routing rules are unit-testable on the JVM, with no Android framework stubs involved. The
 * activity does the `Intent` → strings unwrapping and the `Uri.parse` on the way back.
 */
object IncomingDocument {

    /** Actions that mean "here is a document, open it". MAIN/LAUNCHER is a plain cold start. */
    private val OPEN_ACTIONS = setOf(
        "android.intent.action.VIEW",
        "android.intent.action.EDIT",
        "android.intent.action.SEND",
    )

    /**
     * The URI to open, or null when the intent isn't a document handover.
     *
     * [data] is the intent's own data URI (how VIEW/EDIT hand a file over); [stream] is
     * `EXTRA_STREAM` (how SEND does). Data wins when both are set, since a sender that filled in
     * both meant the data URI as the subject.
     */
    fun uriString(action: String?, data: String?, stream: String? = null): String? {
        if (action !in OPEN_ACTIONS) return null
        return data?.takeIf { it.isNotBlank() } ?: stream?.takeIf { it.isNotBlank() }
    }
}
