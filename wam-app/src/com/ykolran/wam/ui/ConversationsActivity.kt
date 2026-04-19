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
import com.ykolran.wam.services.MediaWatcherService
import com.ykolran.wam.services.NotificationHelper
import kotlinx.coroutines.*

class ConversationsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
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
        swipeRefresh.setColorSchemeColors(getColor(R.color.colorPrimary))
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

        startService(Intent(this, MediaWatcherService::class.java))
        ApiClient.connectWebSocket()
        loadConversations()
        observeWebSocketUpdates()

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
                    Toast.makeText(
                        this@ConversationsActivity,
                        getString(R.string.server_error, response.code()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ConversationsActivity,
                    getString(R.string.cannot_connect_server),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun observeWebSocketUpdates() {
        scope.launch {
            ApiClient.summaryUpdates.collect { update ->
                when (update.type) {

                    "summary_updated" -> {
                        val idx = conversations.indexOfFirst { it.id == update.conversationId }
                        if (idx >= 0) {
                            // Update existing conversation's summary in-place
                            conversations[idx] = conversations[idx].copy(
                                summary   = update.summary,
                                sentiment = update.sentiment
                            )
                            adapter.notifyItemChanged(idx)
                        } else {
                            // New conversation — reload the full list
                            loadConversations()
                        }

                        NotificationHelper.postSummaryNotification(
                            context        = this@ConversationsActivity,
                            contactName    = update.contactName,
                            summary        = update.summary,
                            sentiment      = update.sentiment,
                            conversationId = update.conversationId
                        )
                    }

                    "conversation_archived" -> {
                        // Server confirmed archive — remove from list if still present.
                        // (Optimistic removal in onConversationSwiped already handled the UI;
                        //  this covers the case where a WS push arrives from another device.)
                        val idx = conversations.indexOfFirst { it.id == update.conversationId }
                        if (idx >= 0) {
                            conversations.removeAt(idx)
                            adapter.notifyItemRemoved(idx)
                        }
                    }
                }
            }
        }
    }

    private fun onConversationSwiped(conversation: Conversation, position: Int) {
        // Optimistic removal already done in ConversationAdapter.onSwiped()
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.api.swipeConversation(conversation.id)
                }
                if (!response.isSuccessful) {
                    // Server rejected — restore the item
                    adapter.restoreItem(conversation, position)
                    Toast.makeText(
                        this@ConversationsActivity,
                        getString(R.string.swipe_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                // On success: item is already gone, WS broadcast is ignored (idx == -1)
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
        R.id.action_settings        -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_enroll_face     -> { startActivity(Intent(this, FaceEnrollmentActivity::class.java)); true }
        R.id.action_children_photos -> { startActivity(Intent(this, ChildrenPhotosActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }
}
