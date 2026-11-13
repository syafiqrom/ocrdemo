package com.example.ocrdemo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class MainActivity : AppCompatActivity() {

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private lateinit var imageView: ImageView
    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: Button
    private lateinit var tvResult: TextView

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Words collected from camera captures (keeps growing)
    private val capturedWords = mutableListOf<String>()

    // Gallery picker
    // Gallery picker (multiple images)
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                stopCamera()
                imageView.setImageURI(uris[0])
                addImages(uris)
            }
        }

    // Asks for camera permission
    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is needed", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        previewView = findViewById(R.id.previewView)
        btnCapture = findViewById(R.id.btnCapture)
        tvResult = findViewById(R.id.tvResult)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImages.launch("image/*")
        }

        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            openCamera()
        }

        btnCapture.setOnClickListener {
            capturePhoto()
        }
    }

    private fun openCamera() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) startCamera()
        else requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        previewView.visibility = View.VISIBLE
        btnCapture.visibility = View.VISIBLE
        imageView.visibility = View.GONE

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        previewView.visibility = View.GONE
        btnCapture.visibility = View.GONE
        imageView.visibility = View.VISIBLE
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    addImages(listOf(Uri.fromFile(file)))
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity,
                        "Capture failed: ${exc.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    // Reads one or more images and adds their D words to the shared list
    private fun addImages(uris: List<Uri>) {
        val results = arrayOfNulls<List<String>>(uris.size)
        var finished = 0

        fun onOneDone() {
            finished++
            if (finished == uris.size) {
                // keep the order the images were picked in
                results.forEach { list -> if (list != null) capturedWords.addAll(list) }
                showWords()
            }
        }

        uris.forEachIndexed { index, uri ->
            try {
                val image = InputImage.fromFilePath(this, uri)

                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        results[index] = findDWordList(result)
                        onOneDone()
                    }
                    .addOnFailureListener {
                        results[index] = emptyList()
                        onOneDone()
                    }
            } catch (e: Exception) {
                results[index] = emptyList()
                onOneDone()
            }
        }
    }

    private fun showWords() {
        tvResult.text =
            if (capturedWords.isEmpty()) "No words starting with D found"
            else capturedWords.joinToString("\n")
    }

    // Returns the words starting with D
    private fun findDWordList(result: com.google.mlkit.vision.text.Text): List<String> {
        val words = mutableListOf<String>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    if (element.text.startsWith("D", ignoreCase = true)) {
                        words.add(element.text)
                    }
                }
            }
        }
        return words
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        recognizer.close()
    }
}