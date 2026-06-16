package org.stypox.dicio.di

import android.content.Context
import android.util.Log
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.Language
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.UserSettingsModule.Companion.newDataStoreForPreviews
import org.stypox.dicio.util.LocaleUtils
import org.stypox.dicio.util.distinctUntilChangedBlockingFirst
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the active locale and the matching sentences-language key, taking into account:
 *  1. The user's explicit language preference (from DataStore).
 *  2. The Android system locale list.
 *  3. Which languages actually have compiled sentence data ([Sentences.languages]).
 *
 * **Hebrew / "iw" normalisation:**
 * Android historically uses the legacy BCP 47 code "iw" for Hebrew, while the IETF standard
 * and our sentence directory use "he". The system locale list is normalised on construction
 * so that "iw" locales become "he" before being matched against [Sentences.languages].
 */
@Singleton
class LocaleManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    dataStore: DataStore<UserSettings>,
) {
    // Capture the system locale list at construction time (before any setLocale() call in
    // BaseActivity would overwrite the configuration with the user-selected locale).
    // Normalize legacy Hebrew code "iw" → "he" so it matches our sentences directory.
    private val systemLocaleList: LocaleListCompat = run {
        val raw = ConfigurationCompat.getLocales(appContext.resources.configuration)
        val normalized = (0 until raw.size()).map { i ->
            val locale = raw.get(i)!!
            if (locale.language == "iw") Locale("he", locale.country) else locale
        }
        LocaleListCompat.create(*normalized.toTypedArray())
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    private val _locale: MutableStateFlow<Locale>
    val locale: StateFlow<Locale>

    private val _sentencesLanguage: MutableStateFlow<String>
    val sentencesLanguage: StateFlow<String>

    init {
        val (firstLanguage, nextLanguageFlow) = dataStore.data
            .map { it.language }
            .distinctUntilChangedBlockingFirst()

        val initial = resolveLocale(firstLanguage)
        _locale           = MutableStateFlow(initial.availableLocale)
        locale            = _locale
        _sentencesLanguage = MutableStateFlow(initial.supportedLocaleString)
        sentencesLanguage  = _sentencesLanguage

        scope.launch {
            nextLanguageFlow.collect { newLanguage ->
                val result = resolveLocale(newLanguage)
                _locale.value            = result.availableLocale
                _sentencesLanguage.value = result.supportedLocaleString
            }
        }
    }

    private fun resolveLocale(language: Language): LocaleUtils.LocaleResolutionResult {
        return try {
            LocaleUtils.resolveSupportedLocaleOrThrow(
                localesForSetting(language),
                Sentences.languages,
            )
        } catch (e: LocaleUtils.UnsupportedLocaleException) {
            Log.w(TAG, "Locale not supported, defaulting to English", e)
            LocaleUtils.LocaleResolutionResult(
                availableLocale      = Locale.ENGLISH,
                supportedLocaleString = "en",
            )
        }
    }

    /**
     * Maps a [Language] preference to the locale list that should be consulted for matching.
     *
     * For [Language.LANGUAGE_SYSTEM] (and unrecognised values) the cached [systemLocaleList]
     * is returned. For explicit language selections the list contains exactly one locale,
     * derived from the enum name (e.g. LANGUAGE_HE → Locale("he")).
     */
    private fun localesForSetting(language: Language): LocaleListCompat {
        return when (language) {
            Language.LANGUAGE_SYSTEM,
            Language.UNRECOGNIZED -> systemLocaleList
            else -> {
                // Enum names follow the pattern LANGUAGE or LANGUAGE_COUNTRY;
                // strip the prefix and parse. e.g. "LANGUAGE_HE" → Locale("he").
                val tag = language.name.removePrefix("LANGUAGE_")
                LocaleListCompat.create(LocaleUtils.parseLanguageCountry(tag))
            }
        }
    }

    companion object {
        val TAG: String? = LocaleManager::class.simpleName

        fun newForPreviews(context: Context): LocaleManager =
            LocaleManager(context, newDataStoreForPreviews())
    }
}

/**
 * Hilt entry point allowing [LocaleManager] to be retrieved via
 * [dagger.hilt.android.EntryPointAccessors.fromApplication] before an activity's `onCreate()`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocaleManagerModule {
    fun getLocaleManager(): LocaleManager
}
