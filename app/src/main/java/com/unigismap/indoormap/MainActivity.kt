package com.unigismap.indoormap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.unigismap.indoormap.core.CampusCore
import com.unigismap.indoormap.ui.UniGisMapApp
import com.unigismap.indoormap.ui.theme.UniGisMapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val campus = CampusCore.acquire(this)
        setContent {
            UniGisMapTheme {
                UniGisMapApp(campus)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CampusCore.release(changingConfigurations = isChangingConfigurations)
    }
}
