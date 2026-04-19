package com.ykolran.wam.adapters

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ykolran.wam.R
import com.ykolran.wam.models.Conversation

class ConversationAdapter(
    private val items: MutableList<Conversation>,
    private val onSwipe: (Conversation, Int) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:      TextView = view.findViewById(R.id.tvContactName)
        val tvSummary:   TextView = view.findViewById(R.id.tvSummary)
        val tvSentiment: TextView = view.findViewById(R.id.tvSentiment)
        val tvBadge:     TextView = view.findViewById(R.id.tvNewBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false))

    override fun getItemCount() = items.size

    fun getItem(position: Int): Conversation = items[position]

    fun updateItem(conversationId: String, summary: String, sentiment: String) {
        val idx = items.indexOfFirst { it.id == conversationId }
        if (idx >= 0) {
            items[idx] = items[idx].copy(summary = summary, sentiment = sentiment)
            notifyItemChanged(idx)
        }
    }

    fun addOrUpdateFromRemote(remoteId: String, summary: String, sentiment: String) {
        val idx = items.indexOfFirst { it.remoteId == remoteId }
        if (idx >= 0) {
            items[idx] = items[idx].copy(summary = summary, sentiment = sentiment)
            notifyItemChanged(idx)
        }
    }

    /**
     * Called by ConversationsActivity when the server call fails,
     * to restore the item that was optimistically removed.
     */
    fun restoreItem(conversation: Conversation, position: Int) {
        val insertAt = position.coerceIn(0, items.size)
        items.add(insertAt, conversation)
        notifyItemInserted(insertAt)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conv = items[position]
        holder.tvName.text = conv.contactName
        holder.tvSummary.text = conv.summary?.takeIf { it.isNotBlank() }
            ?: holder.itemView.context.getString(R.string.no_summary_yet)
        holder.tvSentiment.text = sentimentEmoji(conv.sentiment)
        holder.tvBadge.visibility = if (conv.newMessageCount > 0) View.VISIBLE else View.GONE
        holder.tvBadge.text = conv.newMessageCount.toString()
    }

    private fun sentimentEmoji(sentiment: String?) = when (sentiment?.lowercase()) {
        "positive"  -> "😊"
        "negative"  -> "😟"
        "urgent"    -> "🚨"
        "concerned" -> "😕"
        "friendly"  -> "🤝"
        else        -> "💬"
    }

    // ── Swipe helper ──────────────────────────────────────────────────
    fun attachSwipeHelper(recyclerView: RecyclerView) {
        ItemTouchHelper(SwipeCallback(recyclerView.context))
            .attachToRecyclerView(recyclerView)
    }

    private inner class SwipeCallback(private val context: Context)
        : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT  // both directions
        ) {

        private val bgColor  = ColorDrawable(ContextCompat.getColor(context, R.color.swipe_archive))
        private val icon     = ContextCompat.getDrawable(context, R.drawable.ic_archive)!!
        private val iconMargin = context.resources.getDimensionPixelSize(R.dimen.swipe_icon_margin)
        private val textPaint = Paint().apply {
            color     = ContextCompat.getColor(context, android.R.color.white)
            textSize  = context.resources.getDimensionPixelSize(R.dimen.swipe_label_size).toFloat()
            isAntiAlias = true
        }

        override fun onMove(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
            t: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return

            // FIX: remove immediately so the green box disappears right away.
            // The Activity will restore it if the server call fails.
            val conversation = items[position]
            items.removeAt(position)
            notifyItemRemoved(position)

            onSwipe(conversation, position)
        }

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isActive: Boolean
        ) {
            val itemView = vh.itemView
            val label = context.getString(R.string.swipe_archive_label)

            when {
                dX < 0 -> { // swiping LEFT
                    bgColor.setBounds(
                        (itemView.right + dX).toInt(), itemView.top,
                        itemView.right, itemView.bottom
                    )
                    bgColor.draw(c)

                    val iconTop   = itemView.top + (itemView.height - icon.intrinsicHeight) / 2
                    val iconRight = itemView.right - iconMargin
                    val iconLeft  = iconRight - icon.intrinsicWidth
                    icon.setBounds(iconLeft, iconTop, iconRight, iconTop + icon.intrinsicHeight)
                    icon.draw(c)

                    val textX = iconLeft.toFloat() - textPaint.measureText(label) - 8f
                    val textY = itemView.top + itemView.height / 2f + textPaint.textSize / 3f
                    if (textX > itemView.right + dX) c.drawText(label, textX, textY, textPaint)
                }
                dX > 0 -> { // swiping RIGHT
                    bgColor.setBounds(
                        itemView.left, itemView.top,
                        (itemView.left + dX).toInt(), itemView.bottom
                    )
                    bgColor.draw(c)

                    val iconTop  = itemView.top + (itemView.height - icon.intrinsicHeight) / 2
                    val iconLeft = itemView.left + iconMargin
                    val iconRight = iconLeft + icon.intrinsicWidth
                    icon.setBounds(iconLeft, iconTop, iconRight, iconTop + icon.intrinsicHeight)
                    icon.draw(c)

                    val textX = iconRight.toFloat() + 8f
                    val textY = itemView.top + itemView.height / 2f + textPaint.textSize / 3f
                    if (textX + textPaint.measureText(label) < itemView.left + dX)
                        c.drawText(label, textX, textY, textPaint)
                }
            }

            super.onChildDraw(c, rv, vh, dX, dY, actionState, isActive)
        }
    }
}