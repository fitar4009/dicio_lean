package org.stypox.dicio.skills.telephone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.util.RecognizeYesNoSkill
import org.stypox.dicio.util.getString

/**
 * Asks the user to confirm a call before dialling.
 *
 * @param contactName The contact's display name, or `null` when the user spoke a
 *                    phone number directly (direct-dial mode). When `null`, the
 *                    [number] itself is used as the display string.
 * @param number      The raw dial string (digits and/or `*`/`#`).
 */
class ConfirmCallOutput(
    private val contactName: String?,
    private val number: String,
) : SkillOutput {

    /** The string shown/spoken in the confirmation prompt. */
    private val displayLabel: String get() = contactName ?: number

    override fun getSpeechOutput(ctx: SkillContext): String =
        ctx.getString(R.string.skill_telephone_confirm_call, displayLabel)

    override fun getInteractionPlan(ctx: SkillContext): InteractionPlan {
        val yesNoSentences = Sentences.UtilYesNo[ctx.sentencesLanguage]!!
        val confirmYesNoSkill = object : RecognizeYesNoSkill(TelephoneInfo, yesNoSentences) {
            override suspend fun generateOutput(
                ctx: SkillContext,
                inputData: Boolean,
            ): SkillOutput = if (inputData) {
                TelephoneSkill.call(ctx.android, number)
                ConfirmedCallOutput(number)
            } else {
                ConfirmedCallOutput(null)
            }
        }

        return InteractionPlan.ReplaceSubInteraction(
            reopenMicrophone = true,
            nextSkills        = listOf(confirmYesNoSkill),
        )
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            if (contactName != null) {
                // Show the raw number separately only when we have a named contact
                Spacer(modifier = Modifier.height(4.dp))
                Body(text = number)
            }
        }
    }
}
