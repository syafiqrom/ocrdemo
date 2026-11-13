package com.example.ocrdemo

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class MainActivity : AppCompatActivity() {

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private lateinit var imageView: ImageView
    private lateinit var tvResult: TextView

    // Remembers where the camera should save the photo
    private var cameraUri: Uri? = null

    // Gallery picker
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                imageView.setImageURI(uri)
                runOcr(uri)
            }
        }

    // Camera: returns true if the photo was taken successfully
    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
            val uri = cameraUri
            if (success && uri != null) {
                imageView.setImageURI(uri)
                runOcr(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        tvResult = findViewById(R.id.tvResult)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImage.launch("image/*")
        }

        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            launchCamera()
        }
    }

    private fun launchCamera() {
        try {
            val imagesDir = File(cacheDir, "images").apply { mkdirs() }
            val photoFile = File(imagesDir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
            cameraUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun runOcr(uri: Uri) {
        tvResult.text = "Reading..."
        try {
            val image = InputImage.fromFilePath(this, uri)

            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val dWords = mutableListOf<String>()

                    // text -> blocks -> lines -> elements (words)
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            for (element in line.elements) {
                                if (element.text.startsWith("D", ignoreCase = true)) {
                                    dWords.add(element.text)
                                }
                            }
                        }
                    }

                    tvResult.text =
                        if (dWords.isEmpty()) "No words starting with D found"
                        else dWords.joinToString("\n")
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