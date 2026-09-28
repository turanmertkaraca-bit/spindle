package dev.lumen.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.lumen.app.ui.KeyScreen
import dev.lumen.app.ui.LumenChatScreen
import dev.lumen.app.ui.LumenColors
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(applicationContext, File(filesDir, "workspace").apply { mkdirs() })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            var darkOverride by remember { mutableStateOf<Boolean?>(null) }
            val dark = darkOverride ?: systemDark
            val colors = if (dark) LumenColors.Dark else LumenColors.Light

            // Keep the system bar icons legible against our own background.
            val view = LocalView.current
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
            }

            if (state.needsKey) {
                KeyScreen(
                    colors = colors,
                    onSubmit = viewModel::saveKey,
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                    onToggleTheme = { darkOverride = !dark },
                )
            } else {
                LumenChatScreen(
                    steps = state.steps,
                    input = state.input,
                    busy = state.busy,
                    error = state.error,
                    colors = colors,
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                    onInput = viewModel::onInput,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onToggleTheme = { darkOverride = !dark },
                )
            }
        }
    }
}
