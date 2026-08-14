package org.unichat.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.Toast
import java.io.File

/** Fullscreen, uncropped image viewer with a share button. Tap image to close. */
class ImageViewActivity : BaseActivity() {

    // fullscreen by design: it hides the system bars and the image should fill
    // the whole window
    override val padForSystemBars: Boolean = false

    private lateinit var path: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the picture belongs to a chat, and its controls take that chat's
        // accent; unknown (no chat passed) keeps the app's own colours
        val fromChat = intent.getStringExtra("chatId").orEmpty()
        if (fromChat.isNotEmpty() && !Tg.isTgId(fromChat)) {
            theme.applyStyle(R.style.ThemeOverlay_UniChat_Wa, true)
        }
        supportActionBar?.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }

        path = intent.getStringExtra("path") ?: run { finish(); return }
        setContentView(R.layout.activity_image)

        val imageView = findViewById<ZoomImageView>(R.id.image)
        imageView.onSingleTap = { finish() }
        findViewById<ImageButton>(R.id.shareButton).setOnClickListener { share() }

        // Size the decode to the display (with headroom for pinch zoom) instead
        // of a fixed 2160: sampleSize keeps BOTH dimensions above the target, so
        // a 4000x3000 photo used to land on inSampleSize 1 and allocate ~48MB —
        // an OOM risk for a bitmap ~11x the pixels the screen can show.
        val metrics = resources.displayMetrics
        val target = maxOf(metrics.widthPixels, metrics.heightPixels) * 2

        // shared worker instead of a fresh (never-shut-down) executor per open
        Io.executor.execute {
            val bitmap = ImageLoader.decodeSampled(path, target)
            runOnUiThread {
                // the decode outlives a quick Back press; don't touch a torn-down
                // screen (and don't re-finish an already finishing one)
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bitmap != null) imageView.setImageBitmap(bitmap) else finish()
            }
        }
    }

    private fun share() {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val (uri, mime) = providedFile(file, "image/*")
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = mime
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }
}
