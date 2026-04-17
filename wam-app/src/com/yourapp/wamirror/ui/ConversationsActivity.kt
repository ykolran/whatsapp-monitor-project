package com.ykolran.wam.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ykolran.wam.R
import com.ykolran.wam.api.ApiClient
import com.ykolran.wam.models.Conversation
import com.ykolran.wam.models.SummaryUpdate
import com.ykolran.wam.services.MediaWatcherService
import kotlinx.coroutines.*
import retrofit2.Response

/**
 * Main screen — shows all conversations with LLM-generated summaries.
 * Two tabs: Conversations | Children's Photos
 */
class ConversationsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ConversationAdapter
    private val conversations = mutableListOf<Conversation>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversations)

        // Initialize ApiClient with saved settings
        val prefs = getSharedPreferences("wam", MODE_PRIVATE)
        val url = prefs.getString("server_url", "http://192.168.1.100:3000") ?: "http://192.168.1.100:3000"
        val token = prefs.getString("auth_token", "change-me-to-a-random-secret") ?: "change-me-to-a-random-secret"
        ApiClient.configure(url, token)

        recyclerView = findViewById(R.id.recyclerConversations)
        adapter = ConversationAdapter(conversations)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Check notification access
        if (!isNotificationServiceEnabled()) {
            Toast.makeText(this, "Please enable Notification Access for this app", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Start media watcher
        startService(Intent(this, MediaWatcherService::class.java))

        // Connect WebSocket for real-time summary updates
        ApiClient.connectWebSocket()

        loadConversations()
        observeWebSocketUpdates()
    }

    private fun loadConversations() {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.api.getConversations() }
                if (response.isSuccessful) {
                    conversations.clear()
                    conversations.addAll(response.body() ?: emptyList())
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ConversationsActivity,
                    "Cannot connect to server. Is it running?", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun observeWebSocketUpdates() {
        scope.launch {
            ApiClient.summaryUpdates.collect { update ->
                val idx = conversations.indexOfFirst { it.id == update.conversationId }
                if (idx >= 0) {
                    conversations[idx] = conversations[idx].copy(
                        summary = update.summary,
                        intent = update.intent,
                        sentiment = update.sentiment
                    )
                    adapter.notifyItemChanged(idx)
                } else {
                    // New conversation — reload full list
                    loadConversations()
                }
            }
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    override fun onDestroy() {
        scope.cancel()
        ApiClient.disconnectWebSocket()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_enroll_face -> {
                startActivity(Intent(this, FaceEnrollmentActivity::class.java))
                true
            }
            R.id.action_children_photos -> {
                startActivity(Intent(this, ChildrenPhotosActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
