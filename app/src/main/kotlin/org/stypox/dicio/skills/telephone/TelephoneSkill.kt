package org.stypox.dicio.skills.telephone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Telephone
import org.stypox.dicio.util.NumberWordParser

class TelephoneSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Telephone>,
) : StandardRecognizerSkill<Telephone>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Telephone): SkillOutput {
        val rawWho = when (inputData) {
            is Telephone.Dial -> inputData.who?.trim() ?: ""
        }

        // Strip a leading "ל" that the STT may glue onto the first captured word when the user
        // says e.g. "חייג לאמא" (instead of "חייג ל אמא"). The sentence pattern already
        // accounts for an optional separate "ל" token; this handles the attached case.
        val userInput = rawWho.removePrefix("ל").trim()

        // ── 1. Direct dial ────────────────────────────────────────────────────────────────
        // Check whether every token in the captured text is a spoken digit word (Hebrew/English)
        // or literal digit. If so, skip contact lookup and dial the number directly.
        val dialNumber = NumberWordParser.tryParseAsDialString(userInput)
        if (dialNumber != null) {
            Log.d(TAG, "Direct dial detected: \"$userInput\" → $dialNumber")
            return ConfirmCallOutput(contactName = null, number = dialNumber)
        }

        // ── 2. Unified contact lookup ─────────────────────────────────────────────────────
        // Merge contacts from both sources and re-sort by distance.
        // DeviceContactSource covers regular device contacts AND Bluetooth-synced contacts
        // (car multimedia / HFP PBAP). FileContactSource provides the optional text-file
        // fallback at <external_files>/contacts.txt.
        val deviceEntries = DeviceContactSource.getFilteredSortedEntries(
            ctx.android.contentResolver, userInput
        )
        val fileEntries = FileContactSource.getFilteredSortedEntries(
            ctx.android, userInput
        )

        // Merge: sort combined list by distance, then deduplicate by name so the same person
        // appearing in both sources is not presented twice (device entry wins for numbers).
        val seen = mutableSetOf<String>()
        val merged: List<ContactEntry> = (deviceEntries + fileEntries)
            .sortedBy { it.distance }
            .filter   { seen.add(it.name) }

        Log.d(TAG, "Contact lookup for \"$userInput\": " +
                "${deviceEntries.size} device + ${fileEntries.size} file → ${merged.size} merged")

        // ── 3. Pick the best result(s) ────────────────────────────────────────────────────
        val validContacts = mutableListOf<Pair<String, List<String>>>()

        for (entry in merged) {
            if (validContacts.size >= 5) break

            if (validContacts.isEmpty()
                && entry.numbers.size == 1          // unambiguous single number
                && (merged.size <= 1                // no runner-up …
                        || merged[1].distance - 2 > entry.distance)  // … or runner-up far behind
            ) {
                // Very close match with exactly one number → confirm immediately
                return ConfirmCallOutput(entry.name, entry.numbers[0])
            }

            validContacts.add(entry.name to entry.numbers)
        }

        // Exactly one contact found and either it has one number OR number-parsing is unavailable
        // (no parserFormatter) so the index chooser would be skipped anyway
        if (validContacts.size == 1
            && (validContacts[0].second.size == 1 || ctx.parserFormatter == null)
        ) {
            val c = validContacts[0]
            return ConfirmCallOutput(c.first, c.second[0])
        }

        return TelephoneOutput(validContacts)
    }

    companion object {
        private val TAG = TelephoneSkill::class.simpleName

        /** Fires an [Intent.ACTION_CALL] intent for [number]. Works with digits and `*`/`#`. */
        fun call(context: Context, number: String) {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data  = Uri.parse("tel:${Uri.encode(number)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
}
