package dev.lumen.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import dev.lumen.app.ui.TimelineScreen
import dev.lumen.app.ui.sampleDemoSteps

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TimelineScreen(
                steps = sampleDemoSteps(),
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
            )
        }
    }
}
