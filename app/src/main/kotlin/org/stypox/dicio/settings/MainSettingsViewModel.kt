package org.stypox.dicio.settings

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.Language
import org.stypox.dicio.settings.datastore.SpeechOutputDevice
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.Theme
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.util.toStateFlowDistinctBlockingFirst
import javax.inject.Inject

@HiltViewModel
class MainSettingsViewModel @Inject constructor(
    application: Application,
    private val dataStore: DataStore<UserSettings>,
) : AndroidViewModel(application) {

    val settingsState = dataStore.data
        .toStateFlowDistinctBlockingFirst(viewModelScope)

    private fun update(block: (UserSettings.Builder) -> Unit) {
        viewModelScope.launch {
            dataStore.updateData { it.toBuilder().apply(block).build() }
        }
    }

    fun setLanguage(value: Language)                = update { it.setLanguage(value) }
    fun setTheme(value: Theme)                       = update { it.setTheme(value) }
    fun setDynamicColors(value: Boolean)             = update { it.setDynamicColors(value) }
    fun setInputDevice(value: InputDevice)           = update { it.setInputDevice(value) }
    fun setSpeechOutputDevice(value: SpeechOutputDevice) = update { it.setSpeechOutputDevice(value) }
    fun setSttPlaySound(value: SttPlaySound)         = update { it.setSttPlaySound(value) }
    fun setAutoFinishSttPopup(value: Boolean)        = update { it.setAutoFinishSttPopup(value) }
}
