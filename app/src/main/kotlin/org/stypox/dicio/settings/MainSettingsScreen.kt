package org.stypox.dicio.settings

import android.app.Application
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.stypox.dicio.R
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.Language
import org.stypox.dicio.settings.datastore.SpeechOutputDevice
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.Theme
import org.stypox.dicio.settings.datastore.UserSettingsModule.Companion.newDataStoreForPreviews
import org.stypox.dicio.settings.ui.SettingsCategoryTitle
import org.stypox.dicio.settings.ui.SettingsItem
import org.stypox.dicio.ui.theme.AppTheme

@Composable
fun MainSettingsScreen(
    navigationIcon: @Composable () -> Unit,
    navigateToSkillSettings: () -> Unit,
    viewModel: MainSettingsViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title          = { Text(stringResource(R.string.settings)) },
                navigationIcon = navigationIcon,
            )
        }
    ) { paddingValues ->
        MainSettingsContent(
            navigateToSkillSettings = navigateToSkillSettings,
            viewModel               = viewModel,
            modifier                = Modifier.padding(paddingValues),
        )
    }
}

@Composable
private fun MainSettingsContent(
    navigateToSkillSettings: () -> Unit,
    viewModel: MainSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settingsState.collectAsState()

    LazyColumn(modifier) {
        /* ── General ─────────────────────────────────────────────────────── */
        item { SettingsCategoryTitle(stringResource(R.string.pref_general), topPadding = 4.dp) }

        item {
            languageSetting().Render(
                when (val lang = settings.language) {
                    Language.UNRECOGNIZED -> Language.LANGUAGE_SYSTEM
                    else                 -> lang
                },
                viewModel::setLanguage,
            )
        }

        item {
            themeSetting().Render(
                when (val theme = settings.theme) {
                    Theme.UNRECOGNIZED -> Theme.THEME_SYSTEM
                    else               -> theme
                },
                viewModel::setTheme,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item {
                dynamicColors().Render(settings.dynamicColors, viewModel::setDynamicColors)
            }
        }

        item {
            SettingsItem(
                title       = stringResource(R.string.pref_skills_title),
                icon        = Icons.Default.Extension,
                description = stringResource(R.string.pref_skills_summary),
                modifier    = Modifier
                    .clickable(onClick = navigateToSkillSettings)
                    .testTag("skill_settings_item"),
            )
        }

        /* ── Input / Output ──────────────────────────────────────────────── */
        item { SettingsCategoryTitle(stringResource(R.string.pref_io)) }

        item {
            inputDevice().Render(
                when (val d = settings.inputDevice) {
                    InputDevice.UNRECOGNIZED,
                    InputDevice.INPUT_DEVICE_UNSET -> InputDevice.INPUT_DEVICE_EXTERNAL_POPUP
                    else                           -> d
                },
                viewModel::setInputDevice,
            )
        }

        item {
            speechOutputDevice().Render(
                when (val d = settings.speechOutputDevice) {
                    SpeechOutputDevice.UNRECOGNIZED,
                    SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_UNSET ->
                        SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_ANDROID_TTS
                    else -> d
                },
                viewModel::setSpeechOutputDevice,
            )
        }

        item {
            sttPlaySound().Render(
                when (val s = settings.sttPlaySound) {
                    SttPlaySound.UNRECOGNIZED,
                    SttPlaySound.STT_PLAY_SOUND_UNSET -> SttPlaySound.STT_PLAY_SOUND_NOTIFICATION
                    else                              -> s
                },
                viewModel::setSttPlaySound,
            )
        }

        item {
            sttAutoFinish().Render(settings.autoFinishSttPopup, viewModel::setAutoFinishSttPopup)
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Preview
@Composable
private fun MainSettingsScreenPreview() {
    AppTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            MainSettingsContent(
                navigateToSkillSettings = {},
                viewModel = MainSettingsViewModel(
                    application = Application(),
                    dataStore   = newDataStoreForPreviews(),
                ),
            )
        }
    }
}

@Preview
@Composable
private fun MainSettingsScreenWithBarPreview() {
    AppTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            MainSettingsScreen(
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                navigateToSkillSettings = {},
                viewModel = MainSettingsViewModel(
                    application = Application(),
                    dataStore   = newDataStoreForPreviews(),
                ),
            )
        }
    }
}
