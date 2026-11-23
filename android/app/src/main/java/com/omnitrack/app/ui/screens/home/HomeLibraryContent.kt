package com.omnitrack.app.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.ui.components.TrackedMediaRow
import com.omnitrack.app.utils.LibrarySortOption
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLibraryContent(
    mediaEntries: List<MediaEntry>,
    isRefreshing: Boolean,
    selectedTabMediaType: MediaType,
    onMediaTypeSelected: (MediaType) -> Unit,
    selectedUserStatus: UserStatus,
    onStatusSelected: (UserStatus) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    sortOption: LibrarySortOption,
    onRefresh: () -> Unit,
    onSelectEntry: (MediaEntry) -> Unit
) {
    val isSearching = searchQuery.isNotBlank()
    val currentMediaType by rememberUpdatedState(selectedTabMediaType)
    val currentUserStatus by rememberUpdatedState(selectedUserStatus)
    val selectMediaType by rememberUpdatedState(onMediaTypeSelected)
    val selectStatus by rememberUpdatedState(onStatusSelected)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isSearching) {
                if (!isSearching) {
                    val thresholdPx = 72.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        val upperHalf = start.y < size.height / 2f
                        var lastPosition = start
                        var claimed = false
                        var cancelled = false
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.find { it.id == down.id }
                            if (change == null || event.changes.any { it.id != down.id && it.pressed }) {
                                cancelled = true
                                break
                            }
                            if (change.isConsumed && !claimed) {
                                cancelled = true
                                break
                            }
                            lastPosition = change.position
                            val dx = lastPosition.x - start.x
                            val dy = lastPosition.y - start.y
                            if (!claimed && abs(dy) > viewConfiguration.touchSlop &&
                                abs(dx) <= abs(dy) * 1.5f
                            ) {
                                cancelled = true
                                break
                            }
                            if (!claimed && change.pressed &&
                                abs(dx) > thresholdPx && abs(dx) > abs(dy) * 1.5f
                            ) {
                                claimed = true
                            }
                            if (claimed) change.consume()
                        } while (change.pressed)

                        val dx = lastPosition.x - start.x
                        val dy = lastPosition.y - start.y
                        if (!cancelled && claimed &&
                            abs(dx) > thresholdPx && abs(dx) > abs(dy) * 1.5f
                        ) {
                            val direction = if (dx < 0f) 1 else -1
                            if (upperHalf) {
                                val index = (currentMediaType.ordinal + direction)
                                    .coerceIn(0, MediaType.entries.lastIndex)
                                if (index != currentMediaType.ordinal) {
                                    selectMediaType(MediaType.entries[index])
                                }
                            } else {
                                val index = (currentUserStatus.ordinal + direction)
                                    .coerceIn(0, UserStatus.entries.lastIndex)
                                if (index != currentUserStatus.ordinal) {
                                    selectStatus(UserStatus.entries[index])
                                }
                            }
                        }
                    }
                }
            }
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            placeholder = {
                Text(
                    "Search library...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChanged("") }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                unfocusedBorderColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (isSearching) {
            Text(
                text = "Searching all types & statuses",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            ScrollableTabRow(
                selectedTabIndex = selectedTabMediaType.ordinal,
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
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
                        onClick = { onMediaTypeSelected(mediaType) },
                        text = {
                            Text(
                                text = mediaType.label,
                                fontWeight = if (selectedTabMediaType == mediaType) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                                color = if (selectedTabMediaType == mediaType) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            AnimatedContent(
                targetState = selectedTabMediaType to selectedUserStatus,
                transitionSpec = {
                    val forward = if (targetState.first != initialState.first) {
                        targetState.first.ordinal > initialState.first.ordinal
                    } else {
                        targetState.second.ordinal > initialState.second.ordinal
                    }
                    if (forward) {
                        slideInHorizontally { it } + fadeIn() togetherWith
                            (slideOutHorizontally { -it } + fadeOut())
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith
                            (slideOutHorizontally { it } + fadeOut())
                    }
                },
                label = "library filter transition"
            ) { (mediaType, status) ->
                val filteredList = mediaEntries.filter { entry ->
                    if (isSearching) {
                        entry.title.contains(searchQuery, ignoreCase = true) ||
                            entry.seriesTitle?.contains(searchQuery, ignoreCase = true) == true ||
                            entry.alternativeTitles.any { it.contains(searchQuery, ignoreCase = true) } ||
                            entry.authors.any { it.contains(searchQuery, ignoreCase = true) } ||
                            entry.creators.any { it.name.contains(searchQuery, ignoreCase = true) }
                    } else {
                        entry.type == mediaType && entry.userStatus == status
                    }
                }.let { list ->
                    when (sortOption) {
                        LibrarySortOption.LAST_UPDATED -> list.sortedByDescending { it.updatedAt }
                        LibrarySortOption.ALPHABETICAL -> list.sortedBy { it.title.lowercase() }
                        LibrarySortOption.READ_CHAPTERS -> list.sortedByDescending { it.currentUnit }
                    }
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
                            Column {
                                TrackedMediaRow(
                                    entry = entry,
                                    onClick = { onSelectEntry(entry) }
                                )
                                if (isSearching) {
                                    Text(
                                        text = "${entry.type.singularLabel} | ${entry.userStatus.label}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .clickable { onSelectEntry(entry) }
                                            .padding(start = 71.dp, bottom = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeLibraryStatusFilter(
    selectedUserStatus: UserStatus,
    onStatusSelected: (UserStatus) -> Unit
) {
    ScrollableTabRow(
        selectedTabIndex = selectedUserStatus.ordinal,
        edgePadding = 0.dp,
        containerColor = Color.Transparent,
        indicator = { tabPositions ->
            if (selectedUserStatus.ordinal < tabPositions.size) {
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(tabPositions[selectedUserStatus.ordinal])
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .zIndex(-1f)
                )
            }
        },
        divider = {}
    ) {
        UserStatus.entries.forEach { status ->
            Box(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onStatusSelected(status) }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selectedUserStatus == status) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                    fontWeight = if (selectedUserStatus == status) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Normal
                    }
                )
            }
        }
    }
}
