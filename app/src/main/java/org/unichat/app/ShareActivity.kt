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

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val streams = extraStreams(intent, action)
        val mime = intent.type ?: "*/*"
        if (!text.isNullOrBlank()) LinkPreview.prefetch(this, text)

        io.execute {
            val (labels, ids) = targetChoices()
            if (ids.isEmpty()) {
                runOnUiThread { failAndFinish(R.string.no_chats) }
                return@execute
            }
            runOnUiThread {
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
        Io.files.execute {
            if (streams.isNotEmpty()) {
                var staged = 0
                var attempted = 0
                val captioned = HashSet<String>()
                for ((itemIndex, stream) in streams.withIndex()) {
                    val name = uriDisplayName(stream) ?: "shared"
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
                if (staged < attempted) {
                    runOnUiThread {
                        if (!isFinishing) {
                            Toast.makeText(
                                this,
                                resources.getQuantityString(
                                    R.plurals.share_partial, attempted, staged, attempted,
                                ),
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
            out.delete()
            null
        }
    }
}
