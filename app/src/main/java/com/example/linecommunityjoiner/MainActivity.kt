package com.example.linecommunityjoiner

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    private lateinit var lineSelectorText: TextView
    private lateinit var nicknameInput: EditText
    private lateinit var secondsInput: EditText
    private lateinit var urlsInput: EditText
    private lateinit var progressText: TextView
    private lateinit var importButton: Button
    private lateinit var clearButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var pauseButton: Button
    private lateinit var resumeButton: Button

    private lateinit var lineNames: Array<String>
    private lateinit var linePackages: Array<String>
    private lateinit var selectedItems: BooleanArray
    private val selectedLinePackages = mutableListOf<String>()
    private val selectedLineNames = mutableListOf<String>()
    private val defaultLineSelectorText = "請選擇 LINE 分身（可複選）："

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(CommunityJoinSession.EXTRA_MSG) ?: return
            progressText.text = msg
        }
    }

    private val importTextFileLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                progressText.text = ""
                return@registerForActivityResult
            }
            importUrlsFromFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LicenseGate.ensureActivatedOrRedirect(this)) {
            return
        }
        setContentView(R.layout.activity_main)
        lineSelectorText = findViewById(R.id.lineSelectorText)
        nicknameInput = findViewById(R.id.nicknameInput)
        secondsInput = findViewById(R.id.secondsInput)
        urlsInput = findViewById(R.id.urlsInput)
        progressText = findViewById(R.id.progressText)
        importButton = findViewById(R.id.importButton)
        clearButton = findViewById(R.id.clearButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        pauseButton = findViewById(R.id.pauseButton)
        resumeButton = findViewById(R.id.resumeButton)

        lineNames = resources.getStringArray(R.array.line_names)
        linePackages = resources.getStringArray(R.array.line_packages)
        selectedItems = BooleanArray(lineNames.size) { false }

        applyInitialBlankState()
        lineSelectorText.setOnClickListener { showLineMultiSelectDialog() }
        importButton.setOnClickListener { openTextFilePicker() }
        clearButton.setOnClickListener { clearAllFieldsToInitialState() }
        startButton.setOnClickListener {
            ensureOverlayPermission()
            startCommunityJoin()
        }
        stopButton.setOnClickListener { stopCommunityJoin() }
        pauseButton.setOnClickListener { pauseCommunityJoin() }
        resumeButton.setOnClickListener { resumeCommunityJoin() }

        registerProgressReceiver()
    }

    private fun getDisplayNameFromUri(uri: Uri): String? {
        val projection = arrayOf("_display_name")
        val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex("_display_name")
                if (idx >= 0) return it.getString(idx)
            }
        }
        return null
    }

    private fun ensureOverlayPermission() {
        if (!OverlayStatusManager.hasPermission(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun applyInitialBlankState() {
        lineSelectorText.text = defaultLineSelectorText
        nicknameInput.setText("")
        secondsInput.setText("")
        urlsInput.setText("")
        progressText.setText("")
        selectedItems = BooleanArray(lineNames.size) { false }
        selectedLinePackages.clear()
        selectedLineNames.clear()
    }

    private fun clearAllFieldsToInitialState() {
        CommunityJoinSession.resetAll()
        sendBroadcast(Intent(CommunityJoinSession.ACTION_STOP).setPackage(packageName))
        applyInitialBlankState()
    }

    private fun showLineMultiSelectDialog() {
        AlertDialog.Builder(this)
            .setTitle("請選擇 LINE 分身（可複選）")
            .setMultiChoiceItems(lineNames, selectedItems) { _, which, isChecked ->
                selectedItems[which] = isChecked
            }
            .setPositiveButton("確定") { _, _ ->
                selectedLinePackages.clear()
                selectedLineNames.clear()
                for (i in lineNames.indices) {
                    if (selectedItems[i]) {
                        selectedLineNames.add(lineNames[i])
                        selectedLinePackages.add(linePackages[i])
                    }
                }
                lineSelectorText.text = if (selectedLineNames.isEmpty()) {
                    defaultLineSelectorText
                } else {
                    selectedLineNames.joinToString(", ")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openTextFilePicker() {
        try {
            importTextFileLauncher.launch(arrayOf("text/plain", "text/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "無法開啟檔案選擇器：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importUrlsFromFile(uri: Uri) {
        try {
            val lines = mutableListOf<String>()
            val displayName = getDisplayNameFromUri(uri)
            CommunityJoinSession.setImportedFileName(displayName)
            contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        val text = line.trim()
                        if (text.startsWith("http://") || text.startsWith("https://")) {
                            lines.add(text)
                        }
                    }
                }
            }
            if (lines.isEmpty()) {
                progressText.text = "⚠️ 匯入檔案沒有有效網址"
                return
            }
            urlsInput.setText(lines.joinToString("\n"))
            progressText.text = "✅ 已匯入 ${lines.size} 筆網址"
        } catch (e: Exception) {
            progressText.text = "❌ 匯入失敗：${e.message}"
        }
    }

    private fun startCommunityJoin() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "請先開啟無障礙服務", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val urls = urlsInput.text.toString()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://")) }
        if (urls.isEmpty()) {
            Toast.makeText(this, "請至少輸入或匯入一筆有效社群網址", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedLinePackages.isEmpty()) {
            Toast.makeText(this, "請先選擇至少一個 LINE 分身", Toast.LENGTH_SHORT).show()
            return
        }
        val nickname = nicknameInput.text.toString().trim()
        if (nickname.isBlank()) {
            Toast.makeText(this, "請輸入社群暱稱", Toast.LENGTH_SHORT).show()
            return
        }
        val waitSeconds = secondsInput.text.toString().trim().toIntOrNull()
        if (waitSeconds == null || waitSeconds < 1) {
            Toast.makeText(this, "請輸入大於 0 的秒數", Toast.LENGTH_SHORT).show()
            return
        }
        CommunityJoinSession.start(urls, selectedLinePackages.toList(), nickname, waitSeconds)
        progressText.text = "▶️ 已送出開始指令"
        sendBroadcast(Intent(CommunityJoinSession.ACTION_START).setPackage(packageName))
    }

    private fun stopCommunityJoin() {
        CommunityJoinSession.stopRequested = true
        CommunityJoinSession.isRunning = false
        CommunityJoinSession.isPaused = false
        sendBroadcast(Intent(CommunityJoinSession.ACTION_STOP).setPackage(packageName))
        progressText.text = "⛔ 已送出停止指令"
    }

    private fun pauseCommunityJoin() {
        if (!CommunityJoinSession.isRunning) {
            Toast.makeText(this, "目前沒有執行中的流程", Toast.LENGTH_SHORT).show()
            return
        }
        CommunityJoinSession.isPaused = true
        sendBroadcast(Intent(CommunityJoinSession.ACTION_PAUSE).setPackage(packageName))
        progressText.text = "⏸ 已送出暫停指令"
    }

    private fun resumeCommunityJoin() {
        if (!CommunityJoinSession.isRunning) {
            Toast.makeText(this, "目前沒有可繼續的流程", Toast.LENGTH_SHORT).show()
            return
        }
        CommunityJoinSession.isPaused = false
        sendBroadcast(Intent(CommunityJoinSession.ACTION_RESUME).setPackage(packageName))
        progressText.text = "⏯ 已送出繼續指令"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedId = "$packageName/.CommunityJoinAccessibilityService"
        return enabled.any { it.id == expectedId }
    }

    private fun registerProgressReceiver() {
        val filter = IntentFilter(CommunityJoinSession.ACTION_PROGRESS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(progressReceiver, filter)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(progressReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
