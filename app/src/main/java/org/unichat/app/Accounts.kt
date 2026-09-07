package org.unichat.app

import android.content.Context
import android.content.Intent

interface Account {
    val proto: String

    val idPrefix: String

    val labelRes: Int
    val accentRes: Int

    val themeOverlayRes: Int?

    fun isLinked(): Boolean
    val state: String
    fun selfId(): String

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

    val privacyKeys: Set<String>

    fun chatIdForNumber(number: String): String

    val notOnNetworkRes: Int

    fun chatIdForCardId(cardId: String): String

    val serverSearch: Boolean get() = false
    val historySync: Boolean get() = true

    fun label(ctx: Context): String = ctx.getString(labelRes)
}

object Accounts {
    val ALL: List<Account> = listOf(TgAccount, SgAccount, WaAccount)

    fun of(proto: String): Account {
        for (i in ALL.indices) if (ALL[i].proto == proto) return ALL[i]
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

    override fun setupIntent(ctx: Context) = Intent(ctx, SignalRegisterActivity::class.java)
    override fun setNetworkEnabled(enabled: Boolean) =
        if (enabled) Signal.connect() else Signal.disconnect()
    override fun logout() = Signal.logout()

    override fun fetchAbout(onResult: (String) -> Unit) = Signal.fetchAbout(onResult)
    override fun setMyName(name: String, onResult: (Boolean) -> Unit) =
        Signal.setProfile(name, null, onResult)
    override fun setAbout(text: String, onResult: (Boolean) -> Unit) =
        Signal.setProfile(null, text, onResult)

    override val supportsProfilePicture = false
    override fun setProfilePicture(jpegPath: String, onResult: (Boolean) -> Unit) {
        Bridge.runOnUi { onResult(false) }
    }

    override fun fetchPrivacySettings(onResult: (Map<String, String>?) -> Unit) {
        val settings = Signal.privacySettings().ifEmpty { null }
        Bridge.runOnUi { onResult(settings) }
    }
    override fun setPrivacySetting(name: String, value: String, onResult: (Boolean) -> Unit) =
        Signal.setPrivacy(name, value, onResult)

    override val privacyKeys = setOf("discoverable", "readreceipts")

    override fun chatIdForNumber(number: String) = Signal.lookupNumber(number)
    override val notOnNetworkRes = R.string.not_on_signal

    override fun chatIdForCardId(cardId: String) = ""

    override val historySync = false
}
