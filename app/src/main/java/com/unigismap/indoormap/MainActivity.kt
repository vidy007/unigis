package com.unigismap.indoormap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.unigismap.indoormap.core.CampusCore
import com.unigismap.indoormap.map.FloorPlan
import com.unigismap.indoormap.ui.MapScreen
import com.unigismap.indoormap.ui.theme.UniGisMapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val campus = CampusCore.get(this)
        val plan = FloorPlan.of(campus, campus.defaultFloor())
        setContent {
            UniGisMapTheme {
                MapScreen(plan)
            }
        }
    }
}
