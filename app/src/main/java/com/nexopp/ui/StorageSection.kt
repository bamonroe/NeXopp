package com.nexopp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Storage budgets: the largest text import allowed, and how much background-PDF cache to keep. */
@Composable
fun StorageSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    OptionGroup(
        title = "Text import limit",
        subtitle = "Opening a text file typesets all of it into a PDF, so a huge log takes minutes " +
            "and thousands of pages. Files over this size are refused with a message instead.",
        options = AppSettings.TEXT_IMPORT_LIMIT_CHOICES,
        selected = settings.textImportLimitMb,
        label = { "$it MB" },
        onSelect = { onChange(settings.copy(textImportLimitMb = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    OptionGroup(
        title = "PDF cache limit",
        subtitle = "Generated and imported backgrounds are kept so reopening a file is instant. " +
            "Past this, the oldest unused ones are dropped; they regenerate on the next open.",
        options = AppSettings.PDF_CACHE_LIMIT_CHOICES,
        selected = settings.pdfCacheLimitMb,
        label = { "$it MB" },
        onSelect = { onChange(settings.copy(pdfCacheLimitMb = it)) },
    )
}
