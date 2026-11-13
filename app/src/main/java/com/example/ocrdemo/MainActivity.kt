package com.example.ocrdemo

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.net.Uri
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : AppCompatActivity() {

    // The OCR engine (Latin script covers English, Malay, etc.)
    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private lateinit var imageView: ImageView
    private lateinit var tvResult: TextView

    // Opens the gallery/file picker. No permission needed.
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                imageView.setImageURI(uri)
                runOcr(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        tvResult = findViewById(R.id.tvResult)
        val btnPick = findViewById<Button>(R.id.btnPick)

        btnPick.setOnClickListener {
            pickImage.launch("image/*")
        }
    }

    private fun runOcr(uri: Uri) {
        tvResult.text = "Reading..."
        try {
            val image = InputImage.fromFilePath(this, uri)

            recognizer.process(image)
                .addOnSuccessListener { result ->
                    tvResult.text =
                        if (result.text.isBlank()) "No text found" else result.text
                }
                .addOnFailureListener { e ->
                    tvResult.text = "Error: ${e.message}"
                }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
    }
}