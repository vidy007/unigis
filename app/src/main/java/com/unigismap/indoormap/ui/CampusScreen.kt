package com.unigismap.indoormap.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unigismap.indoormap.core.BuildingInfo
import com.unigismap.indoormap.core.CampusColors
import com.unigismap.indoormap.map.CampusBuilding
import com.unigismap.indoormap.map.CampusMap
import com.unigismap.indoormap.map.CampusPlan
import com.unigismap.indoormap.map.rememberMapState
import kotlinx.coroutines.launch

/**
 * Campus map with all buildings of the printed NSTU map. Only buildings with floor plans respond
 * to taps (their card opens the plans via [onOpenFloors]); the others are drawn for orientation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusScreen(plan: CampusPlan, onOpenFloors: (BuildingInfo) -> Unit) {
    val mapState = rememberMapState(plan.bounds, plan.viewRotation, cover = true)
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<CampusBuilding?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("UniGISMap", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Карта кампуса ${plan.name}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = { MapControls(mapState, fitDescription = "Показать весь кампус") },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            CampusMap(
                plan = plan,
                state = mapState,
                selected = selected,
                onBuildingClick = { selected = it },
                modifier = Modifier.fillMaxSize(),
            )

            if (plan.legend.isNotEmpty()) Legend(plan, Modifier.align(Alignment.TopStart).padding(12.dp))

            Text(
                plan.attribution,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, bottom = 4.dp),
            )

            AnimatedVisibility(
                visible = selected != null,
                enter = slideInVertically { it * 2 },
                exit = slideOutVertically { it * 2 },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 88.dp, bottom = 24.dp),
            ) {
                // Keep showing the last building while the card animates out.
                var last by remember { mutableStateOf<CampusBuilding?>(null) }
                selected?.let { last = it }
                last?.let { building ->
                    BuildingCard(
                        info = building.info,
                        onFocus = { scope.launch { mapState.animateFocus(building.bounds) } },
                        onOpenFloors = { onOpenFloors(building.info) },
                        onClose = { selected = null },
                    )
                }
            }
        }
    }
}

/** Colored square with a building number, as on the printed map. */
@Composable
fun NumberBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .widthIn(min = 24.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(CampusColors.ON_BADGE), style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Legend(plan: CampusPlan, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            plan.legend.forEach { (color, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(Color(color)))
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun subtitle(info: BuildingInfo): String = buildString {
    append(info.kind.title)
    if (info.levels > 0) append(" · этажей: ${info.levels}")
    if (info.floors.isNotEmpty()) append(" · планы: ${info.floorLevels.joinToString()} эт.")
}

@Composable
private fun BuildingCard(info: BuildingInfo, onFocus: () -> Unit, onOpenFloors: () -> Unit, onClose: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            if (info.number.isNotEmpty()) {
                NumberBadge(info.number, Color(CampusColors.of(info.kind)), Modifier.padding(top = 2.dp, end = 12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.titleMedium)
                Text(subtitle(info), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть")
            }
        }
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = onOpenFloors,
                label = { Text("Планы этажей") },
                leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null) },
            )
            AssistChip(
                onClick = onFocus,
                label = { Text("Показать") },
                leadingIcon = { Icon(Icons.Outlined.CenterFocusWeak, contentDescription = null) },
            )
        }
    }
}
