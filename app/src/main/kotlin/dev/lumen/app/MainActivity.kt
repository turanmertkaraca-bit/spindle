package dev.lumen.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.lumen.app.ui.LumenChatScreen
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(applicationContext, File(filesDir, "workspace").apply { mkdirs() })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            LumenChatScreen(
                steps = state.steps,
                input = state.input,
                busy = state.busy,
                error = state.error,
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
                onInput = viewModel::onInput,
                onSend = viewModel::send,
                onStop = viewModel::stop,
            )
        }
    }
}
