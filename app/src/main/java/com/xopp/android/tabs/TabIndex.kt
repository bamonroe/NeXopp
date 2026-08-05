package com.xopp.android.tabs

import com.xopp.android.format.SaveFormat

/**
 * The session index — the small text file that says which tabs were open, in what order, and which
 * one was showing. The documents themselves live beside it as `.xopp` snapshots ([TabStore]).
 *
 * Deliberately a hand-rolled line format rather than JSON: it keeps the app dependency-free (the
 * same rule the `.xopp` XML layer follows), stays diff-friendly, and is pure Kotlin, so the encode
 * and decode halves are unit-tested without an Android device.
 *
 * Layout — the first line is the selection, then one tab per line, tab-separated:
 * ```
 * active<TAB>1
 * <id><TAB><title><TAB><uri><TAB><format><TAB><pdfPath><TAB><page><TAB><docKey>
 * ```
 * The trailing `docKey` was added after the format shipped, so a record without it is still read —
 * such a tab simply gets its own id as its key, which is exactly "a view of its own document".
 * Empty fields mean null. Field values are escaped ([escape]) so a title containing a tab or a
 * newline can never break the record structure.
 */
object TabIndex {

    private const val SEP = '\t'
    private const val ACTIVE = "active"

    /** Render [session]'s tab records (documents excluded) as the index text. */
    fun encode(session: TabSession): String = buildString {
        append(ACTIVE).append(SEP).append(session.activeIndex).append('\n')
        for (tab in session.tabs) {
            append(escape(tab.id)).append(SEP)
            append(escape(tab.title)).append(SEP)
            append(escape(tab.uri.orEmpty())).append(SEP)
            append(tab.format.name).append(SEP)
            append(escape(tab.pdfPath.orEmpty())).append(SEP)
            append(tab.page).append(SEP)
            append(escape(tab.docKey)).append('\n')
        }
    }

    /**
     * Parse index [text] back into tab records. Each tab's `document` is left as a placeholder the
     * caller fills from its snapshot file — a record whose snapshot is missing is dropped there, not
     * here. Malformed lines are skipped rather than failing the restore: a half-written index should
     * cost you a tab, not the session.
     */
    fun decode(text: String, placeholder: () -> com.xopp.android.format.model.Document): TabSession {
        var active = 0
        val tabs = ArrayList<OpenTab>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val f = line.split(SEP)
            if (f.firstOrNull() == ACTIVE) {
                active = f.getOrNull(1)?.toIntOrNull() ?: 0
                continue
            }
            if (f.size < 6) continue
            val id = unescape(f[0])
            if (id.isEmpty()) continue
            tabs += OpenTab(
                id = id,
                title = unescape(f[1]),
                document = placeholder(),
                uri = unescape(f[2]).ifEmpty { null },
                format = runCatching { SaveFormat.valueOf(f[3]) }.getOrDefault(SaveFormat.ORIGINAL),
                pdfPath = unescape(f[4]).ifEmpty { null },
                page = f[5].toIntOrNull() ?: 0,
                docKey = f.getOrNull(6)?.let(::unescape)?.ifEmpty { null } ?: id,
                hydrated = false,
            )
        }
        return TabSession(tabs, active.coerceIn(0, maxOf(0, tabs.size - 1)))
    }

    /** Escape the separators (and the escape character itself) so a field can hold any text. */
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    '\\' -> out.append('\\')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
