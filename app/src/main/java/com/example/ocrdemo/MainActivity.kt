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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.firebase.database.FirebaseDatabase
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build

class MainActivity : AppCompatActivity() {

    private val secretCode = BuildConfig.SECRET_CODE

    private val dbRef by lazy {
        FirebaseDatabase
            .getInstance("https://ocr-star-b3312-default-rtdb.asia-southeast1.firebasedatabase.app")
            .getReference("lists/$secretCode/pending")
            //.getReference("lists/$secretCode")
    }
    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private lateinit var imageView: ImageView
    private lateinit var previewView: PreviewView
    private lateinit var tvResult: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lastScanTime = 0L

    // Candidate number seen in the previous frame (for the 2-in-a-row check)
    private var pendingNumber: String? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // Words collected from camera captures (keeps growing)
    private val capturedWords = mutableListOf<String>()

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
        tvResult = findViewById(R.id.tvResult)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickImages.launch("image/*")
        }
        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            openCamera()
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
        imageView.visibility = View.GONE

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { proxy -> analyzeFrame(proxy) } }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Runs on each camera frame, at most every 250 ms
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val mediaImage = proxy.image
        if (mediaImage == null || now - lastScanTime < 250) {
            proxy.close()
            return
        }
        lastScanTime = now

        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { result -> confirmAndAdd(findDWordList(result)) }
            .addOnCompleteListener { proxy.close() }   // must always close the frame
    }

    // Camera only: a number must be seen in 2 consecutive scans before it is added
    private fun confirmAndAdd(numbers: List<String>) {
        val candidate = numbers.firstOrNull { it.length == 9 && it.all { c -> c.isDigit() } }

        if (candidate == null) {
            pendingNumber = null          // nothing valid this frame, start over
            return
        }
        if (candidate in capturedWords) {
            pendingNumber = null          // already in the list, ignore
            return
        }

        if (candidate == pendingNumber) {
            pendingNumber = null
            if (addNumbers(listOf(candidate))) vibrateShort()
        } else {
            pendingNumber = candidate     // first sighting, wait for the next frame
        }
    }

    private fun vibrateShort() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        previewView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
    }

    // Reads one or more images and adds the DO number to the shared list
    private fun addImages(uris: List<Uri>) {
        val results = arrayOfNulls<List<String>>(uris.size)
        var finished = 0

        fun onOneDone() {
            finished++
            if (finished == uris.size) {
                // keep the order the images were picked in
                results.forEach { list -> if (list != null) addNumbers(list) }
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

    // Adds only valid, new numbers, and sends only those to Firebase
    private fun addNumbers(numbers: List<String>): Boolean {
        var added = false
        for (n in numbers) {
            if (n.length == 9 && n.all { it.isDigit() } && n !in capturedWords) {
                capturedWords.add(n)
                dbRef.push().setValue(n)
                added = true
            }
        }
        if (added) showWords()
        return added
    }

    private fun showWords() {
        tvResult.text =
            if (capturedWords.isEmpty()) "No DO number found"
            else capturedWords.joinToString("\n")
    }

    // Finds the "Number/Date" label and returns the number printed below it
    private fun findDWordList(result: com.google.mlkit.vision.text.Text): List<String> {
        val lines = result.textBlocks.flatMap { it.lines }

        // 1. Find the label line (ignores spaces and capital letters)
        val label = lines.firstOrNull {
            it.text.replace(" ", "").contains("Number/Date", ignoreCase = true)
        } ?: return emptyList()
        val labelBox = label.boundingBox ?: return emptyList()

        // 2. Find the closest line below the label that sits in the same column
        val valueLine = lines
            .filter { it !== label }
            .filter {
                val b = it.boundingBox
                b != null &&
                        b.top > labelBox.centerY() &&
                        b.left <= labelBox.right &&
                        b.right >= labelBox.left
            }
            .minByOrNull { it.boundingBox!!.top }
            ?: return emptyList()

        // 3. Take the first element that contains digits ("802141782"),
        //    skipping "/" and the date "04.11.2026"
        val number = valueLine.elements
            .map { it.text }
            .firstOrNull { text -> text.isNotEmpty() && text.all { it.isDigit() } }

        return listOfNotNull(number)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        recognizer.close()
        analysisExecutor.shutdown()
    }
}