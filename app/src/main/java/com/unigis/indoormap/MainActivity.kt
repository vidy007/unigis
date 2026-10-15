package com.unigis.indoormap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.unigis.indoormap.core.CampusCore
import com.unigis.indoormap.map.FloorPlan
import com.unigis.indoormap.ui.MapScreen
import com.unigis.indoormap.ui.theme.UniGisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val campus = CampusCore.get(this)
        val plan = FloorPlan.of(campus, campus.defaultFloor())
        setContent {
            UniGisTheme {
                MapScreen(plan)
            }
        }
    }
}
