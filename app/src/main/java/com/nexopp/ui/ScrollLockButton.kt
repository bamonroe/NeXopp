package com.nexopp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nexopp.render.ScrollLock

/**
 * The scroll-lock control in the top bar, sitting beside undo/redo because it is reached with the
 * same "stop what just happened" reflex.
 *
 * It behaves like the rail's grouped tool buttons: a **tap cycles** off → horizontal → vertical → off
 * so the lock can be thrown mid-annotation without looking, and a **long press** opens the menu that
 * names the three modes outright, for when the icon alone isn't enough. The icon shows the travel a
 * *one-finger* pan can still make ([ScrollLock.icon]) and tints itself while any lock is on, so the
 * button doubles as the indicator that the document isn't refusing to move for some other reason.
 * A two-finger pan ignores the lock entirely, so the page is always reachable.
 *
 * The setting itself lives in [AppSettings.scrollLock]; this only hands the next value up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScrollLockButton(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val lock = settings.scrollLock
    val tint =
        if (lock == ScrollLock.NONE) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.primary
    Box {
        Box(
            modifier = Modifier
                .size(BUTTON_SIZE)
                .clip(CircleShape)
                .then(
                    if (lock == ScrollLock.NONE) Modifier
                    else Modifier.background(MaterialTheme.colorScheme.primaryContainer),
                )
                .combinedClickable(
                    onClick = { onChange(settings.copy(scrollLock = lock.next())) },
                    onLongClick = { open = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(lock.icon(), contentDescription = lock.description, tint = tint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeading("Scroll lock")
            for (mode in ScrollLock.entries) {
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    leadingIcon = { Icon(mode.icon(), contentDescription = null) },
                    trailingIcon = {
                        if (mode == lock) Icon(Icons.Filled.Check, contentDescription = "selected")
                    },
                    onClick = { open = false; onChange(settings.copy(scrollLock = mode)) },
                )
            }
        }
    }
}

/** Matches the `IconButton`s either side of it in the top bar. */
private val BUTTON_SIZE = 48.dp
