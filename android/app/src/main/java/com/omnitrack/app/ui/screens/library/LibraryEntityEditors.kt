package com.omnitrack.app.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.omnitrack.app.data.model.AuthorDto
import com.omnitrack.app.data.model.PlatformDto
import com.omnitrack.app.data.model.SeriesDto
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.domain.model.toDisplayLabel
import com.omnitrack.app.utils.scraper.ExternalMediaScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SeriesFormData(
    val title: String,
    val rating: Int?
)

internal data class AuthorFormData(
    val name: String,
    val nativeName: String?,
    val imageUrl: String?,
    val bio: String?
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeriesEditorDialog(
    existing: SeriesDto?,
    derivedUserStatus: String?,
    derivedPublishingStatus: String?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (SeriesFormData) -> Unit
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var rating by remember { mutableStateOf(existing?.rating?.toString().orEmpty()) }
    val validRating = rating.isBlank() || rating.toIntOrNull()?.let { it in 1..10 } == true

    EditorDialog(
        title = if (existing == null) "Add series" else "Edit series",
        onDismiss = onDismiss,
        onDelete = onDelete,
        saveEnabled = title.isNotBlank() && validRating,
        onSave = {
            if (title.isNotBlank()) {
                onSave(
                    SeriesFormData(
                        title, rating.toIntOrNull()
                    )
                )
            }
        }
    ) {
        OutlinedTextField(title, { title = it }, label = { Text("Series name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (existing != null) {
            Text("Statuses are derived from works. Edit a work to change them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (derivedUserStatus != null && derivedPublishingStatus != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DerivedStatus("User status", UserStatus.fromValue(derivedUserStatus).label)
                    DerivedStatus("Publishing status", derivedPublishingStatus.toDisplayLabel())
                }
            } else {
                Text("No works yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = rating,
            onValueChange = { rating = it },
            label = { Text("Rating (optional)") },
            supportingText = { Text("Whole number from 1 to 10") },
            isError = !validRating,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
private fun DerivedStatus(label: String, status: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50)
    ) {
        Text("$label: $status", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun AuthorEditorDialog(
    existing: AuthorDto?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (AuthorFormData) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var nativeName by remember { mutableStateOf(existing?.nativeName.orEmpty()) }
    var imageUrl by remember { mutableStateOf(existing?.imageUrl.orEmpty()) }
    var bio by remember { mutableStateOf(existing?.bio.orEmpty()) }

    EditorDialog(
        title = if (existing == null) "Add author" else "Edit author",
        onDismiss = onDismiss,
        onDelete = onDelete,
        saveEnabled = name.isNotBlank(),
        onSave = {
            if (name.isNotBlank()) {
                onSave(AuthorFormData(name, nativeName.ifBlank { null }, imageUrl.ifBlank { null }, bio.ifBlank { null }))
            }
        }
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Author name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(nativeName, { nativeName = it }, label = { Text("Native name (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(imageUrl, { imageUrl = it }, label = { Text("Image URL (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(bio, { bio = it }, label = { Text("Biography (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    }
}

@Composable
internal fun <T> Selector(
    label: String,
    selected: String,
    options: List<T>,
    text: (T) -> String,
    onAdd: (() -> Unit)? = null,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            trailingIcon = {
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Open $label dropdown")
            },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text(option)) },
                    onClick = { expanded = false; onSelected(option) }
                )
            }
            if (onAdd != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Create new $label") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAdd()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorDialog(
    title: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
                if (onDelete != null) {
                    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(onClick = onSave, enabled = saveEnabled, modifier = Modifier.weight(1f)) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
internal fun PlatformEditorSheet(
    existing: PlatformDto?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var websiteUrl by remember { mutableStateOf(existing?.websiteUrl.orEmpty()) }
    var logoUrl by remember { mutableStateOf(existing?.logoUrl.orEmpty()) }
    var findingLogo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    EditorDialog(
        title = "Edit platform",
        onDismiss = onDismiss,
        onDelete = onDelete,
        saveEnabled = name.isNotBlank(),
        onSave = {
            if (name.isNotBlank()) {
                onSave(name, websiteUrl.ifBlank { null }, logoUrl.ifBlank { null })
            }
        }
    ) {
        OutlinedTextField(
            name,
            { name = it },
            label = { Text("Platform name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            websiteUrl,
            { websiteUrl = it },
            label = { Text("Website URL (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedButton(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    findingLogo = true
                    val found = ExternalMediaScraper.findCoverImage(websiteUrl)
                    withContext(Dispatchers.Main) {
                        findingLogo = false
                        if (found != null) logoUrl = found
                    }
                }
            },
            enabled = websiteUrl.isNotBlank() && !findingLogo,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (findingLogo) "Finding logo…" else "Find logo from website")
        }
        if (logoUrl.isNotBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = "Platform logo preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(96.dp)
            )
        }
        OutlinedTextField(
            logoUrl,
            { logoUrl = it },
            label = { Text("Logo image URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}
