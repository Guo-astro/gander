package com.arjun.gander

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

/**
 * Save a copy: the open file written wherever the reader pointed the picker, with the bar under
 * the toolbar saying how far along it is.
 *
 * No permission is involved. The picker returns a write grant for the single
 * document it just created and for nothing around it, which is the same shape
 * of grant the home screen already takes for folders, and it is why this can
 * exist in an app whose permission list has to stay empty.
 */
internal class SaveCopy(private val activity: Activity) {

    /**
     * Whether a copy is reporting on the bar under the toolbar. A zip's list waits on the
     * same bar and leaves it alone meanwhile.
     */
    var saving = false
        @androidx.annotation.VisibleForTesting internal set

    /** Copies [src] to [dest], the document the picker made. */
    fun copy(src: Uri, dest: Uri) {
        // Off the main thread. A document is small enough that it would not matter,
        // but Gander opens video too, and copying a few hundred megabytes inline is
        // an ANR rather than a slow save.
        val app = activity.applicationContext
        val main = Handler(Looper.getMainLooper())
        val bar = activity.findViewById<LinearProgressIndicator>(R.id.saveProgress)

        // Asked once, up front: a provider that cannot say how long the file is
        // gets a spinner rather than a bar that would have to invent a position.
        val total = documentLength(activity, src)
        bar.isIndeterminate = total <= 0L
        if (total > 0L) {
            bar.max = 100
            bar.progress = 0
        }
        bar.visibility = View.VISIBLE
        saving = true

        // Shut down immediately after submitting: the already-queued copy still
        // runs to completion, and the worker thread ends with it instead of idling
        // for the life of the process once per save
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val saved = runCatching {
                activity.contentResolver.openInputStream(src).use { input ->
                    activity.contentResolver.openOutputStream(dest).use { output ->
                        val from = checkNotNull(input)
                        val to = checkNotNull(output)
                        // copyTo would be one line, but it reports nothing on the way
                        // through, and the whole point here is to be able to say how
                        // far along a large file is
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var shown = -1
                        while (true) {
                            val read = from.read(buffer)
                            if (read < 0) break
                            to.write(buffer, 0, read)
                            copied += read
                            if (total > 0L) {
                                // Whole percent only: a 500 MB file would otherwise
                                // post thousands of updates nobody can see
                                val percent = ((copied * 100) / total).toInt()
                                if (percent != shown) {
                                    shown = percent
                                    main.post {
                                        if (!activity.isDestroyed) bar.setProgressCompat(percent, true)
                                    }
                                }
                            }
                        }
                    }
                }
            }.isSuccess
            // A failed copy has already created the document and may have written
            // part of it, and half a file under the right name is worse than none:
            // it opens, it looks complete, and it is not.
            if (!saved) runCatching { DocumentsContract.deleteDocument(activity.contentResolver, dest) }
            main.post {
                saving = false
                if (!activity.isDestroyed) bar.visibility = View.GONE
                Toast.makeText(
                    app,
                    if (saved) R.string.save_copy_done else R.string.save_copy_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        worker.shutdown()
    }
}

/**
 * ACTION_CREATE_DOCUMENT with the type set per file. The stock contract fixes
 * it at construction, and the viewer does not know what it is showing until an
 * intent arrives, so the picker would otherwise have to be told that every file
 * is of unknown type and would suggest names without extensions.
 */
internal class CreateTypedDocument : ActivityResultContracts.CreateDocument("*/*") {
    var type: String = "*/*"
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).setType(type)
}
