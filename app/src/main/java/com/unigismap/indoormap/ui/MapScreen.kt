package com.unigismap.indoormap.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unigismap.indoormap.map.FloorMap
import com.unigismap.indoormap.map.FloorPlan
import com.unigismap.indoormap.map.Room
import com.unigismap.indoormap.map.rememberMapState
import kotlinx.coroutines.launch

/**
 * Floor plan of one building. [floors] are the labels of the building's floors with plans
 * ([floorIndex] is shown); [onBack] returns to the campus map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    plan: FloorPlan,
    floors: List<String>,
    floorIndex: Int,
    onFloorSelected: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val mapState = rememberMapState(plan.bounds)
    val scope = rememberCoroutineScope()
    var selectedRoom by remember(plan) { mutableStateOf<Room?>(null) }
    var showSearch by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("UniGISMap", style = MaterialTheme.typography.titleLarge)
                        Text(
                            plan.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "К карте кампуса")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Поиск помещения")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = { MapControls(mapState, fitDescription = "Показать весь этаж") },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            FloorMap(
                plan = plan,
                state = mapState,
                selectedRoom = selectedRoom,
                onRoomClick = { selectedRoom = it },
                modifier = Modifier.fillMaxSize(),
            )

            if (floors.size > 1) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    floors.forEachIndexed { i, label ->
                        FilterChip(
                            selected = i == floorIndex,
                            onClick = { onFloorSelected(i) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = selectedRoom != null,
                enter = slideInVertically { it * 2 },
                exit = slideOutVertically { it * 2 },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 88.dp, bottom = 16.dp),
            ) {
                // Keep showing the last room while the card animates out.
                var lastRoom by remember { mutableStateOf<Room?>(null) }
                selectedRoom?.let { lastRoom = it }
                lastRoom?.let { room ->
                    RoomCard(
                        room = room,
                        onFocus = { scope.launch { mapState.animateFocus(room.bounds) } },
                        onClose = { selectedRoom = null },
                    )
                }
            }
        }
    }

    if (showSearch) {
        RoomSearchSheet(
            search = plan::search,
            onDismiss = { showSearch = false },
            onRoomSelected = { room ->
                showSearch = false
                selectedRoom = room
                scope.launch { mapState.animateFocus(room.bounds) }
            },
        )
    }
}

@Composable
private fun RoomCard(room: Room, onFocus: () -> Unit, onClose: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(room.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${room.category.title} · ${"%.1f".format(room.area)} м²",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть")
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            AssistChip(
                onClick = onFocus,
                label = { Text("Показать на карте") },
                leadingIcon = { Icon(Icons.Outlined.CenterFocusWeak, contentDescription = null) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomSearchSheet(
    search: (String) -> List<Room>,
    onDismiss: () -> Unit,
    onRoomSelected: (Room) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) { search(query) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("Название или номер") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(filtered, key = { it.id }) { room ->
                ListItem(
                    headlineContent = { Text(room.name) },
                    supportingContent = { Text("${room.category.title} · ${"%.1f".format(room.area)} м²") },
                    leadingContent = { Icon(Icons.Default.MeetingRoom, contentDescription = null) },
                    modifier = Modifier.clickable { onRoomSelected(room) },
                )
            }
        }
    }
}
