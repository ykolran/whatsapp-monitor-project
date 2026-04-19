package com.ykolran.wam.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ykolran.wam.R
import com.ykolran.wam.api.ApiClient
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Use the SAME prefs name as ApiClient.loadFromPrefs()
    private val PREFS_NAME = "wamirror"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val prefs  = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val etUrl   = findViewById<TextInputEditText>(R.id.etServerUrl)
        val etToken = findViewById<TextInputEditText>(R.id.etAuthToken)
        val btnTest = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTestConnection)

        // Load saved values
        etUrl.setText(prefs.getString("server_url", "http://192.168.1.100:3000"))
        etToken.setText(prefs.getString("auth_token", ""))

        // Auto-save on every change — no Save button needed
        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val url   = s.toString().trim()
                val token = etToken.text.toString().trim()
                prefs.edit().putString("server_url", url).apply()
                ApiClient.configure(url, token)
            }
        })

        etToken.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val token = s.toString().trim()
                val url   = etUrl.text.toString().trim()
                prefs.edit().putString("auth_token", token).apply()
                ApiClient.configure(url, token)
            }
        })

        btnTest.setOnClickListener {
            scope.launch {
                try {
                    val res = withContext(Dispatchers.IO) { ApiClient.api.healthCheck() }
                    if (res.isSuccessful)
                        Toast.makeText(this@SettingsActivity, getString(R.string.server_reachable), Toast.LENGTH_SHORT).show()
                    else
                        Toast.makeText(this@SettingsActivity, getString(R.string.server_error, res.code()), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.server_unreachable, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}