package org.stypox.dicio.skills.telephone

/**
 * Unified contact representation returned by any [ContactSource].
 *
 * @param name     Display name of the contact.
 * @param numbers  All phone numbers associated with this name.
 * @param distance String-distance score vs the user's spoken input (lower = better match;
 *                 negative values indicate a partial match, positive is effectively no match).
 */
data class ContactEntry(
    val name: String,
    val numbers: List<String>,
    val distance: Int,
)
