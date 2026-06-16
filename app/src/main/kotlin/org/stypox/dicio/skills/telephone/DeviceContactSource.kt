package org.stypox.dicio.skills.telephone

import android.content.ContentResolver
import android.provider.ContactsContract
import android.util.Log
import org.stypox.dicio.util.StringUtils
import java.util.regex.Pattern

/**
 * Reads contacts from Android's [ContactsContract] content provider.
 *
 * This automatically covers all contact accounts present on the device, including:
 *  - Locally stored contacts
 *  - Contacts synced from Google, Exchange, etc.
 *  - Contacts synced over Bluetooth via PBAP from a paired phone
 *    (relevant for Android-based car multimedia head units that have no local
 *    contact database of their own but receive contacts dynamically through HFP/PBAP).
 *
 * A single join query against [ContactsContract.CommonDataKinds.Phone.CONTENT_URI]
 * retrieves every (display_name, phone_number) pair at once, eliminating the need
 * for per-contact follow-up queries. Duplicate numbers for the same contact (same
 * digit string after stripping formatting) are discarded.
 */
object DeviceContactSource {

    private val TAG = DeviceContactSource::class.simpleName
    private val NUMBER_CLEANER = Pattern.compile("[^\\d*#]")

    /**
     * Returns contacts whose display name partially matches [userInput], sorted by
     * match quality (best / most negative distance first). Entries with a non-negative
     * distance are excluded (they have essentially no overlap with the spoken name).
     */
    fun getFilteredSortedEntries(
        contentResolver: ContentResolver,
        userInput: String,
    ): List<ContactEntry> {
        // name → ordered set of raw phone strings (deduped by digit content)
        val nameToNumbers = linkedMapOf<String, MutableList<String>>()

        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} IS NOT NULL",
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol  = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val name   = cursor.getString(nameCol) ?: continue
                    val number = cursor.getString(numCol)  ?: continue
                    if (number.isBlank()) continue

                    val cleaned = NUMBER_CLEANER.matcher(number).replaceAll("")
                    val list = nameToNumbers.getOrPut(name) { mutableListOf() }
                    // Skip this number if its digit-only form is already present
                    if (list.none { NUMBER_CLEANER.matcher(it).replaceAll("") == cleaned }) {
                        list.add(number)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query device contacts", e)
        }

        return nameToNumbers
            .map { (name, numbers) ->
                ContactEntry(
                    name     = name,
                    numbers  = numbers,
                    distance = StringUtils.contactStringDistance(name, userInput),
                )
            }
            .filter  { it.distance < 0 }
            .sortedBy { it.distance }
    }
}
