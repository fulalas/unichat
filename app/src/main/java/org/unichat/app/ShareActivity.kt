package org.unichat.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.File

class ShareActivity : BaseActivity() {

    private val io = Io.executor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        if (!Bridge.init(this) || !Bridge.hasAnySession()) {
            Toast.makeText(this, R.string.share_not_linked, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            finish()
            return
        }
        Bridge.connect()

        // EXTRA_TEXT is a CharSequence: apps that share styled text put a
        // Spanned there and getStringExtra just returns null for it, so the
        // share fell through to "nothing to send" and closed without a word
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val streams = extraStreams(intent, action)
        val mime = intent.type ?: "*/*"

        io.execute {
            val (labels, ids) = targetChoices()
            if (ids.isEmpty()) {
                runOnUiThread { failAndFinish(R.string.no_chats) }
                return@execute
            }
            runOnUiThread {
                // the picker is an AlertDialog: showing it once this window's
                // token is gone (the user backed out of this invisible activity
                // while the chat query ran) throws BadTokenException
                if (isFinishing || isDestroyed) return@runOnUiThread
                showTargetPicker(R.string.share_to, labels, ids, onCancel = { finish() }) {
                    share(it, text, streams, mime)
                }
            }
        }
    }

    private fun extraStreams(intent: Intent, action: String?): List<Uri> {
        @Suppress("DEPRECATION")
        return if (action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().filterNotNull()
        } else {
            listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
    }

    private fun failAndFinish(msgRes: Int) {
        if (!isFinishing) Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun share(chatIds: List<String>, text: String?, streams: List<Uri>, mime: String) {
        if (chatIds.isEmpty()) { finish(); return }
        // Io.files, not Io.executor: the loop below copies each shared item once
        // per target chat, and on the shared serial worker that stalled every
        // other screen's DB reads for the whole share.
        Io.files.execute {
            if (streams.isNotEmpty()) {
                // A send deletes its own cacheDir staging file, so every (item,
                // chat) pair needs its own copy of the master.
                var staged = 0
                var attempted = 0
                // the caption used to ride item 0 unconditionally, so when that
                // item failed to stage the user's text was silently sent to no one
                val captioned = HashSet<String>()
                for ((itemIndex, stream) in streams.withIndex()) {
                    val name = uriDisplayName(stream) ?: "shared"
                    // Per item, not the intent's type: a multi-item share of
                    // mixed content carries "*/*", and Bridge dispatches on the
                    // mime — so every shared photo and video went out as a
                    // document.
                    val itemMime = contentResolver.getType(stream) ?: mime
                    val master = copyUriToCache(stream, "share", "0_${itemIndex}_$name")
                    if (master == null) {
                        attempted += chatIds.size
                        continue
                    }
                    chatIds.forEachIndexed { i, chatId ->
                        attempted++
                        val local = duplicateInCache(master, name, itemIndex, i + 1)
                            ?: return@forEachIndexed
                        staged++
                        val caption =
                            if (!text.isNullOrEmpty() && captioned.add(chatId)) text else ""
                        Bridge.sendFile(chatId, local.absolutePath, name, itemMime, caption)
                    }
                    master.delete()
                }
                if (staged == 0) {
                    runOnUiThread { failAndFinish(R.string.share_failed) }
                    return@execute
                }
                // a partial failure used to be invisible: the user saw the normal
                // "Sending…" toast and never learned some chats got nothing
                if (staged < attempted) {
                    runOnUiThread {
                        if (!isFinishing) {
                            Toast.makeText(
                                this,
                                getString(R.string.share_partial, staged, attempted),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        finish()
                    }
                    return@execute
                }
            } else if (!text.isNullOrBlank()) {
                for (chatId in chatIds) Bridge.sendText(chatId, text)
            } else {
                runOnUiThread { finish() }
                return@execute
            }
            runOnUiThread {
                if (!isFinishing) {
                    Toast.makeText(this, R.string.share_sending, Toast.LENGTH_SHORT).show()
                }
                if (chatIds.size == 1) {
                    val open = Intent(this, ChatActivity::class.java)
                    open.putExtra("chatId", chatIds[0])
                    startActivity(open)
                }
                finish()
            }
        }
    }

    private fun duplicateInCache(src: File, name: String, item: Int, target: Int): File? {
        val out = stagingFile("share", "${item}_${target}_$name")
        return try {
            src.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        } catch (_: Exception) {
            // a half-written copy left behind survives until the 24h startup
            // sweep, and every retried share adds another
            out.delete()
            null
        }
    }
}
