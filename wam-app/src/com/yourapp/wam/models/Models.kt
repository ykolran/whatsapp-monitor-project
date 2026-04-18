package com.ykolran.wam.models

import com.google.gson.annotations.SerializedName

data class Message(
    val id: String = "",
    @SerializedName("conversation_id") val conversationId: String = "",
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis() / 1000,
    @SerializedName("is_history") val isHistory: Int = 0,
    @SerializedName("history_summary_id") val historySummaryId: String? = null
)

data class OutboundMessage(
    val conversationId: String,
    val contactName: String,
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis() / 1000,
    val isGroup: Boolean = false
)

data class Conversation(
    val id: String,
    @SerializedName("remote_id")        val remoteId: String,
    @SerializedName("contact_name")     val contactName: String,
    @SerializedName("updated_at")       val updatedAt: Long,
    @SerializedName("message_count")    val messageCount: Int,
    @SerializedName("new_message_count") val newMessageCount: Int = 0,
    val summary: String?,
    val sentiment: String?
)

data class SummaryUpdate(
    val type: String,
    val conversationId: String,
    val remoteId: String,
    val contactName: String,
    val summary: String,
    val sentiment: String,
    val timestamp: Long
)

data class SwipeResult(
    val success: Boolean,
    val summary: String?,
    val sentiment: String?
)

data class ImageUploadResponse(
    val imageId: String,
    val hasChildren: Boolean,
    val matchedNames: List<String>,
    val imageUrl: String,
    val thumbnailUrl: String
)

data class ChildImage(
    val id: String,
    @SerializedName("conversation_id") val conversationId: String?,
    val filename: String,
    @SerializedName("matched_names")   val matchedNames: String,
    @SerializedName("thumbnail_path")  val thumbnailPath: String,
    @SerializedName("received_at")     val receivedAt: Long,
    val imageUrl: String,
    val thumbnailUrl: String
)
