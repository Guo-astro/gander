package com.arjun.gander

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import androidx.recyclerview.widget.RecyclerView

/**
 * The rows of a file list: the home screen's recents and folders, and what is inside a .zip.
 *
 * One adapter for both, so a folder inside an archive looks and behaves exactly like a folder
 * on the phone, down to what TalkBack says about each row.
 */
internal sealed interface Row {
    data class Header(val title: String) : Row
    data class Hint(val text: String) : Row
    data class Item(
        val badge: String,
        val color: Int,
        val title: String,
        val subtitle: String?,
        val onClick: () -> Unit,
        /**
         * Asks whether to remove the row, and calls back if the answer is no, so a row swiped
         * away can come back. Null for a row that cannot be removed.
         */
        val onRemove: ((kept: () -> Unit) -> Unit)? = null,
        val thumbUri: Uri? = null,
        val thumbExt: String = ""
    ) : Row
}

internal class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val rows = mutableListOf<Row>()

    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    /**
     * Whether the row at [position] wants the whole width rather than one cell.
     *
     * Out-of-range answers full span on purpose: the layout manager can ask about a
     * position mid-update, and a header-shaped guess reflows harmlessly where a
     * cell-shaped one would throw.
     */
    fun isFullSpan(position: Int): Boolean =
        position !in rows.indices || rows[position] !is Row.Item

    /** Whether the row at [position] can be swiped away. See [SwipeToRemove]. */
    fun removable(position: Int): Boolean = (rows.getOrNull(position) as? Row.Item)?.onRemove != null

    /** The row at [position] was swiped away. It is asked about, and put back if it stays. */
    fun swiped(position: Int) {
        val putBack = { if (position in rows.indices) notifyItemChanged(position) }
        val remove = (rows.getOrNull(position) as? Row.Item)?.onRemove
        if (remove == null) putBack() else remove(putBack)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> 0
        is Row.Hint -> 1
        is Row.Item -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            0 -> R.layout.row_header
            1 -> R.layout.row_hint
            else -> R.layout.row_item
        }
        return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header ->
                holder.itemView.findViewById<TextView>(R.id.headerText).text = row.title
            is Row.Hint ->
                holder.itemView.findViewById<TextView>(R.id.hintText).text = row.text
            is Row.Item -> {
                val badge = holder.itemView.findViewById<TextView>(R.id.badge)
                val thumb = holder.itemView.findViewById<ImageView>(R.id.thumb)
                badge.text = row.badge
                badge.background.mutate().setTint(row.color)
                badge.visibility = View.VISIBLE
                thumb.visibility = View.GONE
                thumb.setImageDrawable(null)
                thumb.tag = null
                if (row.thumbUri != null) {
                    Thumbs.load(
                        holder.itemView.context, row.thumbUri, row.thumbExt, thumb, badge
                    )
                }
                holder.itemView.findViewById<TextView>(R.id.title).text = row.title
                val sub = holder.itemView.findViewById<TextView>(R.id.subtitle)
                sub.text = row.subtitle
                sub.visibility = if (row.subtitle == null) View.GONE else View.VISIBLE
                // The row children are not-important for accessibility, so this is
                // the whole announcement. Keeping the badge in it matters: the badge
                // is hidden once a thumbnail loads, and the file type would go with it
                holder.itemView.contentDescription =
                    listOfNotNull(row.title, row.badge, row.subtitle).joinToString(", ")
                holder.itemView.setOnClickListener { row.onClick() }
                // A swipe is how a row is removed, and nothing on screen says so, nor can a
                // screen reader make one. So TalkBack is offered Remove among the row's
                // actions, as dismiss, the action a view that can be swiped away has, and
                // rows that stay must not offer it.
                val remove = row.onRemove
                ViewCompat.replaceAccessibilityAction(
                    holder.itemView, AccessibilityActionCompat.ACTION_DISMISS,
                    remove?.let { holder.itemView.context.getString(R.string.remove) },
                    remove?.let { AccessibilityViewCommand { _, _ -> it {}; true } }
                )
            }
        }
    }
}
