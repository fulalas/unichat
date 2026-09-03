package org.unichat.app

import android.content.Context
import android.content.Intent

/**
 * With Bridge's Protocol interface, this is what keeps `when (proto)` out of
 * the screens: it used to be twenty branches spread over eleven files, so
 * adding a protocol meant finding all of them.
 */
interface Account {
    val proto: String

    /** WhatsApp is the unprefixed protocol, so its prefix is empty and never
     *  matches an id. */
    val idPrefix: String

    val labelRes: Int
    val accentRes: Int

    val themeOverlayRes: Int?

    fun isLinked(): Boolean
    val state: String
    fun selfId(): String

    /** Blocking: only Telegram has to wait for the protocol to come up after a
     *  start. */
    fun selfIdBlocking(): String = selfId()
    fun myName(): String

    fun myPhone(): String

    fun setupIntent(ctx: Context): Intent

    fun setNetworkEnabled(enabled: Boolean)
    fun logout()

    fun fetchAbout(onResult: (String) -> Unit)
    fun setMyName(name: String, onResult: (Boolean) -> Unit)
    fun setAbout(text: String, onResult: (Boolean) -> Unit)

    val supportsProfilePicture: Boolean
    fun setProfilePicture(jpegPath: String, onResult: (Boolean) -> Unit)

    fun fetchPrivacySettings(onResult: (Map<String, String>?) -> Unit)
    fun setPrivacySetting(name: String, value: String, onResult: (Boolean) -> Unit)

    /** "last" is last seen, "online" its who-can-see-me-online follow-up,
     *  "profile" the photo audience, "status" the About audience,
     *  "discoverable" findable by number, "readreceipts" the switch. */
    val privacyKeys: Set<String>

    /**
     * A chat id for an E.164 number (with '+'), or "" when the number has no
     * account on this network. [Bridge.NUMBER_LOOKUP_FAILED] when it could not
     * be asked at all. Blocking; worker threads only.
     */
    fun chatIdForNumber(number: String): String

    val notOnNetworkRes: Int

    /** "" when the contact card carries no id this account can use. Blocking. */
    fun chatIdForCardId(cardId: String): String

    val serverSearch: Boolean get() = false
    val historySync: Boolean get() = true

    fun label(ctx: Context): String = ctx.getString(labelRes)
}

object Accounts {
    /**
     * THIS is the order the user sees, everywhere a protocol is offered —
     * Manage accounts, the "which account?" picker, the login tabs, the
     * notes-to-self pinned above the forward list. Telegram first because its
     * own chat is where files get sent.
     */
    val ALL: List<Account> = listOf(TgAccount, SgAccount, WaAccount)

    // Indexed loops, not firstOrNull: [ofChat] is on the chat-list and message
    // bind paths, which ask once per row, and an iterator per row is waste.
    fun of(proto: String): Account {
        for (i in ALL.indices) if (ALL[i].proto == proto) return ALL[i]
        // WhatsApp is the fallback because it is the unprefixed protocol, but a
        // key that reaches here is a bug: falling through to WhatsApp silently
        // is what once opened a WhatsApp chat from a Signal card and themed
        // Signal screens green.
        android.util.Log.w("UniChat", "unknown protocol '$proto', using WhatsApp")
        return WaAccount
    }

    fun ofChat(chatId: String): Account {
        for (i in ALL.indices) {
            val account = ALL[i]
            if (account.idPrefix.isNotEmpty() && chatId.startsWith(account.idPrefix)) return account
        }
        return WaAccount
    }

    fun linked(): List<Account> = ALL.filter { it.isLinked() }

    /** A paused account is off its network, so it can neither send nor read a
     *  profile back. */
    fun active(): List<Account> = ALL.filter { it.isLinked() && Bridge.protoEnabled(it.proto) }
}

private object WaAccount : Account {
    override val proto = ProtoPicker.WA
    override val idPrefix = ""
    override val labelRes = R.string.whatsapp
    override val accentRes = R.color.accent_wa
    override val themeOverlayRes = R.style.ThemeOverlay_UniChat_Wa

    override fun isLinked() = Bridge.hasSession()
    override val state get() = Bridge.state
    override fun selfId() = Bridge.selfId()
    override fun myName() = Bridge.myName()

    // A WhatsApp id carries the number, unlike an ACI or a Telegram user id.
    override fun myPhone(): String {
        val id = Bridge.selfId()
        return if (isPhoneId(id) && id.substringBefore('@').isNotEmpty()) phoneLabel(id) else ""
    }

    override fun setupIntent(ctx: Context) = LoginActivity.intent(ctx, proto)
    override fun setNetworkEnabled(enabled: Boolean) =
        if (enabled) Bridge.connect() else Bridge.disconnect()
    override fun logout() = Bridge.logout()

    override fun fetchAbout(onResult: (String) -> Unit) = Bridge.fetchMyAbout(onResult)
    override fun setMyName(name: String, onResult: (Boolean) -> Unit) =
        Bridge.setMyName(name, onResult)
    override fun setAbout(text: String, onResult: (Boolean) -> Unit) = Bridge.setAbout(text, onResult)

    override val supportsProfilePicture = true
    override fun setProfilePicture(jpegPath: String, onResult: (Boolean) -> Unit) =
        Bridge.setProfilePicture(jpegPath, onResult)

    override fun fetchPrivacySettings(onResult: (Map<String, String>?) -> Unit) =
        Bridge.fetchPrivacySettings(onResult)
    override fun setPrivacySetting(name: String, value: String, onResult: (Boolean) -> Unit) =
        Bridge.setPrivacySetting(name, value, onResult)

