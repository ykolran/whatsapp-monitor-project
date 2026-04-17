package com.ykolran.wam.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ykolran.wam.R
import com.ykolran.wam.models.Conversation

class ConversationAdapter(private val conversations: List<Conversation>) :
    RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvContactName: TextView = view.findViewById(R.id.tvContactName)
        val tvSummary: TextView = view.findViewById(R.id.tvSummary)
        val tvSentiment: TextView = view.findViewById(R.id.tvSentiment)
        val tvIntent: TextView = view.findViewById(R.id.tvIntent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conv = conversations[position]
        holder.tvContactName.text = conv.contactName
        holder.tvSummary.text = conv.summary ?: "No summary available"
        holder.tvSentiment.text = conv.sentiment ?: ""
        holder.tvIntent.text = conv.intent ?: ""
    }

    override fun getItemCount() = conversations.size
}
