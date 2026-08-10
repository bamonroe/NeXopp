package com.xopp.android.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A standardised up/down/delete control triplet for reorderable list rows.
 *
 * [vertical] chooses the orientation: true for vertical lists (up/down arrows), false for
 * horizontal lists (left/right). The move convention is a [delta]: -1 to move earlier, +1 to move
 * later. [canDelete] gates the delete button; [itemName] is read-only and used for accessibility.
 */
@Composable
internal fun ReorderControls(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean = true,
    vertical: Boolean = true,
    itemName: String,
    onMove: (delta: Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (vertical) {
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(Icons.Filled.ArrowDropUp, contentDescription = "Move $itemName up")
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Move $itemName down")
            }
        } else {
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Move $itemName earlier")
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Move $itemName later")
            }
        }
        IconButton(onClick = onDelete, enabled = canDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete $itemName")
        }
    }
}
