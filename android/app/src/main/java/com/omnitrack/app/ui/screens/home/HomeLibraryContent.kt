package com.omnitrack.app.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.omnitrack.app.domain.model.MediaEntry
import com.omnitrack.app.domain.model.MediaType
import com.omnitrack.app.domain.model.UserStatus
import com.omnitrack.app.ui.components.TrackedMediaRow
import com.omnitrack.app.utils.LibrarySortOption
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val currentUserStatus by rememberUpdatedState(selectedUserStatus)
    val selectStatus by rememberUpdatedState(onStatusSelected)
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    var pageWidth by remember { mutableIntStateOf(0) }
    var swipeGeneration by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isSearching, selectedUserStatus, selectedTabMediaType) {
                if (!isSearching) {
                    var offsetJob: Job? = null
                    try {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.position
                            val gestureStatus = currentUserStatus
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, start)
                            var lastPosition = start
                            var baseOffset = 0f
                            var claimed = false
                            var cancelled = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.find { it.id == down.id }
                                if (change == null || change.isConsumed ||
                                    currentUserStatus != gestureStatus ||
                                    event.changes.any { it.id != down.id && it.pressed }
                                ) {
                                    cancelled = true
                                    break
                                }
                                lastPosition = change.position
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val dx = lastPosition.x - start.x
                                val dy = lastPosition.y - start.y
                                val lockThreshold = viewConfiguration.touchSlop
                                if (!claimed && abs(dy) > lockThreshold && abs(dy) >= abs(dx)) {
                                    break
                                }
                                if (!claimed && pageWidth > 0 && change.pressed &&
                                    abs(dx) > lockThreshold && abs(dx) > abs(dy) * 1.25f
                                ) {
                                    claimed = true
                                    offsetJob?.cancel()
                                    baseOffset = dragOffsetPx
                                }
                                if (claimed) {
                                    change.consume()
                                    val rawOffset = (baseOffset + dx).coerceIn(
                                        -pageWidth.toFloat(), pageWidth.toFloat()
                                    )
                                    val atEdge = (rawOffset > 0f && gestureStatus.ordinal == 0) ||
                                        (rawOffset < 0f && gestureStatus.ordinal == UserStatus.entries.lastIndex)
                                    val offset = if (atEdge) {
                                        rawOffset * 0.3f / (1f + abs(rawOffset) / 80.dp.toPx())
                                    } else {
                                        rawOffset
                                    }
                                    dragOffsetPx = offset
                                }
                            } while (change.pressed)

                            if (claimed) {
                                val travel = baseOffset + lastPosition.x - start.x
                                val velocity = velocityTracker.calculateVelocity().x
                                val flickThreshold = 600.dp.toPx()
                                val travelThreshold = minOf(80.dp.toPx(), pageWidth * 0.28f)
                                var targetStatus: UserStatus? = null
                                var targetOffset = 0f
                                if (!cancelled && currentUserStatus == gestureStatus &&
                                    (abs(travel) >= travelThreshold || abs(velocity) >= flickThreshold)
                                ) {
                                    val movement = if (abs(velocity) >= flickThreshold) velocity else travel
                                    val direction = if (movement < 0f) 1 else -1
                                    val index = (gestureStatus.ordinal + direction)
                                        .coerceIn(0, UserStatus.entries.lastIndex)
                                    if (index != gestureStatus.ordinal) {
                                        targetStatus = UserStatus.entries[index]
                                        targetOffset = -direction * pageWidth.toFloat()
                                    }
                                }
                                offsetJob?.cancel()
                                val commitStatus = targetStatus
                                offsetJob = dragScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    Animatable(dragOffsetPx).animateTo(targetOffset) {
                                        dragOffsetPx = value
                                    }
                                    if (commitStatus != null && currentUserStatus == gestureStatus) {
                                        Snapshot.withMutableSnapshot {
                                            swipeGeneration++
                                            selectStatus(commitStatus)
                                            dragOffsetPx = 0f
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        offsetJob?.cancel()
                        dragOffsetPx = 0f
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
                .clipToBounds()
                .onSizeChanged { pageWidth = it.width }
        ) {
            val page: @Composable (MediaType, UserStatus) -> Unit = { mediaType, status ->
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

                Box(modifier = Modifier.fillMaxSize()) {
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
                                        entry = if (entry.seriesId.isNullOrBlank() && entry.seriesTitle.isNullOrBlank()) {
                                            entry.copy(sequenceNumber = null)
                                        } else {
                                            entry
                                        },
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
            val currentPage by rememberUpdatedState(page)
            val pages = remember {
                UserStatus.entries.associateWith { status ->
                    movableContentOf<MediaType> { mediaType -> currentPage(mediaType, status) }
                }
            }

            key(swipeGeneration) {
                AnimatedContent(
                    targetState = selectedTabMediaType to selectedUserStatus,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(dragOffsetPx.roundToInt(), 0) },
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
                    pages.getValue(status)(mediaType)
                }
            }

            val offset = dragOffsetPx
            val adjacentIndex = when {
                offset > 0f -> selectedUserStatus.ordinal - 1
                offset < 0f -> selectedUserStatus.ordinal + 1
                else -> -1
            }
            if (!isSearching && adjacentIndex in UserStatus.entries.indices) {
                val pageOffset = if (offset > 0f) -pageWidth else pageWidth
                val adjacentStatus = UserStatus.entries[adjacentIndex]
                key(adjacentStatus) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset { IntOffset((dragOffsetPx + pageOffset).roundToInt(), 0) }
                    ) {
                        pages.getValue(adjacentStatus)(selectedTabMediaType)
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
