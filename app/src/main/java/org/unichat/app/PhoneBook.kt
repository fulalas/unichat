package org.unichat.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * The phone's own address book, as a search source.
 *
 * A linked device only ever learns contacts through WhatsApp's synced contact
 * list, which trails the phone's address book — someone saved a minute ago is
 * simply not there yet, and used to be unfindable. Reading the address book
 * directly closes that gap; the number is checked against WhatsApp only when
 * one of these results is actually picked.
 *
 * Every call answers empty without the permission, which is never required:
 * search still works over the chats and contacts already known.
 */
object PhoneBook {

    /** A person as the address book has them. [id] is a search-result key, not a chat id. */
    class Entry(val name: String, val number: String) {
        val id: String get() = PREFIX + number
    }

    const val PREFIX = "phone:"

    fun isPhoneEntry(id: String) = id.startsWith(PREFIX)

    /** The number inside a phone-entry id, in international form. */
    fun numberOf(id: String) = id.removePrefix(PREFIX)

    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * Address-book people matching [query] by name or number. Blocking (a
     * content-provider query); worker threads only.
     */
    fun search(ctx: Context, query: String, limit: Int = 20): List<Entry> {
        if (query.isBlank() || !granted(ctx)) return emptyList()
        // CONTENT_FILTER_URI matches name and number together and is the indexed
        // path the platform's own contact pickers use
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query)
        )
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        runCatching {
            ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(0) ?: continue
                    val number = normalize(c.getString(1) ?: continue)
                    // one row per number, not per label: a contact with the same
                    // number saved as both "mobile" and "work" is one person
                    if (number.isEmpty() || !seen.add(number)) continue
                    out.add(Entry(name, number))
                }
            }
        }
        return out
    }

    /**
     * A stored number in international form, or "" when it plainly is not one.
     * Anything without a country code is dropped rather than guessed at: a
     * wrong guess would open a chat with a stranger.
     */
    fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return when {
            raw.trimStart().startsWith("+") -> "+$digits"
            // international prefix as dialled from many countries
            digits.startsWith("00") -> "+" + digits.removePrefix("00")
            else -> ""
        }
    }

    /** Just the digits, for comparing against chat ids and stored contacts. */
    fun digitsOf(number: String) = number.filter { it.isDigit() }
}
