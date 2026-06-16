package org.stypox.dicio

import android.Manifest
import android.content.Intent
import android.content.Intent.ACTION_ASSIST
import android.content.Intent.ACTION_VOICE_COMMAND
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.shreyaspatil.permissionFlow.PermissionFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.ui.nav.Navigation
import org.stypox.dicio.util.BaseActivity
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseActivity() {

    @Inject lateinit var skillEvaluator: SkillEvaluator
    @Inject lateinit var sttInputDevice: SttInputDeviceWrapper

    private var sttPermissionJob: Job? = null
    private var nextAssistAllowed = Instant.MIN

    /**
     * Loads the STT device and starts listening when an ASSIST intent is received. A short
     * backoff is applied because Android sometimes sends the intent twice in quick succession.
     */
    private fun onAssistIntentReceived() {
        val now = Instant.now()
        if (nextAssistAllowed < now) {
            nextAssistAllowed = now.plusMillis(INTENT_BACKOFF_MILLIS)
            Log.d(TAG, "Received assist intent")
            sttInputDevice.tryLoad(skillEvaluator::processInputEvent)
        } else {
            Log.w(TAG, "Ignoring duplicate assist intent")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isAssistIntent(intent)) {
            onAssistIntentReceived()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isAssistIntent(intent)) {
            onAssistIntentReceived()
        } else {
            // load the input device without immediately starting to listen
            sttInputDevice.tryLoad(null)
        }

        sttPermissionJob?.cancel()
        sttPermissionJob = lifecycleScope.launch {
            // if the STT failed to load because of a missing permission, retry when it is granted
            PermissionFlow.getInstance().getPermissionState(Manifest.permission.RECORD_AUDIO)
                .drop(1)
                .filter { it.isGranted }
                .collect { sttInputDevice.tryLoad(null) }
        }

        composeSetContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(modifier = Modifier.safeDrawingPadding()) {
                    Navigation()
                }
            }
        }
    }

    companion object {
        private const val INTENT_BACKOFF_MILLIS = 100L
        private val TAG = MainActivity::class.simpleName

        private fun isAssistIntent(intent: Intent?): Boolean {
            return when (intent?.action) {
                ACTION_ASSIST, ACTION_VOICE_COMMAND -> true
                else -> false
            }
        }
    }
}
