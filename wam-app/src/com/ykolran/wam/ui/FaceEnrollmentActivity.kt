package com.ykolran.wam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.ykolran.wam.R
import com.ykolran.wam.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class FaceEnrollmentActivity : AppCompatActivity() {

    private lateinit var etChildName:   TextInputEditText
    private lateinit var ivPreview:     ImageView
    private lateinit var llPlaceholder: LinearLayout
    private lateinit var btnCamera:     MaterialButton
    private lateinit var btnGallery:    MaterialButton
    private lateinit var btnEnroll:     MaterialButton
    private lateinit var llProgress:    LinearLayout
    private lateinit var rvChildren:    RecyclerView

    private var selectedImageUri: Uri? = null
    private var cameraImageUri:   Uri? = null

    // ── Photo pickers ────────────────────────────────────────────────────────

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraImageUri != null) {
            selectedImageUri = cameraImageUri
            showPreview(selectedImageUri!!)
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            showPreview(uri)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, getString(R.string.camera_permission_denied), Toast.LENGTH_SHORT).show()
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_enrollment)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        etChildName   = findViewById(R.id.etChildName)
        ivPreview     = findViewById(R.id.ivPhotoPreview)
        llPlaceholder = findViewById(R.id.llPhotoPlaceholder)
        btnCamera     = findViewById(R.id.btnCamera)
        btnGallery    = findViewById(R.id.btnGallery)
        btnEnroll     = findViewById(R.id.btnEnroll)
        llProgress    = findViewById(R.id.llProgress)
        rvChildren    = findViewById(R.id.rvEnrolledChildren)

        rvChildren.layoutManager = LinearLayoutManager(this)

        btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        btnGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        btnEnroll.setOnClickListener { enroll() }

        loadEnrolledChildren()
    }

    // ── Camera helpers ───────────────────────────────────────────────────────

    private fun launchCamera() {
        val photoFile = File(cacheDir, "enroll_${System.currentTimeMillis()}.jpg")
        cameraImageUri = FileProvider.getUriForFile(
            this, "$packageName.provider", photoFile
        )
        cameraLauncher.launch(cameraImageUri!!)
    }

    private fun showPreview(uri: Uri) {
        ivPreview.setImageURI(uri)
        llPlaceholder.visibility = View.GONE
        ivPreview.visibility     = View.VISIBLE
        updateEnrollButton()
    }

    private fun updateEnrollButton() {
        btnEnroll.isEnabled = selectedImageUri != null &&
                etChildName.text.toString().trim().isNotEmpty()
    }

    // ── Enroll ───────────────────────────────────────────────────────────────

    private fun enroll() {
        val childName = etChildName.text.toString().trim()
        val uri       = selectedImageUri ?: return
        if (childName.isEmpty()) {
            etChildName.error = getString(R.string.child_name_hint)
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { uriToFile(uri) }
                val photoPart = MultipartBody.Part.createFormData(
                    "photo", file.name,
                    file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                val namePart = childName.toRequestBody("text/plain".toMediaTypeOrNull())

                val response = withContext(Dispatchers.IO) {
                    ApiClient.api.enrollFace(photoPart, namePart)
                }

                if (response.isSuccessful) {
                    Toast.makeText(
                        this@FaceEnrollmentActivity,
                        getString(R.string.enrolled_success, childName),
                        Toast.LENGTH_LONG
                    ).show()
                    // Reset UI for next photo
                    selectedImageUri = null
                    ivPreview.setImageURI(null)
                    ivPreview.visibility     = View.GONE
                    llPlaceholder.visibility = View.VISIBLE
                    updateEnrollButton()
                    loadEnrolledChildren()
                } else {
                    val errBody = response.errorBody()?.string() ?: "Unknown error"
                    Toast.makeText(
                        this@FaceEnrollmentActivity,
                        getString(R.string.enroll_error, errBody),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@FaceEnrollmentActivity,
                    getString(R.string.enroll_error, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    // ── Load enrolled children ───────────────────────────────────────────────

    private fun loadEnrolledChildren() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { ApiClient.api.getEnrolledFaces() }
                if (response.isSuccessful) {
                    val children = response.body() ?: emptyList()
                    rvChildren.adapter = EnrolledChildAdapter(children) { childName ->
                        confirmDelete(childName)
                    }
                }
            } catch (e: Exception) {
                // Silently ignore — list just stays empty
            }
        }
    }

    private fun confirmDelete(childName: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_child_title, childName))
            .setMessage(R.string.delete_child_message)
            .setPositiveButton(R.string.btn_delete) { _, _ -> deleteChild(childName) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteChild(childName: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ApiClient.api.deleteEnrolledFace(childName) }
                loadEnrolledChildren()
                Toast.makeText(this@FaceEnrollmentActivity,
                    getString(R.string.child_deleted, childName), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@FaceEnrollmentActivity,
                    e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun setLoading(loading: Boolean) {
        llProgress.visibility = if (loading) View.VISIBLE else View.GONE
        btnEnroll.isEnabled   = !loading && selectedImageUri != null &&
                etChildName.text.toString().trim().isNotEmpty()
        btnCamera.isEnabled   = !loading
        btnGallery.isEnabled  = !loading
    }

    private fun uriToFile(uri: Uri): File {
        val input  = contentResolver.openInputStream(uri)!!
        val file   = File(cacheDir, "enroll_upload_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { input.copyTo(it) }
        return file
    }

    // ── Enrolled children adapter ────────────────────────────────────────────

    inner class EnrolledChildAdapter(
        private val items: List<Map<String, Any>>,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<EnrolledChildAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvAvatar:  TextView      = view.findViewById(R.id.tvAvatar)
            val tvName:    TextView      = view.findViewById(R.id.tvChildName)
            val tvSamples: TextView      = view.findViewById(R.id.tvSampleCount)
            val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = layoutInflater.inflate(R.layout.item_enrolled_child, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item    = items[position]
            val name    = item["child_name"] as? String ?: "?"
            val samples = (item["samples"] as? Double)?.toInt() ?: 0

            holder.tvAvatar.text  = name.first().uppercaseChar().toString()
            holder.tvName.text    = name
            holder.tvSamples.text = resources.getQuantityString(
                R.plurals.sample_count, samples, samples
            )
            holder.btnDelete.setOnClickListener { onDelete(name) }
        }
    }
}
