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

    class Entry(val name: String, val number: String) {
        val id: String get() = PREFIX + number
    }

    const val PREFIX = "phone:"

    fun isPhoneEntry(id: String) = id.startsWith(PREFIX)

    fun numberOf(id: String) = id.removePrefix(PREFIX)

    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun search(ctx: Context, query: String, limit: Int = 20): List<Entry> {
        if (query.isBlank() || !granted(ctx)) return emptyList()
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
     * Every number in the address book, deduplicated. Feeds Signal's contact
     * discovery, which has no other way to learn who the user knows: a freshly
     * registered account holds no server-side contact list.
     */
    fun allEntries(ctx: Context, limit: Int = 2000): List<Entry> {
        if (!granted(ctx)) return emptyList()
        val region = deviceRegion(ctx)
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(0) ?: continue
                    val number = toE164(c.getString(1) ?: continue, region)
                    if (number.isEmpty() || !seen.add(number)) continue
                    out.add(Entry(name, number))
                }
            }
        }
        return out
    }

    /**
     * Address-book numbers are mostly saved in local form, with no country
     * code — over half of them here. [normalize] refuses to guess one, which is
     * right when the result would open a chat, but for contact discovery it
     * simply hid most of the address book. The platform formatter applies the
     * device's own region, the same assumption the dialler makes.
     */
    private fun toE164(raw: String, region: String): String {
        if (region.isNotEmpty()) {
            android.telephony.PhoneNumberUtils.formatNumberToE164(raw, region)?.let {
                return normalize(it)
            }
        }
        return normalize(raw)
    }

    private fun deviceRegion(ctx: Context): String {
        val sim = runCatching {
            ctx.getSystemService(android.telephony.TelephonyManager::class.java)?.simCountryIso
        }.getOrNull()
        return (sim?.takeIf { it.isNotBlank() }
            ?: java.util.Locale.getDefault().country).uppercase()
    }

    /** How many phone rows exist at all, to compare against [allEntries]. */
    fun rawCount(ctx: Context): Int {
        if (!granted(ctx)) return 0
        var n = 0
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null
            )?.use { n = it.count }
        }
        return n
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
            digits.startsWith("00") -> "+" + digits.removePrefix("00")
            else -> ""
        }
    }

    fun digitsOf(number: String) = number.filter { it.isDigit() }

    class Picked(val name: String, val numbers: List<String>)

    /** What the picker is asked for: one PHONE NUMBER, not one person. The row
     *  it hands back carries both the number and the name, and reading it is
     *  covered by the picker's own one-shot grant — so sending a contact works
     *  even though this app only ever asks for READ_CONTACTS to widen search,
     *  and may well have been refused. */
    fun pickIntent(): android.content.Intent = android.content.Intent(
        android.content.Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    )

    /**
     * The person behind a URI [pickIntent] returned. The number the user picked
     * always leads; their other numbers are added only when the address book is
     * readable, which is a bonus rather than a requirement.
     */
    fun read(ctx: Context, dataUri: Uri): Picked? {
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        )
        var name = ""
        var contactId = ""
        val numbers = ArrayList<String>()
        val seen = HashSet<String>()
        runCatching {
            ctx.contentResolver.query(dataUri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use
                name = c.getString(0).orEmpty()
                contactId = c.getString(2).orEmpty()
                val number = c.getString(1)?.trim().orEmpty()
                if (number.isNotEmpty() && seen.add(digitsOf(number))) numbers.add(number)
            }
        }.getOrElse { return null }
        if (numbers.isEmpty()) return null
        if (contactId.isNotEmpty() && granted(ctx)) addOtherNumbers(ctx, contactId, numbers, seen)
        return Picked(name.ifEmpty { numbers.first() }, numbers)
    }

    private fun addOtherNumbers(
        ctx: Context, contactId: String, into: MutableList<String>, seen: MutableSet<String>,
    ) {
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                arrayOf(contactId), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0)?.trim().orEmpty()
                    // one entry per number, not per label: the same number saved
                    // as both "mobile" and "work" is one number to send
                    if (number.isNotEmpty() && seen.add(digitsOf(number))) into.add(number)
                }
            }
        }
    }

    /**
     * A vCard 3.0 card, the wire format for a WhatsApp contact message.
     * Deliberately carries no `waid=` parameter: that claims the number belongs
     * to a specific WhatsApp account, and this app has not asked the server
     * whether it does — a guessed one would send the recipient to a stranger.
     */
    fun vcard(name: String, numbers: List<String>): String {
        val lines = ArrayList<String>()
        lines.add("BEGIN:VCARD")
        lines.add("VERSION:3.0")
        lines.add("N:;${escapeVcard(name)};;;")
        lines.add("FN:${escapeVcard(name)}")
        for (n in numbers) lines.add("TEL;type=CELL;type=VOICE:${escapeVcard(n)}")
        lines.add("END:VCARD")
        return lines.joinToString("\n")
    }

    private fun escapeVcard(value: String): String = value
        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace("\n", " ").replace("\r", "")
}
