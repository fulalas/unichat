package org.unichat.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import kotlin.math.min

class ProfileActivity : BaseActivity() {

    private lateinit var avatar: ImageView
    private lateinit var valueName: TextView
    private lateinit var valueAbout: TextView
    private lateinit var valuePhone: TextView

    private val io = Io.executor
    // Empty until loadIdentity answers: resolving it is a blocking bridge call,
    // so it cannot be read on the main thread during onCreate.
    private var selfId: String = ""
    private var proto: String = ProtoPicker.WA
    private val account get() = Accounts.of(proto)
    private var name: String = ""
    private var about: String = ""

    private val pickPhoto = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onPhotoPicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proto = intent.getStringExtra("proto") ?: ProtoPicker.WA
        // must precede any view inflation
        applyProtocolTheme(proto)
        setContentView(R.layout.activity_profile)
        supportActionBar?.apply {
            title = getString(
                R.string.title_with_account,
                getString(R.string.profile), account.label(this@ProfileActivity),
            )
            setDisplayHomeAsUpEnabled(true)
        }
        if (!Bridge.init(this)) { finish(); return }

        avatar = findViewById(R.id.avatar)
        valueName = findViewById(R.id.valueName)
        valueAbout = findViewById(R.id.valueAbout)
        valuePhone = findViewById(R.id.valuePhone)

        avatar.clipToOutline = true
        avatar.outlineProvider = ViewOutlineProvider.BACKGROUND

        // Publishing an avatar means encrypting it under the profile key and
        // uploading to the CDN, which is not implemented for Signal — so the
        // picker is not offered rather than silently doing nothing.
        if (account.supportsProfilePicture) {
            avatar.setOnClickListener { pickPhoto.launch("image/*") }
        }
        // Same guard as the avatar itself: without it, picking a photo on the
        // Signal screen fell through to the WhatsApp path and replaced the
        // WhatsApp account's picture.
        val editPhoto = findViewById<View>(R.id.editPhoto)
        if (account.supportsProfilePicture) {
            editPhoto.setOnClickListener { pickPhoto.launch("image/*") }
        } else {
            editPhoto.visibility = View.GONE
        }
        findViewById<View>(R.id.rowName).setOnClickListener { editName() }
        findViewById<View>(R.id.rowAbout).setOnClickListener { editAbout() }

        loadIdentity()
        loadAbout()
    }

    // The three calls below are blocking: WhatsApp's name is a gomobile hop
    // that takes the process-wide Go lock, and Signal's id and number are JNI
    // calls with a SQLite read behind the name. Run on the main thread they
    // froze the screen for as long as the bridge took to answer.
    private fun loadIdentity() {
        io.execute {
            val id = account.selfId()
            // the profile name is our push name (shown to everyone), not the
            // locally-saved contact name
            val myName = account.myName()
            // Signal ids are ACIs, not phone JIDs, so each account is asked for
            // its own number rather than it being parsed back out of the id.
            val phone = account.myPhone()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                selfId = id
                name = myName
                valueName.text = myName
                valuePhone.text = phone
                loadAvatar()
            }
        }
    }

    // Decoding to the header size instead of full resolution avoids allocating
    // an entire server-sized photo (up to 8 MiB of source) just to draw it
    // small.
    private val avatarPx by lazy { (200 * resources.displayMetrics.density).toInt() }

    private fun loadAvatar() = AvatarLoader.loadBig(this, selfId, avatarPx) { bmp ->
        if (bmp != null) showAvatar(bmp) else avatar.setImageResource(R.drawable.ic_person)
    }

    private fun showAvatar(bmp: Bitmap) {
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        avatar.setImageBitmap(bmp)
    }

    private fun loadAbout() {
        account.fetchAbout { text ->
            if (isFinishing) return@fetchAbout
            about = text
            valueAbout.text = text
        }
    }

    private fun editName() {
        val input = EditText(this).apply {
            setText(name)
            hint = getString(R.string.profile_name)
            setSingleLine()
            setSelection(text.length)
        }
        editDialog(R.string.profile_name, input) {
            val text = input.text.toString().trim()
            if (text.isNotEmpty() && text != name) applyName(text)
        }
    }

    private fun applyName(text: String) {
        account.setMyName(text) { ok -> onNameApplied(text, ok) }
    }

    private fun onNameApplied(text: String, ok: Boolean) {
        if (isFinishing) return
        if (ok) {
            name = text
            valueName.text = text
        } else {
            Toast.makeText(this, R.string.profile_name_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun editAbout() {
        val input = EditText(this).apply {
            setText(about)
            hint = getString(R.string.profile_about_hint)
            setSelection(text.length)
        }
        editDialog(R.string.profile_about, input) {
            val text = input.text.toString()
            if (text != about) applyAbout(text)
        }
    }

    private fun editDialog(titleRes: Int, input: EditText, onOk: () -> Unit) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyAbout(text: String) {
        account.setAbout(text) { ok -> onAboutApplied(text, ok) }
    }

    private fun onAboutApplied(text: String, ok: Boolean) {
        if (isFinishing) return
        if (ok) {
            about = text
            valueAbout.text = text
        } else {
            Toast.makeText(this, R.string.profile_about_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onPhotoPicked(uri: Uri) {
        io.execute {
            val file = prepareAvatar(uri)
            if (file == null) {
                runOnUiThread {
                    if (!isFinishing) Toast.makeText(this, R.string.profile_photo_failed, Toast.LENGTH_SHORT).show()
                }
                return@execute
            }
            account.setProfilePicture(file.absolutePath) { ok ->
                // This callback is posted to the MAIN thread by the bridge, so
                // decode and delete on a worker and only touch views back here.
                if (!ok) {
                    io.execute { file.delete() }
                    if (!isFinishing) {
                        Toast.makeText(this, R.string.profile_photo_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@setProfilePicture
                }
                io.execute {
                    // show the just-uploaded image directly: a re-fetch can
                    // briefly still return the server's previous picture
                    val bmp = ImageLoader.decodeSampled(file.absolutePath, avatarPx)
                    file.delete()
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (bmp != null) showAvatar(bmp)
                        // the bridge replaced the on-disk copy; drop the in-memory
                        // one too so the chat list / toolbar don't show the old one
                        AvatarLoader.invalidate(selfId)
                        Toast.makeText(this, R.string.profile_photo_updated, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun prepareAvatar(uri: Uri): File? {
        // Catch Throwable, not just Exception: decoding a large gallery photo can
        // throw OutOfMemoryError (an Error), which we must not let crash the app.
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, AVATAR_PX)
            }
            val src = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null
            val side = min(src.width, src.height)
            val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(cropped, AVATAR_PX, AVATAR_PX, true)
            // a STAGING_PREFIXES name, so Bridge.cleanStaleCache's startup sweep
            // reclaims it if the upload callback never runs (process death)
            val out = stagingFile("avatar", "photo.jpg")
            // compress() reports failure by returning false; ignoring it meant a
            // truncated or empty JPEG was handed to the bridge as a success and
            // the server rejected it, leaving only the generic failure toast
            val encoded = out.outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
            if (!encoded) {
                out.delete()
                return null
            }
            out
        } catch (e: Throwable) {
            null
        }
    }

    // Requires BOTH dimensions >= target, where ImageLoader.sampleSize uses
    // `||` because it decodes for display: here the result is centre-cropped to
    // a square and scaled to AVATAR_PX, so `||` on a panoramic source would
    // leave a crop far smaller than AVATAR_PX to upscale from.
    private fun sampleSizeFor(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
        return s
    }

    companion object {
        private const val AVATAR_PX = 640
    }
}
