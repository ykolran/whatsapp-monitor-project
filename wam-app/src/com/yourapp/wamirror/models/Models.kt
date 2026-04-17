package com.ykolran.wam.models

import com.google.gson.annotations.SerializedName

data class SimpleResponse(
    val status: String? = null,
    val message: String? = null,
    val success: Boolean = true
)

data class Message(
    val id: String = "",
    val conversationId: String,
    val contactName: String,
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis() / 1000,
    val isGroup: Boolean = false
)

data class Conversation(
    val id: String,
    @SerializedName("contact_name") val contactName: String,
    @SerializedName("updated_at") val updatedAt: Long,
    @SerializedName("message_count") val messageCount: Int,
    val summary: String?,
    val intent: String?,
    val sentiment: String?
)

data class SummaryUpdate(
    val type: String,          // "summary_updated"
    val conversationId: String,
    val contactName: String,
    val summary: String,
    val intent: String,
    val sentiment: String,
    val timestamp: Long
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
    @SerializedName("matched_names") val matchedNames: String,
    @SerializedName("thumbnail_path") val thumbnailPath: String,
    @SerializedName("received_at") val receivedAt: Long,
    val imageUrl: String,
    val thumbnailUrl: String
)
