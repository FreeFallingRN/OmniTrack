package com.omnitrack.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun <S, F> SortFilterDialog(
    sortItems: List<S>,
    sortLabel: (S) -> String,
    selectedSort: S,
    sortDescending: Boolean,
    onSortSelected: (S) -> Unit,
    filterTitle: String,
    filterItems: List<F>,
    filterLabel: (F) -> String,
    selectedFilter: F,
    onFilterSelected: (F) -> Unit,
    onDismissRequest: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 460.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color.Black,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 16.dp)
            ) {
                Column(Modifier.weight(1.1f).selectableGroup()) {
                    Text(
                        "Sort by",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    sortItems.forEach { item ->
                        val selected = item == selectedSort
                        SortFilterItem(
                            label = sortLabel(item),
                            selected = selected,
                            indicator = if (selected) {
                                if (sortDescending) "↓" else "↑"
                            } else null,
                            direction = if (selected) {
                                if (sortDescending) "Descending" else "Ascending"
                            } else null,
                            onClick = { onSortSelected(item) }
                        )
                    }
                }
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Column(Modifier.weight(1f).selectableGroup()) {
                    Text(
                        filterTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    filterItems.forEach { item ->
                        SortFilterItem(
                            label = filterLabel(item),
                            selected = item == selectedFilter,
                            onClick = { onFilterSelected(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortFilterItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    indicator: String? = null,
    direction: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { if (direction != null) stateDescription = direction }
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (selected) {
            if (indicator != null) {
                Text(indicator, color = MaterialTheme.colorScheme.primary)
            } else {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
