package com.ykolran.wam.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ykolran.wam.R
import com.ykolran.wam.api.ApiClient
import com.ykolran.wam.models.ChildImage
import kotlinx.coroutines.*

class ChildrenPhotosActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_children_photos)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        tvEmpty = findViewById(R.id.tvEmpty)
        recyclerView = findViewById(R.id.recyclerPhotos)
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        loadPhotos()
    }

    private fun loadPhotos() {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.api.getChildImages() }
                if (response.isSuccessful) {
                    val images = response.body() ?: emptyList()
                    if (images.isEmpty()) {
                        tvEmpty.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        tvEmpty.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        recyclerView.adapter = ChildPhotoAdapter(images) { image ->
                            // Open full image in browser/gallery
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(image.imageUrl)))
                        }
                    }
                }
            } catch (e: Exception) {
                tvEmpty.text = getString(R.string.cannot_connect_server)
                tvEmpty.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
