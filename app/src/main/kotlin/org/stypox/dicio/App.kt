package org.stypox.dicio

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.HiltAndroidApp
import org.stypox.dicio.io.input.onnx_whisper.OnnxWhisperInputDevice
import org.stypox.dicio.util.checkPermissions

// IMPORTANT NOTE: beware of this nasty bug related to allowBackup=true
// https://medium.com/p/924c91bafcac
@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Ensure the Whisper model subdirectory exists from the very first launch
        // so the user can push model files via ADB without needing to first select
        // the input method in settings. filesDir itself is also created by this call.
        java.io.File(filesDir, OnnxWhisperInputDevice.MODEL_DIR_NAME).mkdirs()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkPermissions(this, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            initNotificationChannels()
        }
    }

    private fun initNotificationChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    getString(R.string.error_report_channel_id),
                    NotificationManagerCompat.IMPORTANCE_LOW
                )
                    .setName(getString(R.string.error_report_channel_name))
                    .setDescription(getString(R.string.error_report_channel_description))
                    .build()
            )
        )
    }
}
