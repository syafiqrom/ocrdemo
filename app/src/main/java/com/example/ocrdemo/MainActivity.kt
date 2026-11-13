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

    // Gallery picker
    // Gallery picker (multiple images)
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                stopCamera()
                imageView.setImageURI(uris[0])   // preview the first image
                runOcrMultiple(uris)
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
                    val uri = Uri.fromFile(file)
                    stopCamera()
                    imageView.setImageURI(uri)
                    runOcr(uri)   // goes straight to OCR, no confirm screen
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

    // Used by the camera (one image)
    private fun runOcr(uri: Uri) {
        runOcrMultiple(listOf(uri))
    }

    // Used by the gallery (one or many images)
    private fun runOcrMultiple(uris: List<Uri>) {
        tvResult.text = "Reading ${uris.size} image(s)..."

        val results = arrayOfNulls<String>(uris.size)
        var finished = 0

        fun onOneDone() {
            finished++
            tvResult.text = "Reading... $finished / ${uris.size}"

            if (finished == uris.size) {
                tvResult.text = uris.indices.joinToString("\n\n") { i ->
                    val body = results[i] ?: "Error"
                    if (uris.size > 1) "Image ${i + 1}:\n$body" else body
                }
            }
        }

        uris.forEachIndexed { index, uri ->
            try {
                val image = InputImage.fromFilePath(this, uri)

                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        results[index] = findDWords(result)
                        onOneDone()
                    }
                    .addOnFailureListener { e ->
                        results[index] = "Error: ${e.message}"
                        onOneDone()
                    }
            } catch (e: Exception) {
                results[index] = "Could not load image"
                onOneDone()
            }
        }
    }

    // Keeps only words starting with D
    private fun findDWords(result: com.google.mlkit.vision.text.Text): String {
        val dWords = mutableListOf<String>()

        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    if (element.text.startsWith("D", ignoreCase = true)) {
                        dWords.add(element.text)
                    }
                }
            }
        }

        return if (dWords.isEmpty()) "No words starting with D found"
        else dWords.joinToString("\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        recognizer.close()
    }
}