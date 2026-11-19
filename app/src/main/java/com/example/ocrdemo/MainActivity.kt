package com.example.ocrdemo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.LinearLayout
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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import com.example.ocrdemo.R.id.btnCameraText
import com.example.ocrdemo.R.id.btnCameraQR
import com.example.ocrdemo.R.id.btnEnterText

class MainActivity : AppCompatActivity() {
    // Secret code lives in CodeStore; it is shown on the Settings screen
    private val secretCode by lazy { CodeStore.getCode(this) }

    private val db by lazy { FirebaseDatabase.getInstance(BuildConfig.DB_URL) }

    private val dbRef by lazy {
        db.getReference("lists/$secretCode/pending")
    }
    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private lateinit var previewView: PreviewView
    private lateinit var previewCard: MaterialCardView
    private lateinit var tvResult: TextView
    private lateinit var inputGroup: LinearLayout

    // Input Group
    private lateinit var tilInput: TextInputLayout
    private lateinit var etInput: TextInputEditText
    private lateinit var btnSubmit: MaterialButton

    // Status
    private var statusReady = false
    private var lastWarnTime = 0L
    private var statusRef: DatabaseReference? = null
    private var statusListener: ValueEventListener? = null

    // Results from the extension
    private val doneNumbers = mutableSetOf<String>()
    private val failedNumbers = mutableSetOf<String>()
    private var doneRef: DatabaseReference? = null
    private var failedRef: DatabaseReference? = null
    private var doneListener: ValueEventListener? = null
    private var failedListener: ValueEventListener? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lastScanTime = 0L

    // Candidate number seen in the previous frame (for the 2-in-a-row check)
    private var pendingNumber: String? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // List of DO number collected from camera captures
    private val capturedDOs = mutableListOf<String>()

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

        previewView = findViewById(R.id.previewView)
        inputGroup = findViewById(R.id.inputGroup)
        tvResult = findViewById(R.id.tvResult)
        previewCard = findViewById(R.id.previewCard)

        savedInstanceState?.getStringArrayList("captured")?.let {
            capturedDOs.addAll(it)
            if (capturedDOs.isNotEmpty()) showDOs()
        }

        findViewById<MaterialToolbar>(R.id.topAppBar).setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    openSettings.launch(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        findViewById<Button>(btnCameraQR).setOnClickListener {
            hideKeyboard()
            openCamera(ScanMode.QR)
        }
        findViewById<Button>(btnCameraText).setOnClickListener {
            hideKeyboard()
            openCamera(ScanMode.TEXT)
        }
        findViewById<Button>(btnEnterText).setOnClickListener {
            cameraProvider?.unbindAll()
            previewView.visibility = View.GONE
            previewCard.visibility = View.GONE
            inputGroup.visibility = View.VISIBLE
        }
        listenStatus()
        listenResults()
        onCreateInputGroup()
    }

    private fun onCreateInputGroup() {
        tilInput = findViewById(R.id.tilInput)
        etInput = findViewById(R.id.etInput)
        btnSubmit = findViewById(R.id.btnSubmit)

        btnSubmit.setOnClickListener { submit() }

        // Clear the error as soon as the user starts typing again
        etInput.setOnFocusChangeListener { _, _ -> tilInput.error = null }
    }

    private fun submit() {
        val text = etInput.text?.toString()?.trim().orEmpty()

        when {
            text.isEmpty() -> {
                tilInput.error = "Please enter something"
                return
            }
            text.length != 9 -> {
                tilInput.error = "DO number must be 9 digits"
                return
            }
            // check if duplicate
            text in capturedDOs -> {
                tilInput.error = "This DO number is already in the list"
                return
            }
        }

        tilInput.error = null

        if (addNumbers(listOf(text))) {
            vibrateShort()
            etInput.text?.clear()
        }
    }

    private fun hideKeyboard(){
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(etInput.windowToken, 0)
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
        previewCard.visibility = View.VISIBLE
        previewView.visibility = View.VISIBLE
        inputGroup.visibility = View.GONE

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
        inputGroup.visibility = View.GONE
    }

    // Adds only valid, new numbers, and sends only those to Firebase
    private fun addNumbers(numbers: List<String>): Boolean {
        // check if the correct url and extension is opened
        if (!isExtensionReady()) {
            warnNotReady()
            return false
        }

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
        if (capturedDOs.isEmpty()) {
            tvResult.text = "No DO number found"
            return
        }
        val sb = SpannableStringBuilder()
        capturedDOs.asReversed().forEachIndexed { i, n ->
            if (i > 0) sb.append("\n")
            sb.append(n)

            val iconRes = when {
                n in doneNumbers -> R.drawable.ic_check_circle
                n in failedNumbers -> R.drawable.ic_cancel
                else -> null
            }
            if (iconRes != null) {
                sb.append("  ")  // gap, then a placeholder character the icon replaces
                sb.setSpan(
                    iconSpan(iconRes),
                    sb.length - 1, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        tvResult.text = sb
    }

    private fun iconSpan(resId: Int): ImageSpan {
        val d = ContextCompat.getDrawable(this, resId)!!
        val size = (tvResult.textSize * 1.1f).toInt()  // scales with the 18sp text
        d.setBounds(0, 0, size, size)
        return ImageSpan(d, ImageSpan.ALIGN_BASELINE)
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
        statusListener?.let { statusRef?.removeEventListener(it) }
        doneListener?.let { doneRef?.removeEventListener(it) }
        failedListener?.let { failedRef?.removeEventListener(it) }
        cameraProvider?.unbindAll()
        recognizer.close()
        barcodeScanner.close()
        analysisExecutor.shutdown()
    }

    private fun listenStatus() {
        statusRef = db.getReference("lists/$secretCode/status")
        statusListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                statusReady = s.child("ready").getValue(Boolean::class.java) ?: false
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        statusRef!!.addValueEventListener(statusListener!!)
    }

    private fun isExtensionReady() = statusReady

    private fun warnNotReady() {
        val now = System.currentTimeMillis()
        if (now - lastWarnTime < 4000) return
        lastWarnTime = now
        Toast.makeText(this,
            "Not connected. Open the correct page and the extension panel.",
            Toast.LENGTH_LONG).show()
    }

    private fun listenResults() {
        doneRef = db.getReference("lists/$secretCode/done")
        doneListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                doneNumbers.clear()
                s.children.forEach { c -> c.value?.toString()?.let { doneNumbers.add(it) } }
                if (capturedDOs.isNotEmpty()) showDOs()
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        doneRef!!.addValueEventListener(doneListener!!)

        failedRef = db.getReference("lists/$secretCode/failed")
        failedListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                failedNumbers.clear()
                s.children.forEach { c ->
                    c.child("number").value?.toString()?.let { failedNumbers.add(it) }
                }
                if (capturedDOs.isNotEmpty()) showDOs()
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        failedRef!!.addValueEventListener(failedListener!!)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("captured", ArrayList(capturedDOs))
    }
}