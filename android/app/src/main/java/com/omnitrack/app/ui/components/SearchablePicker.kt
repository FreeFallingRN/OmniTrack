package com.omnitrack.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun <T> SearchablePicker(
    label: String,
    selected: String,
    title: String,
    searchLabel: String,
    createLabel: String,
    options: List<T>,
    itemKey: (T) -> String,
    itemText: (T) -> String,
    selectedIds: Set<String>,
    onSelected: (T) -> Unit,
    onCreate: (String?) -> Unit,
    selectedFallback: String? = null,
    noneLabel: String? = null,
    noneSelected: Boolean = false,
    onClear: (() -> Unit)? = null,
    dismissOnSelection: Boolean = true,
    itemContent: (@Composable (T, Boolean) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { expanded = true })
    }

    if (expanded) {
        var search by remember { mutableStateOf("") }
        val query = search.trim()
        val filtered = remember(options, selectedIds, query, itemKey, itemText) {
            options.filter { itemKey(it) in selectedIds } +
                options.filter { itemKey(it) !in selectedIds && itemText(it).contains(query, ignoreCase = true) }
        }
        val canCreate = query.isNotEmpty() && options.none { itemText(it).trim().equals(query, ignoreCase = true) }
        val listState = rememberLazyListState()
        LaunchedEffect(query) { listState.scrollToItem(0) }
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 460.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color.Black,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(Modifier.heightIn(max = 560.dp).padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text(searchLabel) },
                        singleLine = true,
                        trailingIcon = {
                            if (search.isNotEmpty()) {
                                IconButton(onClick = { search = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).heightIn(max = 320.dp).fillMaxWidth(),
                        state = listState
                    ) {
                        if (selectedFallback != null) {
                            item {
                                DropdownMenuItem(
                                    text = { Text("$selectedFallback (selected)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                                    onClick = { expanded = false }
                                )
                            }
                        }
                        if (noneLabel != null && onClear != null) {
                            item {
                                DropdownMenuItem(
                                    text = { Text(if (noneSelected) "$noneLabel (selected)" else noneLabel) },
                                    trailingIcon = {
                                        if (noneSelected) Icon(Icons.Default.Check, contentDescription = null)
                                    },
                                    onClick = { expanded = false; if (!noneSelected) onClear() }
                                )
                            }
                        }
                        items(filtered, key = itemKey) { option ->
                            val isSelected = itemKey(option) in selectedIds
                            DropdownMenuItem(
                                text = {
                                    if (itemContent != null) {
                                        itemContent(option, isSelected)
                                    } else {
                                        val text = itemText(option)
                                        Text(
                                            if (isSelected) "$text (selected)" else text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                trailingIcon = {
                                    if (isSelected) Icon(Icons.Default.Check, contentDescription = null)
                                },
                                onClick = {
                                    if (dismissOnSelection) expanded = false
                                    onSelected(option)
                                }
                            )
                        }
                    }
                    if (canCreate) {
                        DropdownMenuItem(
                            text = { Text("Create \"$query\"", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = { expanded = false; onCreate(query) }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { expanded = false; onCreate(null) }) { Text(createLabel) }
                        TextButton(onClick = { expanded = false }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}
