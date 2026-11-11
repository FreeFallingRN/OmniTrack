package com.omnitrack.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnitrack.app.domain.model.Series

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailSheet(
    series: Series,
    onDismiss: () -> Unit,
    onUpdateProgress: (Double) -> Unit,
    onToggleActiveTarget: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var progressInput by remember { mutableStateOf(series.currentCumulativeUnit.toString()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = series.title, style = MaterialTheme.typography.headlineSmall)
            Text(text = "Type: ${series.type.label} | Status: ${series.userStatus.label}")

            OutlinedTextField(
                value = progressInput,
                onValueChange = { progressInput = it },
                label = { Text("Update ${series.unitName}") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    progressInput.toDoubleOrNull()?.let { onUpdateProgress(it) }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Progress")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Pin Persistent Notification")
                Switch(
                    checked = series.isActiveTarget,
                    onCheckedChange = { onToggleActiveTarget(it) }
                )
            }

            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete Series")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}