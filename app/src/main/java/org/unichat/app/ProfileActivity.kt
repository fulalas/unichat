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
    private lateinit var selfId: String
    private var proto: String = ProtoPicker.WA
    private val isTg get() = proto == ProtoPicker.TG
    private var name: String = ""
    private var about: String = ""

    private val pickPhoto = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onPhotoPicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proto = intent.getStringExtra("proto") ?: ProtoPicker.WA
        // this screen belongs to one account, so it wears that
        // protocol's accent; must precede any view inflation
        applyProtocolTheme(isTg)
        setContentView(R.layout.activity_profile)
        supportActionBar?.apply {
            title = getString(R.string.profile) + " — " + ProtoPicker.label(this@ProfileActivity, proto)
            setDisplayHomeAsUpEnabled(true)
        }
        if (!Bridge.init(this)) { finish(); return }
        selfId = Bridge.selfId(proto)

        avatar = findViewById(R.id.avatar)
        valueName = findViewById(R.id.valueName)
        valueAbout = findViewById(R.id.valueAbout)
        valuePhone = findViewById(R.id.valuePhone)

        avatar.clipToOutline = true
        avatar.outlineProvider = ViewOutlineProvider.BACKGROUND

        avatar.setOnClickListener { pickPhoto.launch("image/*") }
        findViewById<View>(R.id.editPhoto).setOnClickListener { pickPhoto.launch("image/*") }
        findViewById<View>(R.id.rowName).setOnClickListener { editName() }
        findViewById<View>(R.id.rowAbout).setOnClickListener { editAbout() }

        // the profile name is our push name (shown to everyone), not the
        // locally-saved contact name
        name = Bridge.myName(proto)
        valueName.text = name
        valuePhone.text = if (isTg) Tg.myPhone() else formatPhone(selfId)
        loadAvatar()
        loadAbout()
    }

    private fun formatPhone(id: String): String =
        if (isPhoneId(id) && id.substringBefore('@').isNotEmpty()) phoneLabel(id) else ""

    /** Pixel size of the 200dp header, the most the avatar is ever shown at.
     *  Decoding to it instead of full resolution avoids allocating an entire
     *  server-sized photo (up to 8 MiB of source) just to draw it small. */
    private val avatarPx by lazy { (200 * resources.displayMetrics.density).toInt() }

    private fun loadAvatar() {
        io.execute {
            var path = Bridge.getAvatarFullPath(selfId)
            if (path.isEmpty()) path = Bridge.getAvatarPath(selfId)
            val bmp = if (path.isEmpty()) null else ImageLoader.decodeSampled(path, avatarPx)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (bmp != null) showAvatar(bmp) else avatar.setImageResource(R.drawable.ic_person)
            }
        }
    }

    private fun showAvatar(bmp: Bitmap) {
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        avatar.setImageBitmap(bmp)
    }

    private fun loadAbout() {
        Bridge.fetchMyAbout(proto) { text ->
            if (isFinishing) return@fetchMyAbout
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
        Bridge.setMyName(proto, text) { ok -> onNameApplied(text, ok) }
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
        Bridge.setAbout(proto, text) { ok -> onAboutApplied(text, ok) }
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
            Bridge.setProfilePicture(proto, file.absolutePath) { ok ->
                // This callback is posted to the MAIN thread by the bridge, so
                // decode and delete on a worker and only touch views back here.
                if (!ok) {
                    file.delete()
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
            // decode bounds first, then downsample, so a huge image never loads
            // full-size into memory
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

    // Largest power-of-two subsample that keeps BOTH dimensions >= target.
    // Deliberately different from ImageLoader.sampleSize, which uses `||`
    // because it decodes for display: here the result is centre-cropped to a
    // square and scaled to AVATAR_PX, so `||` on a panoramic source would leave
    // a crop far smaller than AVATAR_PX to upscale from.
    private fun sampleSizeFor(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
        return s
    }

    companion object {
        private const val AVATAR_PX = 640
    }
}
