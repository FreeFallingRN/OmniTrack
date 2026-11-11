package com.omnitrack.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.Series
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.ui.SeriesViewModel
import com.omnitrack.app.ui.components.MediaDetailSheet
import com.omnitrack.app.ui.components.TrackedMediaRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniTrackHomeScreen(
    viewModel: SeriesViewModel,
    onTogglePinNotification: (Series) -> Unit
) {
    val seriesList by viewModel.seriesList.collectAsState()

    var selectedTabMediaType by remember { mutableStateOf(MediaType.NOVEL) }
    var selectedUserStatus by remember { mutableStateOf(UserStatus.IN_PROGRESS) }
    var selectedSeriesForDetail by remember { mutableStateOf<Series?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OmniTrack") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // MediaType Filter Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTabMediaType.ordinal,
                edgePadding = 0.dp
            ) {
                MediaType.entries.forEach { mediaType ->
                    Tab(
                        selected = selectedTabMediaType == mediaType,
                        onClick = { selectedTabMediaType = mediaType },
                        text = { Text(mediaType.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // UserStatus Filter Chips / Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UserStatus.entries.forEach { status ->
                    FilterChip(
                        selected = selectedUserStatus == status,
                        onClick = { selectedUserStatus = status },
                        label = { Text(status.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Filtered List
            val filteredList = seriesList.filter {
                it.type == selectedTabMediaType && it.userStatus == selectedUserStatus
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredList, key = { it.id }) { series ->
                    TrackedMediaRow(
                        series = series,
                        onClick = { selectedSeriesForDetail = series },
                        onToggleActiveTarget = {
                            viewModel.toggleActiveTarget(series.id, !series.isActiveTarget)
                            onTogglePinNotification(series)
                        },
                        onIncrement = {
                            viewModel.updateProgress(series.id, series.currentCumulativeUnit + 1.0)
                        }
                    )
                }
            }
        }
    }

    // Detail Bottom Sheet
    selectedSeriesForDetail?.let { series ->
        MediaDetailSheet(
            series = series,
            onDismiss = { selectedSeriesForDetail = null },
            onUpdateProgress = { newProgress ->
                viewModel.updateProgress(series.id, newProgress)
            },
            onToggleActiveTarget = { isActive ->
                viewModel.toggleActiveTarget(series.id, isActive)
            },
            onDelete = {
                viewModel.deleteSeries(series.id)
                selectedSeriesForDetail = null
            }
        )
    }
}