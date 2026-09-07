package org.unichat.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

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
                    if (number.isEmpty() || !seen.add(number)) continue
                    out.add(Entry(name, number))
                }
            }
        }
        return out
    }

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

    fun pickIntent(): android.content.Intent = android.content.Intent(
        android.content.Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    )

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
                    if (number.isNotEmpty() && seen.add(digitsOf(number))) into.add(number)
                }
            }
        }
    }

    fun vcard(name: String, numbers: List<String>): String {
        val lines = ArrayList<String>()
        lines.add("BEGIN:VCARD")
        lines.add("VERSION:3.0")
        lines.add("N:;${escapeVcard(name)};;;")
        lines.add("FN:${escapeVcard(name)}")
        for (n in numbers) lines.add("TEL;type=CELL;type=VOICE:${escapeVcard(n)}")
        lines.add("END:VCARD")
        return lines.joinToString("\r\n")
    }

    private fun escapeVcard(value: String): String = value
        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace("\n", " ").replace("\r", "")
}