    override val privacyKeys = setOf("last", "online", "profile", "status", "readreceipts")

    override fun chatIdForNumber(number: String) = Bridge.resolveNumber(number)
    override val notOnNetworkRes = R.string.not_on_whatsapp

    // A WhatsApp contact card carries the bare digits, and those are the id.
    // Checked on the DIGITS, not on cardId: the waid a received card carries is
    // the sender's to write, so a non-numeric one built "@s.whatsapp.net" — an
    // invalid JID that is not empty, so the caller's number fallback never ran
    // and the chat opened on it anyway.
    override fun chatIdForCardId(cardId: String): String {
        val digits = PhoneBook.digitsOf(cardId)
        return if (digits.isEmpty()) "" else digits + "@s.whatsapp.net"
    }
}

private object TgAccount : Account {
    override val proto = ProtoPicker.TG
    override val idPrefix = Tg.PREFIX
    override val labelRes = R.string.telegram
    override val accentRes = R.color.accent
    // The base theme is already Telegram blue.
    override val themeOverlayRes: Int? = null

    override fun isLinked() = Tg.hasSession()
    override val state get() = Tg.state
    override fun selfId() = Tg.selfId()
    override fun selfIdBlocking() = Tg.selfIdBlocking()
    override fun myName() = Tg.myName()
    override fun myPhone() = Tg.myPhone()

    override fun setupIntent(ctx: Context) = LoginActivity.intent(ctx, proto)
    override fun setNetworkEnabled(enabled: Boolean) = Tg.setNetworkEnabled(enabled)
    override fun logout() = Tg.logout()

    override fun fetchAbout(onResult: (String) -> Unit) = Tg.async({ Tg.fetchMyAbout() }, onResult)
    override fun setMyName(name: String, onResult: (Boolean) -> Unit) =
        Tg.async({ Tg.setMyName(name) }, onResult)
    override fun setAbout(text: String, onResult: (Boolean) -> Unit) =
        Tg.async({ Tg.setAbout(text) }, onResult)

    override val supportsProfilePicture = true
    override fun setProfilePicture(jpegPath: String, onResult: (Boolean) -> Unit) =
        Tg.async({ Tg.setProfilePicture(jpegPath) }, onResult)

    override fun fetchPrivacySettings(onResult: (Map<String, String>?) -> Unit) =
        Tg.async({ Tg.fetchPrivacySettings() }, onResult)
    override fun setPrivacySetting(name: String, value: String, onResult: (Boolean) -> Unit) =
        Tg.async({ Tg.setPrivacySetting(name, value) }, onResult)

    // No read-receipts setting, and last seen carries its own online rule.
    override val privacyKeys = setOf("last", "profile", "status")

    override fun chatIdForNumber(number: String) = Tg.createChatByPhone(number)
    override val notOnNetworkRes = R.string.not_on_telegram

    override fun chatIdForCardId(cardId: String) =
        cardId.toLongOrNull()?.let { Tg.createUserChat(it) }.orEmpty()

    override val serverSearch = true
}

private object SgAccount : Account {
    override val proto = ProtoPicker.SG
    override val idPrefix = Signal.PREFIX
    override val labelRes = R.string.signal
    override val accentRes = R.color.accent_sg
    override val themeOverlayRes = R.style.ThemeOverlay_UniChat_Sg

    override fun isLinked() = Signal.hasSession()
    override val state get() = Signal.state
    override fun selfId() = Signal.selfId()
    override fun myName() = Signal.myName()
    override fun myPhone() = Signal.myPhone()

    // Signal registers this app as the account's primary device, which is
    // nothing like linking a companion: its own screen owns the warning.
    override fun setupIntent(ctx: Context) = Intent(ctx, SignalRegisterActivity::class.java)
    override fun setNetworkEnabled(enabled: Boolean) =
        if (enabled) Signal.connect() else Signal.disconnect()
    override fun logout() = Signal.logout()

    override fun fetchAbout(onResult: (String) -> Unit) = Signal.fetchAbout(onResult)
    // Signal's profile endpoint replaces every field at once, so each of these
    // resends the other one unchanged.
    override fun setMyName(name: String, onResult: (Boolean) -> Unit) =
        Signal.setProfile(name, null, onResult)
    override fun setAbout(text: String, onResult: (Boolean) -> Unit) =
        Signal.setProfile(null, text, onResult)

    // Refusals still answer on the main thread, like every other account here:
    // ProfileActivity calls this from a worker and touches views in the
    // callback, so a straight onResult() would run them off the main thread.
    override val supportsProfilePicture = false
    override fun setProfilePicture(jpegPath: String, onResult: (Boolean) -> Unit) {
        Bridge.runOnUi { onResult(false) }
    }

    // Empty maps to null, which is this callback's "could not read": an empty
    // map went through as success, so the screen rendered both switches at
    // their defaults and a toggle then wrote a setting nobody had read back.
    override fun fetchPrivacySettings(onResult: (Map<String, String>?) -> Unit) {
        val settings = Signal.privacySettings().ifEmpty { null }
        Bridge.runOnUi { onResult(settings) }
    }
    override fun setPrivacySetting(name: String, value: String, onResult: (Boolean) -> Unit) =
        Signal.setPrivacy(name, value, onResult)

    // None of the per-audience choices: a Signal profile is visible to anyone
    // you message.
    override val privacyKeys = setOf("discoverable", "readreceipts")

    override fun chatIdForNumber(number: String) = Signal.lookupNumber(number)
    override val notOnNetworkRes = R.string.not_on_signal

    // A Signal contact card carries no id this account can reuse.
    override fun chatIdForCardId(cardId: String) = ""

    override val historySync = false
}
