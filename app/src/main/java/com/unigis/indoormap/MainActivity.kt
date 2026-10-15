package com.unigis.indoormap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.unigis.indoormap.core.Campus
import com.unigis.indoormap.ui.MapScreen
import com.unigis.indoormap.ui.theme.UniGisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val campus = Campus.get(this)
        val plan = campus.floorPlan(requireNotNull(campus.defaultFloor()) { "campus.json has no floor plans" })
        setContent {
            UniGisTheme {
                MapScreen(plan)
            }
        }
    }
}
