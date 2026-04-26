package com.ykolran.wam.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ykolran.wam.R
import com.ykolran.wam.models.ChildImage

class ChildPhotoAdapter(
    private val items: List<ChildImage>,
    private val onTap: (ChildImage) -> Unit
) : RecyclerView.Adapter<ChildPhotoAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val tvNames: TextView = view.findViewById(R.id.tvMatchedNames)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_child_photo, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        Glide.with(holder.thumbnail)
            .load(item.thumbnailUrl)
            .centerCrop()
            .placeholder(R.drawable.ic_photo_placeholder)
            .into(holder.thumbnail)

        holder.tvNames.text = item.matchedNames.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")

        holder.tvDate.text = android.text.format.DateFormat
            .getDateFormat(holder.itemView.context)
            .format(java.util.Date(item.receivedAt * 1000))

        holder.itemView.setOnClickListener { onTap(item) }
    }
}
