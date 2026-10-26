package com.arjun.gander

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import org.robolectric.Robolectric

/**
 * A stand-in for a granted folder.
 *
 * The home screen reads folders through DocumentsContract, which means a
 * provider on the other side. This is the smallest one that answers the two
 * queries MainActivity makes: the children of a folder, and the display name
 * of a tree.
 */
internal class FakeDocumentsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "test.documents"

        fun install(): FakeDocumentsProvider =
            Robolectric.buildContentProvider(FakeDocumentsProvider::class.java)
                .create(AUTHORITY)
                .get()

        fun treeUri(docId: String = "root"): Uri =
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, docId)
    }

    /** Folder document id to the entries inside it. */
    private val folders = mutableMapOf<String, MutableList<ChildDoc>>()
    private val labels = mutableMapOf<String, String>()

    fun folder(docId: String, label: String, vararg children: ChildDoc) = apply {
        folders[docId] = children.toMutableList()
        labels[docId] = label
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

        // A children query names the parent in the last path segment; a single
        // document query names the document itself.
        val isChildren = uri.pathSegments.contains("children")
        val docId = DocumentsContract.getDocumentId(uri)

        val rows = if (isChildren) {
            folders[docId].orEmpty()
        } else {
            listOf(ChildDoc(docId, labels[docId] ?: docId, MIME_DIR, 0, 0))
        }

        return MatrixCursor(columns).apply {
            rows.forEach { child ->
                addRow(
                    columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> child.docId
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> child.name
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> child.mime
                            DocumentsContract.Document.COLUMN_SIZE -> child.size
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> child.modified
                            else -> null
                        }
                    }.toTypedArray<Any?>()
                )
            }
        }
    }

    override fun getType(uri: Uri): String = MIME_DIR
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        args: Array<out String>?
    ) = 0
}
