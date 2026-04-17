package com.ykolran.wam.services

import android.app.Service
import android.content.Intent
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import com.ykolran.wam.api.ApiClient
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Background service that watches the WhatsApp Images folder for new received images.
 * When a new image appears, it uploads it to the server for face recognition.
 *
 * SETUP: Requires READ_EXTERNAL_STORAGE (or MANAGE_EXTERNAL_STORAGE on Android 11+)
 * On Android 10+, the WhatsApp media path changed to use scoped storage paths.
 */
class MediaWatcherService : Service() {

    private val TAG = "MediaWatcher"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fileObserver: FileObserver? = null

    // WhatsApp saves received images here on modern Android
    private val WHATSAPP_IMAGES_PATH =
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent"
    // Received images (not in Sent subfolder)
    private val WHATSAPP_RECEIVED_PATH =
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWatching()
        return START_STICKY
    }

    private fun startWatching() {
        val watchDir = File(WHATSAPP_RECEIVED_PATH)
        if (!watchDir.exists()) {
            Log.w(TAG, "WhatsApp Images directory not found: $WHATSAPP_RECEIVED_PATH")
            return
        }

        // FileObserver.CLOSE_WRITE fires when the file is fully written
        fileObserver = object : FileObserver(watchDir, CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                if (event == CLOSE_WRITE && isImageFile(path)) {
                    val fullPath = "$WHATSAPP_RECEIVED_PATH/$path"
                    Log.d(TAG, "New WhatsApp image: $fullPath")
                    // Small delay to ensure file is fully flushed
                    scope.launch {
                        delay(500)
                        uploadImage(File(fullPath))
                    }
                }
            }
        }
        fileObserver?.startWatching()
        Log.i(TAG, "Watching: $WHATSAPP_RECEIVED_PATH")
    }

    private fun isImageFile(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private suspend fun uploadImage(file: File) {
        if (!file.exists() || file.length() == 0L) return
        try {
            val mediaType = "image/jpeg".toMediaType()
            val requestBody = file.asRequestBody(mediaType)
            val part = MultipartBody.Part.createFormData("image", file.name, requestBody)
            val response = ApiClient.api.uploadImage(part, null)
            if (response.isSuccessful) {
                val result = response.body()
                if (result?.hasChildren == true) {
                    Log.i(TAG, "Image contains children: ${result.matchedNames}")
                    // Optionally notify the app
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload image: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        fileObserver?.stopWatching()
        scope.cancel()
        super.onDestroy()
    }
}
