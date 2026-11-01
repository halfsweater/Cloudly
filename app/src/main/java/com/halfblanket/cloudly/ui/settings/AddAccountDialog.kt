package com.halfblanket.cloudly.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.halfblanket.cloudly.data.db.DriveAccess
import com.halfblanket.cloudly.ui.theme.CloudlyTheme

/** Lets the user choose how much Drive access to grant before Google's consent screen appears. */
@Composable
fun AddAccountDialog(onConfirm: (DriveAccess) -> Unit, onDismiss: () -> Unit) {
    var access by rememberSaveable { mutableStateOf(DriveAccess.FULL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Connect a Google account") },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(
                    "You'll pick an account and approve access on Google's own screen. Cloudly never sees your password.",
                    style = MaterialTheme.typography.bodyMedium, color = CloudlyTheme.extra.muted,
                )
                Spacer(Modifier.height(16.dp))
                AccessOption(
                    selected = access == DriveAccess.FULL,
                    title = "Full Drive access (recommended)",
                    body = "Browse, organise, transfer and clean up all files in this Drive.",
                    onClick = { access = DriveAccess.FULL },
                )
                AccessOption(
                    selected = access == DriveAccess.APP_FILES_ONLY,
                    title = "Only files created with Cloudly",
                    body = "Cloudly can't see your existing files — only what you upload or create here.",
                    onClick = { access = DriveAccess.APP_FILES_ONLY },
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(access) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AccessOption(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = CloudlyTheme.extra.muted)
        }
    }
}
