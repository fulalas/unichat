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

    /** Theme overlay for this protocol's screens, or null when the base theme
     *  is already its colour. */
    val themeOverlayRes: Int?

    fun isLinked(): Boolean

    /** "connected", "connecting", "disconnected" or "logged_out", as the
     *  protocol last reported through UiListener.onAccountState. */
    val state: String

    fun selfId(): String
    fun myName(): String

    /** The account's own number, as the user should read it. Empty when the
     *  protocol does not tell us. */
    fun myPhone(): String

    /** Where the user links this account. */
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
}

object Accounts {
    /** Every protocol the app speaks, linked or not, in the order the UI lists
     *  them. WhatsApp last would break [ofChat]'s fallback. */
    val ALL: List<Account> = listOf(WaAccount, TgAccount, SgAccount)

    // Indexed loops, not firstOrNull: both are on the chat-list and message
    // bind paths, which ask once per row.
    fun of(proto: String): Account {
        for (i in ALL.indices) if (ALL[i].proto == proto) return ALL[i]
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

    // Every TDLib call blocks on its own thread and answers on the main one.
    override fun fetchAbout(onResult: (String) -> Unit) = Bridge.onTg({ Tg.fetchMyAbout() }, onResult)
    override fun setMyName(name: String, onResult: (Boolean) -> Unit) =
        Bridge.onTg({ Tg.setMyName(name) }, onResult)
    override fun setAbout(text: String, onResult: (Boolean) -> Unit) =
        Bridge.onTg({ Tg.setAbout(text) }, onResult)

    override val supportsProfilePicture = true
    override fun setProfilePicture(jpegPath: String, onResult: (Boolean) -> Unit) =
        Bridge.onTg({ Tg.setProfilePicture(jpegPath) }, onResult)

    override fun fetchPrivacySettings(onResult: (Map<String, String>?) -> Unit) =
        Bridge.onTg({ Tg.fetchPrivacySettings() }, onResult)
    override fun setPrivacySetting(name: String, value: String, onResult: (Boolean) -> Unit) =
        Bridge.onTg({ Tg.setPrivacySetting(name, value) }, onResult)
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

    override val supportsProfilePicture = false
    override fun setProfilePicture(jpegPath: String, onResult: (Boolean) -> Unit) = onResult(false)

    // PrivacyActivity shows Signal its own screen and never asks for these; the
    // WhatsApp answers used to be handed back here, which would have edited the
    // WhatsApp account.
    override fun fetchPrivacySettings(onResult: (Map<String, String>?) -> Unit) = onResult(null)
    override fun setPrivacySetting(name: String, value: String, onResult: (Boolean) -> Unit) =
        onResult(false)
}
