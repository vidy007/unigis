package com.unigismap.indoormap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.unigismap.indoormap.map.MapState
import kotlinx.coroutines.launch

/** Compass, zoom and fit buttons shared by the floor and campus screens. */
@Composable
fun MapControls(state: MapState, fitDescription: String) {
    val scope = rememberCoroutineScope()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SmallFloatingActionButton(onClick = { scope.launch { state.animateResetRotation() } }) {
            // Needle points to the map's "north" (top edge of a plan, true north on the campus).
            Icon(
                Icons.Default.Navigation,
                contentDescription = "Сбросить поворот",
                modifier = Modifier.rotate(state.rotation),
            )
        }
        SmallFloatingActionButton(onClick = { scope.launch { state.animateZoom(1.5f) } }) {
            Icon(Icons.Default.Add, contentDescription = "Приблизить")
        }
        SmallFloatingActionButton(onClick = { scope.launch { state.animateZoom(1 / 1.5f) } }) {
            Icon(Icons.Default.Remove, contentDescription = "Отдалить")
        }
        FloatingActionButton(onClick = { scope.launch { state.animateFit() } }) {
            Icon(Icons.Default.CenterFocusStrong, contentDescription = fitDescription)
        }
    }
}
