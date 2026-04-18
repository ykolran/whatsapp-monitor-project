package com.ykolran.wam.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.ykolran.wam.R
import com.ykolran.wam.api.ApiClient
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        val prefs = getSharedPreferences("wam", MODE_PRIVATE)
        val etUrl = findViewById<EditText>(R.id.etServerUrl)
        val etToken = findViewById<EditText>(R.id.etAuthToken)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        val btnTest = findViewById<Button>(R.id.btnTestConnection)

        etUrl.setText(prefs.getString("server_url", "http://192.168.1.100:3000"))
        etToken.setText(prefs.getString("auth_token", "change-me-to-a-random-secret"))

        btnSave.setOnClickListener {
            val url = etUrl.text.toString().trim()
            val token = etToken.text.toString().trim()
            prefs.edit().putString("server_url", url).putString("auth_token", token).apply()
            ApiClient.configure(url, token)
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        btnTest.setOnClickListener {
            scope.launch {
                try {
                    val res = withContext(Dispatchers.IO) { ApiClient.api.healthCheck() }
                    if (res.isSuccessful) Toast.makeText(this@SettingsActivity, "✓ Server reachable!", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this@SettingsActivity, "Server error: ${res.code()}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "Cannot reach server: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
