package com.arjun.gander

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.get
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * The spike for PdfRenderService, in debug builds only. It opens a PDF through PdfRenderClient
 * and the isolated renderer and logs what came back under the tag PdfSpike, so a run over adb
 * can be read from logcat. A document comes as the intent's data (from adb, or from another app
 * sharing one), from the file picker, or as every PDF in a folder picked as a tree.
 *
 *   adb shell am start -a com.arjun.gander.PDF_SPIKE -d <content uri> -t application/pdf \
 *     --grant-read-uri-permission -n com.arjun.gander.debug/com.arjun.gander.PdfSpikeActivity
 *   adb shell am start -a com.arjun.gander.PDF_SPIKE --es pick file|tree \
 *     -n com.arjun.gander.debug/com.arjun.gander.PdfSpikeActivity
 *
 * Add --ez all true to draw every page rather than the first, and --es fault crash-draw (or
 * another of FaultyPdfRenderService's) to bind a renderer that fails on purpose.
 */
class PdfSpikeActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var client: PdfRenderClient
    private var allPages = false
    private var edgesChecked = false
    private lateinit var log: TextView
    private lateinit var image: ImageView

    // A debug build's test bench, which no reader sees, so its two labels stay untranslated
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(Button(this).apply { text = "Pick a PDF"; setOnClickListener { pick("file") } })
        column.addView(Button(this).apply { text = "Pick a folder"; setOnClickListener { pick("tree") } })
        log = TextView(this).apply { setTextIsSelectable(true) }
        image = ImageView(this).apply { adjustViewBounds = true }
        column.addView(log)
        column.addView(image)
        setContentView(ScrollView(this).apply { addView(column) })
        val fault = intent.getStringExtra("fault")
        val service = if (fault == null) Intent(this, PdfRenderService::class.java)
        else Intent(this, FaultyPdfRenderService::class.java).setAction(fault)
        client = PdfRenderClient(this, service) { crashed -> report("GONE crashed=$crashed") }
        client.bind()
        report("BIND ${service.component?.shortClassName} fault=$fault")
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        client.close()
        worker.shutdown()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        allPages = intent.getBooleanExtra("all", false)
        val what = intent.getStringExtra("pick")
        if (what != null) pick(what) else intent.data?.let { enqueue("intent", it) }
    }

    private fun pick(what: String) {
        val request = if (what == "tree") Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
        startActivityForResult(request, if (what == "tree") PICK_TREE else PICK_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return report("PICKED nothing")
        if (requestCode == PICK_FILE) {
            enqueue("picker", uri)
        } else {
            worker.execute {
                val found = pdfsIn(uri)
                report("TREE ${uri.authority} holds ${found.size} PDFs")
                runOnUiThread { found.forEach { enqueue("tree", it) } }
            }
        }
    }

    private fun enqueue(source: String, uri: Uri) {
        val all = allPages
        worker.execute { open(source, uri, all) }
    }

    private fun open(source: String, uri: Uri, all: Boolean) {
        val started = SystemClock.elapsedRealtime()
        val line = StringBuilder("RESULT source=$source authority=${uri.authority} name=${nameOf(uri)}")
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) {
                line.append(" descriptor=none")
            } else {
                line.append(" statSize=${pfd.statSize}")
                val pages = client.open(pfd)
                val said = if (pages == PdfRenderClient.GONE) "gone" else "$pages"
                line.append(" open=$said")
                report("OPENED $said ms=${SystemClock.elapsedRealtime() - started}")
                if (pages > 0) drawPages(if (all) pages else 1, line)
                if (pages > 0 && !edgesChecked) {
                    edgesChecked = true
                    report(edges(pages))
                }
            }
        } catch (e: Exception) {
            line.append(" error=${e.javaClass.name}: ${e.message}")
        }
        line.append(" ms=${SystemClock.elapsedRealtime() - started}")
        report(line.toString())
    }

    private fun drawPages(count: Int, line: StringBuilder) {
        val width = resources.displayMetrics.widthPixels
        val started = SystemClock.elapsedRealtime()
        var drawn = 0
        for (n in 0 until count) {
            val size = client.pageSize(n)
            val dims = size?.let { drawSize(width, it[0], it[1]) }
            if (size == null || dims == null) {
                line.append(" page$n=nosize")
                return
            }
            val (w, h) = dims[0] to dims[1]
            val bytes = client.render(n, w, h)
            if (bytes == null) {
                line.append(" page$n=short at ${w}x$h")
                return
            }
            if (n == 0) {
                line.append(" page0=${size[0]}x${size[1]}pt drawn=${w}x$h bytes=${bytes.size}")
                val bitmap = bitmapOf(bytes, w, h)
                line.append(" centre=#%08x".format(bitmap[w / 2, h / 2]))
                runOnUiThread { image.setImageBitmap(bitmap) }
            }
            drawn++
        }
        line.append(" drew=$drawn pagesMs=${SystemClock.elapsedRealtime() - started}")
    }

    /** Requests the client refuses without asking the renderer, each of which gives null. */
    private fun edges(pages: Int): String =
        "EDGE sizeOutOfRange=${client.pageSize(pages)} sizeNegative=${client.pageSize(-1)}" +
            " renderOutOfRange=${client.render(pages, 100, 100)}" +
            " renderTooWide=${client.render(0, PdfRenderService.MAX_SIDE + 1, 10)}" +
            " renderTooMany=${client.render(0, 4000, 4000)}" +
            " renderZero=${client.render(0, 0, 100)}"

    private fun bitmapOf(bytes: ByteArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pdfsIn(tree: Uri): List<Uri> = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        contentResolver.query(children, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    if (c.getString(2) == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) {
                        add(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
                    }
                }
            }
        }.orEmpty()
    }.getOrElse {
        report("TREE can't list: $it")
        emptyList()
    }

    private fun nameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: "?"

    private fun report(line: String) {
        Log.i(TAG, line)
        runOnUiThread { log.append(line + "\n") }
    }

    companion object {
        private const val TAG = "PdfSpike"
        private const val PICK_FILE = 1
        private const val PICK_TREE = 2
    }
}
