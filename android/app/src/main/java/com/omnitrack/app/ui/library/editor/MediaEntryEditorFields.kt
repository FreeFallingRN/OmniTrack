package com.omnitrack.app.ui.library.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun EntryField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Decimal
) {
    OutlinedTextField(
        value,
        onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true
    )
}

@Composable
internal fun StatusButtonGroup(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    compact: Boolean = false,
    enabled: Boolean = true,
    onSelected: (String) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected, options) {
        val index = options.indexOfFirst { it.first == selected }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(options, key = { _, option -> option.first }) { _, (value, text) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    enabled = enabled,
                    modifier = if (compact) Modifier.height(36.dp) else Modifier,
                    label = {
                        if (compact) {
                            Text(text, style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(text)
                        }
                    }
                )
            }
        }
    }
}
