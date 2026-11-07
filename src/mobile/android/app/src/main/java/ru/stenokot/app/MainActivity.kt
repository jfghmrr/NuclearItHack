package ru.stenokot.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.stenokot.app.pipeline.IntentEntry

/** Единственная активность приложения: принимает входящие аудио и показывает интерфейс. */
class MainActivity : ComponentActivity() {
    private lateinit var entry: IntentEntry

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val controller = (application as StenoApplication).graph.controller
        entry = IntentEntry(controller)
        setContent {
            val home by controller.home.collectAsStateWithLifecycle()
            Text(
                text = home.statusCode,
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .testTag("txt_status"),
            )
        }
        entry.onCreate(intent, savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        entry.onNewIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        entry.onSaveInstanceState(outState)
    }
}
