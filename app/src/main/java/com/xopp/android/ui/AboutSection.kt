package com.xopp.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xopp.android.BuildConfig

/** Where the About page sends people; kept in one place so a link is never spelled out twice. */
object AboutLinks {
    const val SOURCE = "https://github.com/bamonroe/NeXopp"
    const val LICENSE = "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html"
    const val SUPPORT = "https://www.patreon.com/bamonroe"
    const val XOURNALPP = "https://github.com/xournalpp/xournalpp"
}

/**
 * About: which build this is (version name, version code, and the git commit it was built from),
 * the licence it ships under, where the source lives, and a nudge to support the work. Every row
 * that points outward opens the link in the system browser.
 */
@Composable
fun AboutSection() {
    val context = LocalContext.current
    val open: (String) -> Unit = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Text("Xopp", style = MaterialTheme.typography.titleLarge)
    Text(
        "A stylus-first Android reader and editor for Xournal++ documents.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 12.dp),
    )

    InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    InfoRow("Git commit", BuildConfig.GIT_COMMIT)
    HorizontalDivider(Modifier.padding(vertical = 12.dp))

    Text("Licence", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Xopp is free software under the GNU General Public License, version 2 or later — the same " +
            "licence as Xournal++ itself. You may use, study, share, and modify it; if you " +
            "distribute a modified version, it must stay free under the same terms and ship its " +
            "source. It comes with no warranty. The full text is in the LICENSE file in the source.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    LinkRow("Read the GPL v2", AboutLinks.LICENSE, open)
    LinkRow("Xournal++, the desktop app", AboutLinks.XOURNALPP, open)
    HorizontalDivider(Modifier.padding(vertical = 12.dp))

    Text("Source", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Xopp is developed in the open. Bug reports and patches are welcome.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    LinkRow("github.com/bamonroe/NeXopp", AboutLinks.SOURCE, open)
    HorizontalDivider(Modifier.padding(vertical = 12.dp))

    Text("Buy me a coffee", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Xopp is written in spare hours and given away for free. If it saves you some, consider " +
            "chipping in — it keeps the work going.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    LinkRow("patreon.com/bamonroe", AboutLinks.SUPPORT, open)
}

/** A read-only "label … value" line, for facts about the build. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

/** A tappable line that hands its URL to the system browser. */
@Composable
private fun LinkRow(label: String, url: String, open: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable { open(url) }.padding(vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
