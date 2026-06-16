package org.stypox.dicio.skills.telephone

import android.content.Context
import android.util.Log
import org.stypox.dicio.util.StringUtils
import java.io.File

/**
 * Reads contacts from a plain-text file stored in the app's external files directory.
 *
 * **File location:** `<external_files_dir>/contacts.txt`
 * e.g. `/sdcard/Android/data/org.stypox.dicio/files/contacts.txt`
 *
 * No extra Android permissions are required to read from this directory
 * (the app's own scoped-storage path is always accessible to the app itself).
 *
 * **File format** — one entry per line:
 * ```
 * Contact Name=PhoneNumber
 * ```
 * - Lines without `=` are silently ignored.
 * - Blank names or numbers are ignored.
 * - Multiple lines with the same name accumulate into a single [ContactEntry] with
 *   multiple numbers (deduplication by exact string).
 * - Lines starting with `#` are treated as comments and ignored.
 *
 * Example:
 * ```
 * # family
 * Mom=0521234567
 * Dad=0537654321
 * Dad=0528888888
 * Taxi=*6666
 * ```
 */
object FileContactSource {

    private val TAG = FileContactSource::class.simpleName

    /** The filename looked up inside [Context.getExternalFilesDir]. */
    const val CONTACTS_FILENAME = "contacts.txt"

    /**
     * Returns the [File] object representing the contacts file.
     * Callers can use this path for display or to guide users where to place the file.
     */
    fun getContactsFile(context: Context): File =
        File(context.getExternalFilesDir(null), CONTACTS_FILENAME)

    /**
     * Parses [getContactsFile], filters entries whose name partially matches [userInput],
     * and returns matching [ContactEntry]s sorted by match quality (best first).
     *
     * Returns an empty list if the file does not exist or cannot be read.
     */
    fun getFilteredSortedEntries(context: Context, userInput: String): List<ContactEntry> {
        val file = getContactsFile(context)
        if (!file.exists()) return emptyList()

        return try {
            // name → list of distinct numbers
            val nameToNumbers = linkedMapOf<String, MutableList<String>>()

            file.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.startsWith('#') || !line.contains('=')) return@forEachLine

                val eqIdx  = line.indexOf('=')
                val name   = line.substring(0, eqIdx).trim()
                val number = line.substring(eqIdx + 1).trim()
                if (name.isBlank() || number.isBlank()) return@forEachLine

                val list = nameToNumbers.getOrPut(name) { mutableListOf() }
                if (!list.contains(number)) list.add(number)
            }

            nameToNumbers
                .map { (name, numbers) ->
                    ContactEntry(
                        name     = name,
                        numbers  = numbers,
                        distance = StringUtils.contactStringDistance(name, userInput),
                    )
                }
                .filter  { it.distance < 0 }
                .sortedBy { it.distance }

        } catch (e: Exception) {
            Log.w(TAG, "Could not read contacts file: ${file.absolutePath}", e)
            emptyList()
        }
    }
}
