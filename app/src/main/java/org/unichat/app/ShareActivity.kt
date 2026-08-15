package org.unichat.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.File

/**
 * Receives ACTION_SEND / ACTION_SEND_MULTIPLE from other apps and forwards to
 * the chosen chats.
 */
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

    /** The shared content URIs: one for SEND, a list for SEND_MULTIPLE. */
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
                // Each shared item is read from its content URI exactly once into
                // a master file; a send deletes its own cacheDir staging file, so
                // every (item, chat) pair gets its own local copy.
                var staged = 0
                var attempted = 0
                for ((itemIndex, stream) in streams.withIndex()) {
                    val name = uriDisplayName(stream) ?: "shared"
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
                        // caption only on the first item, like WhatsApp
                        val caption = if (itemIndex == 0) text.orEmpty() else ""
                        Bridge.sendFile(chatId, local.absolutePath, name, mime, caption)
                    }
                    master.delete() // only the per-chat copies are sent
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
                // the sends are only enqueued here and continue in the Bridge
                // singleton; a failure surfaces later as its own toast
                if (!isFinishing) {
                    Toast.makeText(this, R.string.share_sending, Toast.LENGTH_SHORT).show()
                }
                // jump into the chat only when there's a single, unambiguous target
                if (chatIds.size == 1) {
                    val open = Intent(this, ChatActivity::class.java)
                    open.putExtra("chatId", chatIds[0])
                    startActivity(open)
                }
                finish()
            }
        }
    }

    // Duplicates an already-staged master file into a fresh per-target staging
    // file (local disk copy — no second read of the original content URI).
    // The indices keep names unique across items and share targets.
    private fun duplicateInCache(src: File, name: String, item: Int, target: Int): File? {
        val out = stagingFile("share", "${item}_${target}_$name")
        return try {
            src.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        } catch (_: Exception) {
            // a half-written copy left behind survives until the 24h startup
            // sweep, and every retried share adds another (copyUriToCache
            // unlinks on failure for the same reason)
            out.delete()
            null
        }
    }
}
