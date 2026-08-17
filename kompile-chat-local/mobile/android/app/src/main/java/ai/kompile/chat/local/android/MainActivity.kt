package ai.kompile.chat.local.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ai.kompile.chat.local.android.ui.navigation.AppNavigation
import ai.kompile.chat.local.android.ui.theme.KompileChatTheme
import ai.kompile.chat.local.android.viewmodel.ChatViewModel

/**
 * Single-activity host. Navigation is handled entirely inside Compose via
 * [AppNavigation]. No fragments.
 */
class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KompileChatTheme {
                AppNavigation(vm = chatViewModel)
            }
        }
    }
}
