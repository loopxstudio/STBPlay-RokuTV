package com.stbplay.app

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import com.stbplay.app.data.db.PlaylistEntity

@Composable
internal fun ProfileDeleteDialog(profile: PlaylistEntity, onDelete: () -> Unit, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete ${profile.name}?") },
        text = { Text("This removes this profile, its cached catalog and favorites from this device.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(modifier = Modifier.focusProperties { canFocus = true }.focusRequester(cancelFocus), onClick = onCancel) { Text("Cancel") } }
    )
    LaunchedEffect(profile.id) { cancelFocus.requestFocus() }
}
