package com.omnitrack.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.ui.SeriesViewModel
import com.omnitrack.app.ui.components.MediaDetailSheet
import com.omnitrack.app.ui.components.TrackedMediaRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniTrackHomeScreen(
    viewModel: SeriesViewModel
) {
    val mediaEntries by viewModel.mediaEntries.collectAsState()

    var selectedTabMediaType by remember { mutableStateOf(MediaType.NOVEL) }
    var selectedUserStatus by remember { mutableStateOf(UserStatus.IN_PROGRESS) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedEntryForDetail by remember { mutableStateOf<MediaEntry?>(null) }
    var bottomNavIndex by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Status filter pills: only render at the bottom when on the Library screen (index 0)
                if (bottomNavIndex == 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserStatus.entries.forEach { status ->
                            val isSelected = selectedUserStatus == status
                            Surface(
                                shape = CircleShape,
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                onClick = { selectedUserStatus = status }
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = status.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                // Main bottom navigation bar row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val navItems = listOf(
                        Triple(0, Icons.Default.Home, "Library"),
                        Triple(1, Icons.Default.Search, "Explore"),
                        Triple(2, Icons.Default.DateRange, "History"),
                        Triple(3, Icons.Default.Settings, "Settings")
                    )

                    navItems.forEach { (index, icon, label) ->
                        val isSelected = bottomNavIndex == index
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .width(56.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .clickable { bottomNavIndex = index },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // Switch content based on active bottom nav tab index
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (bottomNavIndex) {
                0 -> {
                    // 0: Library View (Search bar, media tabs, and list)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // Search Bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search library...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Media Type Tabs (Novels, Comics, Anime, Movies)
                        ScrollableTabRow(
                            selectedTabIndex = selectedTabMediaType.ordinal,
                            edgePadding = 0.dp,
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            indicator = { tabPositions ->
                                if (selectedTabMediaType.ordinal < tabPositions.size) {
                                    TabRowDefaults.SecondaryIndicator(
                                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabMediaType.ordinal]),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            divider = {}
                        ) {
                            MediaType.entries.forEach { mediaType ->
                                Tab(
                                    selected = selectedTabMediaType == mediaType,
                                    onClick = { selectedTabMediaType = mediaType },
                                    text = {
                                        Text(
                                            text = mediaType.label,
                                            fontWeight = if (selectedTabMediaType == mediaType) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedTabMediaType == mediaType) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = {
                                refreshScope.launch {
                                    isRefreshing = true
                                    viewModel.refreshData()
                                    isRefreshing = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        ) {
                            // Filtered Media List
                            val filteredList = mediaEntries.filter { entry ->
                                val matchesType = entry.type == selectedTabMediaType
                                val matchesStatus = entry.userStatus == selectedUserStatus
                                val matchesSearch = searchQuery.isBlank() ||
                                    entry.title.contains(searchQuery, ignoreCase = true) ||
                                    entry.seriesTitle?.contains(searchQuery, ignoreCase = true) == true
                                matchesType && matchesStatus && matchesSearch
                            }

                            if (filteredList.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No entries found",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(filteredList, key = { it.id }) { entry ->
                                        TrackedMediaRow(
                                            entry = entry,
                                            onClick = { selectedEntryForDetail = entry },
                                            onToggleActiveTarget = {
                                                viewModel.toggleActiveTarget(entry.id, !entry.isActiveTarget)
                                            },
                                            onIncrement = {
                                                viewModel.updateProgress(entry.id, entry.currentUnit + 1.0)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // 1: Explore View
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Explore Screen", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                2 -> {
                    // 2: History View
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("History Screen", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                3 -> {
                    // 3: Settings View
                    OmniTrackSettingsScreen()
                }
            }
        }
    }

    selectedEntryForDetail?.let { entry ->
        MediaDetailSheet(
            entry = entry,
            relatedEntries = mediaEntries
                .filter { it.seriesId == entry.seriesId && it.id != entry.id }
                .sortedBy { it.sequenceNumber },
            onDismiss = { selectedEntryForDetail = null },
            onUpdateProgress = { newProgress -> viewModel.updateProgress(entry.id, newProgress) },
            onSelectEntry = { selectedEntryForDetail = it }
        )
    }
}