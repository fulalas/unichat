package org.unichat.app

import android.content.Context
import android.content.Intent

/**
 * A protocol seen as an account: whether it is linked, what it is called, how
 * it is set up, paused and removed, and who the user is on it.
 *
 * Bridge's Protocol interface is the other half of the same idea — one chat's
 * messages. Together they are what keeps `when (proto)` out of the screens:
 * this used to be twenty branches spread over eleven files, so adding a
 * protocol meant finding all of them.
 */
interface Account {
    val proto: String

    /** Namespace for this protocol's rows in the shared Db. WhatsApp is the
     *  unprefixed one, so its prefix is empty and never matches an id. */
    val idPrefix: String

    val labelRes: Int
    val accentRes: Int

    /** Null when the base theme is already this protocol's colour. */
    val themeOverlayRes: Int?

    fun isLinked(): Boolean
    val state: String
    fun selfId(): String
    fun myName(): String

    /** Empty when the protocol does not tell us the number. */
    fun myPhone(): String

    fun setupIntent(ctx: Context): Intent

    /** The Manage accounts pause: off the network, but still linked. */
    fun setNetworkEnabled(enabled: Boolean)
    fun logout()

    fun fetchAbout(onResult: (String) -> Unit)
    fun setMyName(name: String, onResult: (Boolean) -> Unit)
    fun setAbout(text: String, onResult: (Boolean) -> Unit)

    val supportsProfilePicture: Boolean
    fun setProfilePicture(jpegPath: String, onResult: (Boolean) -> Unit)

    fun fetchPrivacySettings(onResult: (Map<String, String>?) -> Unit)
    fun setPrivacySetting(name: String, value: String, onResult: (Boolean) -> Unit)

    /** Which rows the privacy screen shows for this account. "last" is last
     *  seen, "online" its who-can-see-me-online follow-up, "profile" the photo
     *  audience, "status" the About audience, "discoverable" findable by
     *  number, "readreceipts" the switch. The screen hides what is not here. */
    val privacyKeys: Set<String>

    /**
     * A chat id for an E.164 number (with '+'), or "" when the number has no
     * account on this network. [Bridge.NUMBER_LOOKUP_FAILED] when it could not
     * be asked at all. Blocking; worker threads only.
     */
    fun chatIdForNumber(number: String): String

    /** Said when [chatIdForNumber] comes back empty. */
    val notOnNetworkRes: Int

    /**
     * A chat id built from this protocol's own id as it appears on a contact
     * card, or "" when the card carries none this account can use. Blocking.
     */
    fun chatIdForCardId(cardId: String): String

    fun label(ctx: Context): String = ctx.getString(labelRes)
}

object Accounts {
    /** Every protocol the app speaks, linked or not, in the order the UI lists them. */
    val ALL: List<Account> = listOf(WaAccount, TgAccount, SgAccount)

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

    /** Linked and switched on. A paused account is off its network, so it can
     *  neither send nor read a profile back. */
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
    override fun chatIdForCardId(cardId: String) =
        if (cardId.isEmpty()) "" else PhoneBook.digitsOf(cardId) + "@s.whatsapp.net"
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

    override fun fetchPrivacySettings(onResult: (Map<String, String>?) -> Unit) {
        val settings = Signal.privacySettings()
        Bridge.runOnUi { onResult(settings) }
    }
    override fun setPrivacySetting(name: String, value: String, onResult: (Boolean) -> Unit) =
        Signal.setPrivacy(name, value, onResult)

    // None of the per-audience choices: a Signal profile is visible to anyone
    // you message. The two it does have are its own.
    override val privacyKeys = setOf("discoverable", "readreceipts")

    override fun chatIdForNumber(number: String) = Signal.lookupNumber(number)
    override val notOnNetworkRes = R.string.not_on_signal

    // A Signal contact card carries no id this account can reuse.
    override fun chatIdForCardId(cardId: String) = ""
}
