package com.ykolran.wam.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import com.ykolran.wam.R
import com.ykolran.wam.adapters.ConversationAdapter
import com.ykolran.wam.api.ApiClient
import com.ykolran.wam.models.Conversation
import com.ykolran.wam.models.SummaryUpdate
import com.ykolran.wam.services.MediaWatcherService
import com.ykolran.wam.services.NotificationHelper
import kotlinx.coroutines.*

class ConversationsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout   // ADD THIS
    private lateinit var adapter: ConversationAdapter
    private val conversations = mutableListOf<Conversation>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversations)
        setSupportActionBar(findViewById(R.id.toolbar))

        NotificationHelper.createChannel(this)
        ApiClient.loadFromPrefs(this)
 
        swipeRefresh = findViewById(R.id.swipeRefresh)      
        swipeRefresh.setColorSchemeColors(                   
            getColor(R.color.colorPrimary)
        )
        swipeRefresh.setOnRefreshListener { loadConversations() }  


        recyclerView = findViewById(R.id.recyclerConversations)
        adapter = ConversationAdapter(conversations, ::onConversationSwiped)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        adapter.attachSwipeHelper(recyclerView)

        if (!isNotificationServiceEnabled()) {
            Snackbar.make(recyclerView, R.string.notification_access_required, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_enable) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }.show()
        }

        // Start media watcher
        startService(Intent(this, MediaWatcherService::class.java))

        // Connect WebSocket for real-time summary updates
        ApiClient.connectWebSocket()

        loadConversations()
        observeWebSocketUpdates()

        // Highlight a specific conversation if launched from a notification
        intent.getStringExtra("highlight_conversation_id")?.let { highlightConversation(it) }
    }

    private fun loadConversations() {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.api.getConversations() }
                if (response.isSuccessful) {
                    conversations.clear()
                    conversations.addAll(response.body() ?: emptyList())
                    adapter.notifyDataSetChanged()
                } else {
                    Toast.makeText(this@ConversationsActivity,
                        getString(R.string.server_error, response.code()), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ConversationsActivity,
                    getString(R.string.cannot_connect_server), Toast.LENGTH_LONG).show()
            } finally {
                swipeRefresh.isRefreshing = false   // ADD THIS — always stop the spinner
            }
        }
    }

    private fun observeWebSocketUpdates() {
        scope.launch {
            ApiClient.summaryUpdates.collect { update ->
                // Update list item in-place
                val idx = conversations.indexOfFirst { it.id == update.conversationId }
                if (idx >= 0) {
                    conversations[idx] = conversations[idx].copy(
                        summary = update.summary,
                        sentiment = update.sentiment
                    )
                    adapter.notifyItemChanged(idx)
                } else {
                    loadConversations() // new conversation arrived
                }

                // Post a local push notification
                NotificationHelper.postSummaryNotification(
                    context       = this@ConversationsActivity,
                    contactName   = update.contactName,
                    summary       = update.summary,
                    sentiment     = update.sentiment,
                    conversationId = update.conversationId
                )
            }
        }
    }

    private fun onConversationSwiped(conversation: Conversation, position: Int) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.api.swipeConversation(conversation.id)
                }
                if (response.isSuccessful) {
                    val result = response.body()
                    Snackbar.make(recyclerView,
                        getString(R.string.conversation_archived), Snackbar.LENGTH_SHORT).show()
                    // Item is already removed — nothing more to do visually.
                    // Optionally re-insert at top with updated summary if you want it to stay visible:
                    // result?.summary?.let { ... }
                } else {
                    // Restore the item since the server rejected the swipe
                    adapter.restoreItem(conversation, position)
                    Toast.makeText(this@ConversationsActivity,
                        getString(R.string.swipe_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                adapter.restoreItem(conversation, position)
                Toast.makeText(this@ConversationsActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun highlightConversation(conversationId: String) {
        val idx = conversations.indexOfFirst { it.id == conversationId }
        if (idx >= 0) recyclerView.scrollToPosition(idx)
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    override fun onResume() {
        super.onResume()
        loadConversations()
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

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings       -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_enroll_face    -> { startActivity(Intent(this, FaceEnrollmentActivity::class.java)); true }
        R.id.action_children_photos -> { startActivity(Intent(this, ChildrenPhotosActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }
}
