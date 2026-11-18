package com.example.ocrdemo

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val code = CodeStore.getCode(this)
        findViewById<TextView>(R.id.tvCode).text = code

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("code", code))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear DO list?")
                .setMessage("This removes all DO numbers from the main screen. Duplicates of numbers you already scanned will no longer be detected, and this can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    // Tell MainActivity to clear its list when we go back
                    setResult(RESULT_OK, Intent().putExtra("cleared", true))
                    Toast.makeText(this, "List cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }
}