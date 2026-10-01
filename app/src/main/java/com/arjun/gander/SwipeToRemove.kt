package com.arjun.gander

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.withClip
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A row on the home screen is removed by swiping it sideways, issue #39. Long-pressing a recent
 * file used to remove it on the spot, which was easy to do by accident.
 *
 * The row slides off over a bin on the error container colour, both coming in as it goes and
 * whole by halfway, where letting go counts. Short of that it springs back. Past it, the row asks
 * first, through [RowAdapter.swiped], and an Undo follows. Only rows that can be removed move.
 */
internal class SwipeToRemove(context: Context, private val adapter: RowAdapter) :
    ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START or ItemTouchHelper.END) {

    private val backing = Paint().apply {
        color = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorErrorContainer, "SwipeToRemove"
        )
    }
    private val bin: Drawable = AppCompatResources.getDrawable(context, R.drawable.ic_delete)!!.mutate()

    // In from the edge the row uncovers by the row's own padding, so the bin lines up with the badge
    private val inset = (BIN_INSET_DP * context.resources.displayMetrics.density).roundToInt()

    override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
        if (adapter.removable(viewHolder.bindingAdapterPosition)) {
            super.getSwipeDirs(recyclerView, viewHolder)
        } else {
            0
        }

    // Holding a row does nothing now, which is the point of #39
    override fun isLongPressDragEnabled(): Boolean = false

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        adapter.swiped(viewHolder.bindingAdapterPosition)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        val row = viewHolder.itemView
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f && row.width > 0) {
            // How near the row is to where letting go removes it, from 0 to 1
            val reach = (abs(dX) / (row.width * getSwipeThreshold(viewHolder))).coerceAtMost(1f)
            val alpha = (reach * 255).roundToInt()
            val left = if (dX > 0) row.left else row.right + dX.roundToInt()
            val right = if (dX > 0) row.left + dX.roundToInt() else row.right

            backing.alpha = alpha
            c.drawRect(left.toFloat(), row.top.toFloat(), right.toFloat(), row.bottom.toFloat(), backing)

            val size = bin.intrinsicWidth
            val top = row.top + (row.height - size) / 2
            val start = if (dX > 0) row.left + inset else row.right - inset - size
            bin.setBounds(start, top, start + size, top + size)
            bin.alpha = alpha
            // Only over what the row has uncovered, so the bin never shows through a gap it has
            // not reached
            c.withClip(left, row.top, right, row.bottom) { bin.draw(this) }
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    private companion object {
        /** row_item.xml's paddingStart and paddingEnd. */
        const val BIN_INSET_DP = 16
    }
}
