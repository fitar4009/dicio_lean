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

@Singleton
class LocaleManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    dataStore: DataStore<UserSettings>,
) {
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
        _locale            = MutableStateFlow(initial.availableLocale)
        locale             = _locale
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
            Log.w(TAG, "Locale not supported, falling back to Hebrew", e)
            // Hebrew is the primary language of this fork; always fall back to it.
            LocaleUtils.LocaleResolutionResult(
                availableLocale       = Locale("he"),
                supportedLocaleString = "he",
            )
        }
    }

    /**
     * Maps a [Language] preference to the locale list used for sentence matching.
     *
     * **Hebrew-first policy:**
     * [Language.LANGUAGE_SYSTEM] has numeric value 0, which is also proto3's default for
     * unset enum fields. This means every device that has never explicitly chosen a language
     * (including existing installs migrated from older versions) has [Language.LANGUAGE_SYSTEM]
     * stored. Because this is a Hebrew-first fork and the car OS may run in English, we treat
     * [Language.LANGUAGE_SYSTEM] as Hebrew rather than following the device OS language.
     * Users who want English can select it explicitly in Settings → Language.
     *
     * **Hebrew "iw" normalisation:**
     * Android / Java stores Hebrew internally as the legacy BCP 47 code "iw" while our
     * sentences directory uses the modern code "he". [LocaleUtils.resolveSupportedLocaleOrThrow]
     * matches on [Locale.getLanguage], so we pass [Locale] with language "he" directly;
     * the sentences compiler also outputs "he" as the language key.
     */
    private fun localesForSetting(language: Language): LocaleListCompat {
        return when (language) {
            // LANGUAGE_SYSTEM (= 0) is the proto3 default for "never explicitly set".
            // Treat it as Hebrew for this fork so commands work on any OS language.
            Language.LANGUAGE_SYSTEM,
            Language.UNRECOGNIZED -> LocaleListCompat.create(Locale("he"))

            else -> {
                // Derive locale from enum name: LANGUAGE_HE → "HE" → Locale("he"),
                //                               LANGUAGE_EN → "EN" → Locale("en"), etc.
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

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocaleManagerModule {
    fun getLocaleManager(): LocaleManager
}
