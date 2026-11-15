package io.github.abhishekkaisar.glassos

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * "Update available": a glass card in the middle of the screen, in the style of [AppMenu], with the installed and
 * the new version, the release notes, and "Update now" and "Later" as rows the remote can focus. While the APK
 * downloads the card shows the progress, and "Later" becomes "Cancel".
 */
class UpdateDialog(private val act: HomeScreen) : FrameLayout(act.ctx) {
    var isOpen = false
        private set
    private val scrim = View(context).apply { setBackgroundColor(0x80000000.toInt()) }
    val panel = GlassBox(context, dp(30f)).apply {
        glass.tint = 0.10f
        glass.dark = 0.30f
        glass.sheen = 0.10f
        shadowAlpha = 0.5f
    }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val title = text("Update available", 24f, 0xFFFFFFFF.toInt(), Fonts.medium).apply { gravity = Gravity.CENTER }
    private val versions = text("", 17f, 0xE6FFFFFF.toInt(), Fonts.regular).apply { gravity = Gravity.CENTER }
    private val notesLabel = text("What's new", 14f, 0x99FFFFFF.toInt(), Fonts.medium)
    private val notes = text("", 15f, 0xB3FFFFFF.toInt(), Fonts.regular).apply {
        maxLines = 8
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val status = text("", 15f, 0xE6FFFFFF.toInt(), Fonts.regular).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val bar = Bar(context).apply { visibility = View.GONE }
    private val updateRow = RowView(context, "Update now")
    private val laterRow = RowView(context, "Later")

    private var rel: Updater.Release? = null
    private var job: Updater.Download? = null
    private var downloading = false
    private var onClosed: (() -> Unit)? = null

    init {
        visibility = View.GONE
        clipChildren = false
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel, LayoutParams(dpi(560f), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val pad = dpi(24f)
        list.setPadding(pad, dpi(20f), pad, dpi(14f))
        panel.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        list.addView(title, LinearLayout.LayoutParams(match, wrap))
        list.addView(versions, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(6f) })
        list.addView(notesLabel, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(16f); leftMargin = dpi(4f) })
        list.addView(notes, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(4f); leftMargin = dpi(4f); rightMargin = dpi(4f) })
        list.addView(status, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(16f) })
        list.addView(bar, LinearLayout.LayoutParams(match, dpi(6f)).apply { topMargin = dpi(8f); leftMargin = dpi(4f); rightMargin = dpi(4f) })
        val rowLp = { LinearLayout.LayoutParams(match, dpi(48f)).apply { topMargin = dpi(5f) } }
        updateRow.titleView.gravity = Gravity.CENTER
        laterRow.titleView.gravity = Gravity.CENTER
        list.addView(updateRow, rowLp().apply { topMargin = dpi(18f) })
        list.addView(laterRow, rowLp())

        updateRow.onClick = { startUpdate() }
        laterRow.onClick = {
            if (downloading) cancelDownload()
            else {
                rel?.let { act.prefs.updateSkipped = it.version }
                close()
            }
        }
    }

    private fun text(s: String, size: Float, color: Int, face: android.graphics.Typeface) = TextView(context).apply {
        text = s
        textSize = size
        typeface = face
        setTextColor(color)
    }

    fun open(r: Updater.Release, closed: () -> Unit) {
        if (isOpen) return
        isOpen = true
        rel = r
        onClosed = closed
        versions.text = "Installed ${BuildConfig.VERSION_NAME}   →   New ${r.version}"
        val body = r.notes.replace("\r", "").replace(Regex("(?m)^#+\\s*"), "").replace("**", "").ifBlank { "No release notes." }
        notes.text = body
        resetUi()
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(160).withEndAction { invalidateDeep(panel) }.start()
        updateRow.requestFocus()
        invalidateDeep(panel)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        job?.cancel()
        job = null
        downloading = false
        animate().cancel()
        visibility = View.GONE
        val c = onClosed
        onClosed = null
        c?.invoke()
    }

    /** Back and Menu: stops a download, or closes like "Later" (without remembering the version as skipped). */
    fun back() {
        if (downloading) cancelDownload() else close()
    }

    fun release() {
        job?.cancel()
        job = null
    }

    /** D-pad inside the card only; Up and Down switch between its two rows and Left and Right do nothing. */
    fun onKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    val rows = listOf(updateRow, laterRow).filter { it.visibility == View.VISIBLE }
                    val i = rows.indexOfFirst { it.hasFocus() }
                    val next = if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) i + 1 else i - 1
                    if (next in rows.indices) rows[next].requestFocus()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
        }
        return false
    }

    private fun resetUi() {
        downloading = false
        status.visibility = View.GONE
        bar.visibility = View.GONE
        bar.fraction = 0f
        notesLabel.visibility = View.VISIBLE
        notes.visibility = View.VISIBLE
        updateRow.visibility = View.VISIBLE
        laterRow.titleView.text = "Later"
    }

    private fun startUpdate() {
        val r = rel ?: return
        // Android 8 and later: GlassOS must be allowed to install apps first. Come back and choose Update now again.
        if (!Updater.canInstall(context)) {
            Toast.makeText(context, "Allow GlassOS to install apps, then come back and choose Update now", Toast.LENGTH_LONG).show()
            Updater.openInstallSettings(context)
            return
        }
        downloading = true
        notesLabel.visibility = View.GONE
        notes.visibility = View.GONE
        updateRow.visibility = View.GONE
        status.text = "Downloading ${r.version}… 0 %"
        status.visibility = View.VISIBLE
        bar.fraction = 0f
        bar.visibility = View.VISIBLE
        laterRow.titleView.text = "Cancel"
        laterRow.requestFocus()
        job = Updater.download(context, r, { p ->
            if (isOpen && downloading) { status.text = "Downloading ${r.version}… $p %"; bar.fraction = p / 100f }
        }, { file, error ->
            if (!isOpen) return@download
            job = null
            if (file == null) {
                Toast.makeText(context, error ?: "Download failed", Toast.LENGTH_LONG).show()
                resetUi()
                updateRow.requestFocus()
            } else {
                status.text = "Starting the installer…"
                if (!Updater.install(context, file)) Toast.makeText(context, "This TV has no app installer", Toast.LENGTH_LONG).show()
                close()
            }
        })
        invalidateDeep(panel)
    }

    private fun cancelDownload() {
        job?.cancel()
        job = null
        resetUi()
        updateRow.requestFocus()
        invalidateDeep(panel)
    }

    /** A thin rounded progress bar. */
    private class Bar(ctx: android.content.Context) : View(ctx) {
        var fraction = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            p.color = 0x33FFFFFF
            r.set(0f, 0f, width.toFloat(), h)
            c.drawRoundRect(r, h / 2, h / 2, p)
            p.color = 0xF2FFFFFF.toInt()
            r.set(0f, 0f, maxOf(h, width * fraction), h)
            c.drawRoundRect(r, h / 2, h / 2, p)
        }
    }
}
