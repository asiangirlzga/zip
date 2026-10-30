package com.example.kbz

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val REQ_FILES = 1
    private val REQ_FOLDER = 2
    private val REQ_SAVE = 3
    private val REQ_KBZ = 4
    private val REQ_OUT_TREE = 5
    private val REQ_VIDEO = 6
    private val REQ_VIDEO_SAVE = 7

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var resultCard: LinearLayout
    private lateinit var radioFast: RadioButton
    private lateinit var radioSmall: RadioButton
    private lateinit var vidBalanced: RadioButton
    private lateinit var vidSmall: RadioButton
    private lateinit var vidTiny: RadioButton
    private var videoUri: Uri? = null
    private var videoSize = 0L
    private var videoName = "video"
    private var transformer: Transformer? = null
    private val ui = Handler(Looper.getMainLooper())
    private val buttons = ArrayList<Button>()

    private var pending: List<KbzCodec.Entry> = emptyList()
    private var kbzUri: Uri? = null

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = resources.getColor(id, theme)

    private fun rounded(color: Int, radiusDp: Int = 16) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun label(text: String, size: Float, colorId: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(col(colorId))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun actionButton(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else col(R.color.accent))
        background = if (primary) rounded(col(R.color.accent), 14) else GradientDrawable().apply {
            setColor(col(R.color.card))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), col(R.color.accent))
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(10) }
        buttons.add(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }

        root.addView(label("KB Compressor", 28f, R.color.text, true))
        root.addView(label(
            "Lossless compression for files and folders.\nRepeating data can shrink to a few KB; " +
                    "already-compressed files (MP4, JPG, ZIP) stay about the same.",
            14f, R.color.subtext
        ).apply { setPadding(0, dp(6), 0, dp(16)) })

        // compression level card
        val levelCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(col(R.color.card))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        levelCard.addView(label("Compression level", 14f, R.color.subtext, true))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        radioSmall = RadioButton(this).apply {
            text = "Smaller (XZ / LZMA2) - slower, best ratio"
            setTextColor(col(R.color.text))
            id = View.generateViewId()
        }
        radioFast = RadioButton(this).apply {
            text = "Faster (Deflate) - quicker, less shrink"
            setTextColor(col(R.color.text))
            id = View.generateViewId()
        }
        group.addView(radioSmall)
        group.addView(radioFast)
        group.check(radioSmall.id)
        levelCard.addView(group)
        root.addView(levelCard)

        val videoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(col(R.color.card))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        videoCard.addView(label("Video quality (H.265)", 14f, R.color.subtext, true))
        val vg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        fun vr(t: String) = RadioButton(this).apply {
            text = t; setTextColor(col(R.color.text)); id = View.generateViewId()
        }
        vidBalanced = vr("Balanced - keep resolution")
        vidSmall = vr("Small - 720p")
        vidTiny = vr("Tiny - 480p, lowest bitrate")
        vg.addView(vidBalanced); vg.addView(vidSmall); vg.addView(vidTiny)
        vg.check(vidSmall.id)
        videoCard.addView(vg)
        root.addView(videoCard)
        root.addView(actionButton("Compress a video (H.265)", true) { pickVideo() })

        root.addView(actionButton("Compress files", true) { pickFiles() })
        root.addView(actionButton("Compress a folder", true) { pickFolder() })
        root.addView(actionButton("Restore a .kbz archive", false) { pickKbz() })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        root.addView(progress)

        status = label("", 14f, R.color.subtext).apply { setPadding(0, dp(6), 0, dp(10)) }
        root.addView(status)

        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(col(R.color.card))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        root.addView(resultCard)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    // ------------------------------------------------------------------ pickers

    private fun pickFiles() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQ_FILES)
    }

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
    }

    private fun pickVideo() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }, REQ_VIDEO)
    }

    private fun pickKbz() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_KBZ)
    }

    private fun askSave(name: String) {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        }, REQ_SAVE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        try {
            when (requestCode) {
                REQ_FILES -> {
                    val uris = ArrayList<Uri>()
                    data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
                    if (uris.isEmpty()) data.data?.let { uris.add(it) }
                    if (uris.isEmpty()) return
                    pending = buildFileEntries(uris)
                    val name = if (pending.size == 1) pending[0].path + ".kbz" else "archive.kbz"
                    askSave(name)
                }
                REQ_FOLDER -> {
                    val tree = data.data ?: return
                    val (rootName, list) = listTree(tree)
                    if (list.isEmpty()) { status.text = "That folder is empty."; return }
                    pending = list
                    askSave("$rootName.kbz")
                }
                REQ_SAVE -> data.data?.let { runCompress(it) }
                REQ_VIDEO -> {
                    val u = data.data ?: return
                    videoUri = u
                    contentResolver.query(u, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sz = c.getColumnIndex(OpenableColumns.SIZE)
                            if (n >= 0) videoName = c.getString(n)
                            if (sz >= 0 && !c.isNull(sz)) videoSize = c.getLong(sz)
                        }
                    }
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_TITLE, videoName.substringBeforeLast('.') + "_h265.mp4")
                    }, REQ_VIDEO_SAVE)
                }
                REQ_VIDEO_SAVE -> data.data?.let { runVideo(it) }
                REQ_KBZ -> {
                    kbzUri = data.data
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        putExtra(Intent.EXTRA_TITLE, "Choose where to restore")
                    }, REQ_OUT_TREE)
                }
                REQ_OUT_TREE -> data.data?.let { runRestore(it) }
            }
        } catch (e: Exception) {
            status.text = "Error: ${e.message}"
        }
    }

    // ------------------------------------------------------------------ building entries

    private fun buildFileEntries(uris: List<Uri>): List<KbzCodec.Entry> {
        val used = HashSet<String>()
        val list = ArrayList<KbzCodec.Entry>()
        for (u in uris) {
            var name = "file"
            var size = 0L
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
            name = name.replace('/', '_')
            var unique = name
            var k = 1
            while (!used.add(unique)) {
                val dot = name.lastIndexOf('.')
                unique = if (dot > 0) name.substring(0, dot) + " (" + k + ")" + name.substring(dot) else "$name ($k)"
                k++
            }
            list.add(KbzCodec.Entry(unique, size) { contentResolver.openInputStream(u)!! })
        }
        return list
    }

    private class Row(val id: String, val name: String, val mime: String, val size: Long)

    private fun listChildren(tree: Uri, docId: String): List<Row> {
        val rows = ArrayList<Row>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        contentResolver.query(
            uri,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                rows.add(Row(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) 0L else c.getLong(3)))
            }
        }
        return rows
    }

    private fun listTree(tree: Uri): Pair<String, List<KbzCodec.Entry>> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var rootName = "folder"
        contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
            arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) rootName = it.getString(0) }
        rootName = rootName.replace('/', '_')

        val out = ArrayList<KbzCodec.Entry>()
        fun walk(docId: String, prefix: String) {
            for (r in listChildren(tree, docId)) {
                val path = "$prefix/${r.name.replace('/', '_')}"
                if (r.mime == Document.MIME_TYPE_DIR) {
                    walk(r.id, path)
                } else {
                    val u = DocumentsContract.buildDocumentUriUsingTree(tree, r.id)
                    out.add(KbzCodec.Entry(path, r.size) { contentResolver.openInputStream(u)!! })
                }
            }
        }
        walk(rootId, rootName)
        return rootName to out
    }

    // ------------------------------------------------------------------ running

    private fun setBusy(busy: Boolean) {
        buttons.forEach { it.isEnabled = !busy; it.alpha = if (busy) 0.5f else 1f }
        radioFast.isEnabled = !busy
        radioSmall.isEnabled = !busy
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateProgress(done: Long, total: Long) {
        if (total <= 0) return
        runOnUiThread {
            progress.progress = (done * 1000 / total).toInt()
            status.text = "${fmt(done)} of ${fmt(total)} processed"
        }
    }

    private fun runTask(label: String, block: () -> Unit) {
        setBusy(true)
        status.text = label
        progress.progress = 0
        resultCard.visibility = View.GONE
        worker.execute {
            try {
                block()
            } catch (t: Throwable) {
                runOnUiThread { status.text = "Error: ${t.message ?: t.javaClass.simpleName}" }
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }
    }

    private fun runCompress(dest: Uri) {
        val entries = pending
        val level = if (radioFast.isChecked) KbzCodec.Level.FAST else KbzCodec.Level.SMALL
        runTask("Compressing...") {
            val result = contentResolver.openOutputStream(dest)!!.use { out ->
                KbzCodec.compress(entries, out, level) { d, t -> updateProgress(d, t) }
            }
            runOnUiThread {
                progress.progress = 1000
                status.text = "Done"
                showCompressResult(result)
            }
        }
    }

    private fun runVideo(dest: Uri) {
        val src = videoUri ?: return
        val height = when {
            vidTiny.isChecked -> 480
            vidSmall.isChecked -> 720
            else -> 0
        }
        val bitrate = when {
            vidTiny.isChecked -> 700_000
            vidSmall.isChecked -> 1_500_000
            else -> 0
        }
        setBusy(true)
        status.text = "Compressing video..."
        progress.progress = 0
        resultCard.visibility = View.GONE

        val tmp = File(cacheDir, "video_out.mp4")
        if (tmp.exists()) tmp.delete()

        val enc = DefaultEncoderFactory.Builder(this).apply {
            if (bitrate > 0) {
                setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            }
        }.build()

        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(enc)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    ui.removeCallbacksAndMessages(null)
                    worker.execute {
                        try {
                            contentResolver.openOutputStream(dest)!!.use { out ->
                                tmp.inputStream().use { it.copyTo(out, 256 * 1024) }
                            }
                            val newSize = tmp.length()
                            tmp.delete()
                            runOnUiThread {
                                progress.progress = 1000
                                status.text = "Done"
                                showVideoResult(videoSize, newSize)
                                setBusy(false)
                            }
                        } catch (e: Exception) {
                            runOnUiThread { status.text = "Error: ${e.message}"; setBusy(false) }
                        }
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    ui.removeCallbacksAndMessages(null)
                    status.text = "Video error: ${exportException.message}"
                    setBusy(false)
                }
            })
            .build()
        transformer = t

        val item = MediaItem.fromUri(src)
        val effects = if (height > 0) Effects(emptyList(), listOf(Presentation.createForHeight(height))) else Effects.EMPTY
        val edited = EditedMediaItem.Builder(item).setEffects(effects).build()
        t.start(edited, tmp.absolutePath)

        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    progress.progress = holder.progress * 10
                    status.text = "Compressing video... ${holder.progress}%"
                }
                ui.postDelayed(this, 500)
            }
        }
        ui.post(poll)
    }

    private fun showVideoResult(orig: Long, now: Long) {
        resultCard.removeAllViews()
        resultCard.addView(label("Video size comparison", 18f, R.color.text, true))
        resultCard.addView(label("Original  -  ${fmt(orig)}", 14f, R.color.subtext).apply { setPadding(0, dp(10), 0, 0) })
        resultCard.addView(bar(1f, col(R.color.subtext)))
        resultCard.addView(label("H.265  -  ${fmt(now)}", 14f, R.color.subtext))
        resultCard.addView(bar(if (orig > 0) now.toFloat() / orig else 1f, col(R.color.accent)))
        val saved = if (orig > 0) (1.0 - now.toDouble() / orig) * 100.0 else 0.0
        resultCard.addView(label(
            if (saved > 0.05) "%.1f%% smaller".format(saved) else "No savings - try a lower quality level",
            20f, if (saved > 0.05) R.color.good else R.color.subtext, true
        ))
        resultCard.addView(label(
            "Video is re-encoded (lossy) and plays in any normal video player.",
            13f, R.color.subtext
        ).apply { setPadding(0, dp(8), 0, 0) })
        resultCard.visibility = View.VISIBLE
    }

    private fun runRestore(tree: Uri) {
        val src = kbzUri ?: return
        runTask("Restoring...") {
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val dirCache = HashMap<String, Uri>()
            dirCache[""] = rootUri

            val restored = contentResolver.openInputStream(src)!!.use { ins ->
                KbzCodec.extract(ins, { path -> openSink(tree, rootUri, dirCache, path) }) { d, t -> updateProgress(d, t) }
            }
            runOnUiThread {
                progress.progress = 1000
                status.text = "Done"
                showRestoreResult(restored)
            }
        }
    }

    private fun openSink(tree: Uri, rootUri: Uri, cache: HashMap<String, Uri>, path: String): OutputStream {
        val parts = path.split('/')
        var cur = ""
        var parent = rootUri
        for (d in parts.dropLast(1)) {
            cur = if (cur.isEmpty()) d else "$cur/$d"
            val p = parent
            parent = cache.getOrPut(cur) { findOrCreateDir(tree, p, d) }
        }
        val doc = DocumentsContract.createDocument(contentResolver, parent, "application/octet-stream", parts.last())
            ?: throw IOException("Cannot create ${parts.last()}")
        return contentResolver.openOutputStream(doc) ?: throw IOException("Cannot write ${parts.last()}")
    }

    private fun findOrCreateDir(tree: Uri, parent: Uri, name: String): Uri {
        val parentId = DocumentsContract.getDocumentId(parent)
        for (r in listChildren(tree, parentId)) {
            if (r.mime == Document.MIME_TYPE_DIR && r.name == name) {
                return DocumentsContract.buildDocumentUriUsingTree(tree, r.id)
            }
        }
        return DocumentsContract.createDocument(contentResolver, parent, Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Cannot create folder $name")
    }

    // ------------------------------------------------------------------ results

    private fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.2f MB".format(b / 1048576.0)
        b >= 1L shl 10 -> "%.2f KB".format(b / 1024.0)
        else -> "$b B"
    }

    private fun bar(fraction: Float, color: Int): View {
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1000f
            background = rounded(col(R.color.track), 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14)
            ).apply { topMargin = dp(4); bottomMargin = dp(10) }
        }
        val w = (fraction * 1000f).coerceIn(6f, 1000f)
        track.addView(View(this).apply {
            background = rounded(color, 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, w)
        })
        return track
    }

    private fun showCompressResult(r: KbzCodec.Result) {
        resultCard.removeAllViews()
        val orig = r.originalTotal
        val comp = r.archiveSize
        resultCard.addView(label("Size comparison", 18f, R.color.text, true))

        resultCard.addView(label("Original  -  ${fmt(orig)}", 14f, R.color.subtext).apply { setPadding(0, dp(10), 0, 0) })
        resultCard.addView(bar(1f, col(R.color.subtext)))

        resultCard.addView(label("Compressed  -  ${fmt(comp)}", 14f, R.color.subtext))
        resultCard.addView(bar(if (orig > 0) comp.toFloat() / orig else 1f, col(R.color.accent)))

        val saved = if (orig > 0) (1.0 - comp.toDouble() / orig) * 100.0 else 0.0
        resultCard.addView(label(
            if (saved > 0.05) "%.2f%% smaller".format(saved) else "No real savings (data is already compressed)",
            20f, if (saved > 0.05) R.color.good else R.color.subtext, true
        ))

        resultCard.addView(label("Files", 14f, R.color.subtext, true).apply { setPadding(0, dp(16), 0, dp(4)) })
        r.files.take(30).forEach { f ->
            resultCard.addView(label(
                "${f.path.substringAfterLast('/')}\n${fmt(f.original)} -> ${fmt(f.compressed)}  (${f.method})",
                13f, R.color.text
            ).apply { setPadding(0, dp(4), 0, dp(4)) })
        }
        if (r.files.size > 30) {
            resultCard.addView(label("+ ${r.files.size - 30} more files", 13f, R.color.subtext))
        }
        resultCard.visibility = View.VISIBLE
    }

    private fun showRestoreResult(list: List<KbzCodec.Restored>) {
        resultCard.removeAllViews()
        resultCard.addView(label("Restored ${list.size} file(s)", 18f, R.color.text, true))
        resultCard.addView(label("Total ${fmt(list.sumOf { it.size })}", 14f, R.color.subtext).apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        list.take(30).forEach { f ->
            resultCard.addView(label("${f.path}  (${fmt(f.size)})", 13f, R.color.text).apply {
                setPadding(0, dp(3), 0, dp(3))
            })
        }
        if (list.size > 30) resultCard.addView(label("+ ${list.size - 30} more", 13f, R.color.subtext))
        resultCard.visibility = View.VISIBLE
    }
}
