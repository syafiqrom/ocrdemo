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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.firebase.database.FirebaseDatabase
import com.google.android.material.appbar.MaterialToolbar
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.content.Intent
import com.example.ocrdemo.R.id.btnCameraText
import com.example.ocrdemo.R.id.btnCameraQR

class MainActivity : AppCompatActivity() {
    // Secret code lives in CodeStore; it is shown on the Settings screen
    private val secretCode by lazy { CodeStore.getCode(this) }

    private val dbRef by lazy {
        FirebaseDatabase
            .getInstance(BuildConfig.DB_URL)
            .getReference("lists/$secretCode/pending")
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

    // List of DO number collected from camera captures
    private val capturedDOs = mutableListOf<String>()

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

    // Opens Settings and listens for the "clear list" result
    private val openSettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK &&
                result.data?.getBooleanExtra("cleared", false) == true
            ) {
                clearList()
            }
        }

    private enum class ScanMode { TEXT, QR }
    private var scanMode = ScanMode.TEXT

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Generate random code the first time the app opens and save it on the phone
        CodeStore.getCode(this)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        previewView = findViewById(R.id.previewView)
        tvResult = findViewById(R.id.tvResult)

//        findViewById<Button>(R.id.btnPick).setOnClickListener {
//            pickImages.launch("image/*")
//        }
        findViewById<MaterialToolbar>(R.id.topAppBar).setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    openSettings.launch(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        findViewById<Button>(btnCameraText).setOnClickListener {
            openCamera(ScanMode.TEXT)
        }
        findViewById<Button>(btnCameraQR).setOnClickListener {
            openCamera(ScanMode.QR)
        }
    }

    private fun openCamera(mode: ScanMode) {
        scanMode = mode
        pendingNumber = null   // reset the 2-in-a-row check when switching modes

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

        when (scanMode) {
            ScanMode.TEXT -> recognizer.process(image)
                .addOnSuccessListener { result -> confirmAndAdd(findDOText(result)) }
                .addOnCompleteListener { proxy.close() }

            ScanMode.QR -> barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    confirmAndAdd(barcodes.mapNotNull { it.rawValue?.trim() })
                }
                .addOnCompleteListener { proxy.close() }
        }
    }

    // Camera only: a number must be seen in 2 consecutive scans before it is added
    private fun confirmAndAdd(numbers: List<String>) {
        val candidate = numbers.firstOrNull { it.length == 9 && it.all { c -> c.isDigit() } }

        if (candidate == null) {
            pendingNumber = null          // nothing valid this frame, start over
            return
        }
        if (candidate in capturedDOs) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(120)
        }
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
                showDOs()
            }
        }

        uris.forEachIndexed { index, uri ->
            try {
                val image = InputImage.fromFilePath(this, uri)

                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        results[index] = findDOText(result)
                        onOneDone()
                    }
                    .addOnFailureListener {
                        results[index] = emptyList()
                        onOneDone()
                    }
            } catch (e: Exception) {
                android.util.Log.e("OCR", "Could not read image", e)
                results[index] = emptyList()
                onOneDone()
            }
        }
    }

    // Adds only valid, new numbers, and sends only those to Firebase
    private fun addNumbers(numbers: List<String>): Boolean {
        var added = false
        for (n in numbers) {
            if (n.length == 9 && n.all { it.isDigit() } && n !in capturedDOs) {
                capturedDOs.add(n)
                dbRef.push().setValue(n)
                added = true
            }
        }
        if (added) showDOs()
        return added
    }

    private fun showDOs() {
        tvResult.text =
            if (capturedDOs.isEmpty()) "No DO number found"
            else capturedDOs.joinToString("\n")
    }

    private fun clearList() {
        capturedDOs.clear()
        pendingNumber = null
        tvResult.text = "Recognized text will appear here"
    }

    // Finds the "Number/Date" label and returns the DO number printed below it
    private fun findDOText(result: com.google.mlkit.vision.text.Text): List<String> {
        val lines = result.textBlocks.flatMap { it.lines }

        // 1. Find the label line
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
        barcodeScanner.close()
        analysisExecutor.shutdown()
    }
}