package com.example.linecommunityjoiner

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.SpinnerAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var lineSelectorText: TextView
    private lateinit var nicknameInput: EditText
    private lateinit var secondsMinInput: EditText
    private lateinit var secondsMaxInput: EditText
    private lateinit var scheduleSpinner: Spinner
    private lateinit var postMessageInput: EditText
    private lateinit var urlsInput: EditText
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
    private var scheduleOptions: List<String> = emptyList()
    private var isRestoringUi = false

    private val importTextFileLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                persistUiState()
            } else {
                importUrlsFromFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LicenseGate.ensureActivatedOrRedirect(this)) return
        setContentView(R.layout.activity_main)

        lineSelectorText = findViewById(R.id.lineSelectorText)
        nicknameInput = findViewById(R.id.nicknameInput)
        secondsMinInput = findViewById(R.id.secondsMinInput)
        secondsMaxInput = findViewById(R.id.secondsMaxInput)
        scheduleSpinner = findViewById(R.id.scheduleSpinner)
        postMessageInput = findViewById(R.id.postMessageInput)
        urlsInput = findViewById(R.id.urlsInput)
        importButton = findViewById(R.id.importButton)
        clearButton = findViewById(R.id.clearButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        pauseButton = findViewById(R.id.pauseButton)
        resumeButton = findViewById(R.id.resumeButton)

        lineNames = resources.getStringArray(R.array.line_names)
        linePackages = resources.getStringArray(R.array.line_packages)
        selectedItems = BooleanArray(lineNames.size) { false }

        setupScheduleSpinner()
        restoreUiStateOrBlank()
        setupAutoSaveListeners()

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
    }

    override fun onResume() {
        super.onResume()
        restoreUiStateOrBlank()
    }

    override fun onPause() {
        persistUiState()
        super.onPause()
    }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREF_UI, MODE_PRIVATE)

    private fun setupScheduleSpinner() {
        val list = mutableListOf("立即開始")
        for (hour in 0 until 24) {
            for (minute in 0 until 60) {
                val format = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
                list.add(format)
            }
        }
        scheduleOptions = list
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, scheduleOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        scheduleSpinner.adapter = adapter as SpinnerAdapter
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
            val intent = Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION", Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun applyInitialBlankState() {
        isRestoringUi = true
        lineSelectorText.text = defaultLineSelectorText
        nicknameInput.setText("")
        secondsMinInput.setText("")
        secondsMaxInput.setText("")
        postMessageInput.setText("")
        urlsInput.setText("")
        scheduleSpinner.setSelection(0, false)
        selectedItems = BooleanArray(lineNames.size) { false }
        selectedLinePackages.clear()
        selectedLineNames.clear()
        isRestoringUi = false
    }

    private fun clearSavedUiState() {
        prefs().edit().clear().apply()
    }

    private fun restoreUiStateOrBlank() {
        val p = prefs()
        val savedNamesRaw = p.getString(KEY_SELECTED_LINE_NAMES, "") ?: ""
        val savedPackagesRaw = p.getString(KEY_SELECTED_LINE_PACKAGES, "") ?: ""
        val savedNickname = p.getString(KEY_NICKNAME, "") ?: ""
        val savedSecondsMin = p.getString(KEY_SECONDS_MIN, "") ?: ""
        val savedSecondsMax = p.getString(KEY_SECONDS_MAX, "") ?: ""
        val savedSchedule = p.getString(KEY_SCHEDULE, "立即開始") ?: "立即開始"
        val savedPostMessage = p.getString(KEY_POST_MESSAGE, "") ?: ""
        val savedUrls = p.getString(KEY_URLS, "") ?: ""
        val hasAnySavedState = !(savedNamesRaw.isBlank() &&
            savedPackagesRaw.isBlank() &&
            savedNickname.isBlank() &&
            savedSecondsMin.isBlank() &&
            savedSecondsMax.isBlank() &&
            savedPostMessage.isBlank() &&
            savedUrls.isBlank() &&
            savedSchedule == "立即開始")
        if (!hasAnySavedState) {
            applyInitialBlankState()
            return
        }
        isRestoringUi = true
        selectedLineNames.clear()
        selectedLinePackages.clear()
        if (savedNamesRaw.isNotBlank()) {
            selectedLineNames.addAll(savedNamesRaw.split("\n").filter { it.isNotBlank() })
        }
        if (savedPackagesRaw.isNotBlank()) {
            selectedLinePackages.addAll(savedPackagesRaw.split("\n").filter { it.isNotBlank() })
        }
        selectedItems = BooleanArray(lineNames.size) { false }
        for (i in lineNames.indices) {
            if (selectedLineNames.contains(lineNames[i])) {
                selectedItems[i] = true
            }
        }
        lineSelectorText.text = if (selectedLineNames.isEmpty()) {
            defaultLineSelectorText
        } else {
            selectedLineNames.joinToString(", ")
        }
        nicknameInput.setText(savedNickname)
        secondsMinInput.setText(savedSecondsMin)
        secondsMaxInput.setText(savedSecondsMax)
        postMessageInput.setText(savedPostMessage)
        urlsInput.setText(savedUrls)
        val idx = scheduleOptions.indexOf(savedSchedule).coerceAtLeast(0)
        scheduleSpinner.setSelection(idx, false)
        isRestoringUi = false
    }

    private fun persistUiState() {
        if (isRestoringUi) return
        val names = selectedLineNames.joinToString("\n")
        val packages = selectedLinePackages.joinToString("\n")
        val scheduleText = scheduleSpinner.selectedItem?.toString().orEmpty()
        prefs().edit()
            .putString(KEY_SELECTED_LINE_NAMES, names)
            .putString(KEY_SELECTED_LINE_PACKAGES, packages)
            .putString(KEY_NICKNAME, nicknameInput.text?.toString().orEmpty())
            .putString(KEY_SECONDS_MIN, secondsMinInput.text?.toString().orEmpty())
            .putString(KEY_SECONDS_MAX, secondsMaxInput.text?.toString().orEmpty())
            .putString(KEY_SCHEDULE, scheduleText)
            .putString(KEY_POST_MESSAGE, postMessageInput.text?.toString().orEmpty())
            .putString(KEY_URLS, urlsInput.text?.toString().orEmpty())
            .apply()
    }

    private fun setupAutoSaveListeners() {
        val watcher = SimpleTextWatcher { persistUiState() }
        nicknameInput.addTextChangedListener(watcher)
        secondsMinInput.addTextChangedListener(watcher)
        secondsMaxInput.addTextChangedListener(watcher)
        postMessageInput.addTextChangedListener(watcher)
        urlsInput.addTextChangedListener(watcher)
        scheduleSpinner.onItemSelectedListener = SimpleItemSelectedListener { persistUiState() }
    }

    private fun clearAllFieldsToInitialState() {
        CommunityJoinSession.resetAll()
        sendBroadcast(Intent(CommunityJoinSession.ACTION_STOP).setPackage(packageName))
        clearSavedUiState()
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
                persistUiState()
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
            urlsInput.setText(lines.joinToString("\n"))
            val importedNameText = displayName ?: "未取得檔名"
            Toast.makeText(this, "已匯入 ${lines.size} 筆社群網址\n來源檔案：$importedNameText", Toast.LENGTH_SHORT).show()
            persistUiState()
        } catch (e: Exception) {
            Toast.makeText(this, "匯入失敗：${e.message}", Toast.LENGTH_SHORT).show()
            persistUiState()
        }
    }

    private fun parseWaitRange(minText: String, maxText: String): Pair<Int, Int>? {
        val min = minText.trim().toIntOrNull()
        val max = maxText.trim().toIntOrNull()
        if (min == null && max == null) return null
        if (min != null && min < 1) return null
        if (max != null && max < 1) return null
        return when {
            min != null && max != null -> if (min > max) null else min to max
            min != null -> min to min
            else -> (max ?: return null) to (max ?: return null)
        }
    }

    private fun startCommunityJoin() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "請先開啟無障礙服務", Toast.LENGTH_LONG).show()
            startActivity(Intent("android.settings.ACCESSIBILITY_SETTINGS"))
            return
        }
        val urls = urlsInput.text.toString().lines()
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
        val waitRange = parseWaitRange(
            secondsMinInput.text?.toString().orEmpty(),
            secondsMaxInput.text?.toString().orEmpty()
        )
        if (waitRange == null) {
            Toast.makeText(this, "請輸入正確秒數，至少要填一格；若兩格都填，左邊不可大於右邊", Toast.LENGTH_LONG).show()
            return
        }
        val selectedSchedule = scheduleSpinner.selectedItem?.toString().orEmpty()
        val scheduleText = if (selectedSchedule == "立即開始") "" else selectedSchedule
        val postMessage = postMessageInput.text?.toString().orEmpty()
        CommunityJoinSession.start(
            urls,
            selectedLinePackages.toList(),
            nickname,
            waitRange.first,
            waitRange.second,
            postMessage,
            scheduleText
        )
        persistUiState()
        sendBroadcast(Intent(CommunityJoinSession.ACTION_START).setPackage(packageName))
    }

    private fun stopCommunityJoin() {
        CommunityJoinSession.stopRequested = true
        CommunityJoinSession.isRunning = false
        CommunityJoinSession.isPaused = false
        sendBroadcast(Intent(CommunityJoinSession.ACTION_STOP).setPackage(packageName))
    }

    private fun pauseCommunityJoin() {
        if (!CommunityJoinSession.isRunning) {
            Toast.makeText(this, "目前沒有執行中的流程", Toast.LENGTH_SHORT).show()
            return
        }
        CommunityJoinSession.isPaused = true
        sendBroadcast(Intent(CommunityJoinSession.ACTION_PAUSE).setPackage(packageName))
    }

    private fun resumeCommunityJoin() {
        if (!CommunityJoinSession.isRunning) {
            Toast.makeText(this, "目前沒有可繼續的流程", Toast.LENGTH_SHORT).show()
            return
        }
        CommunityJoinSession.isPaused = false
        sendBroadcast(Intent(CommunityJoinSession.ACTION_RESUME).setPackage(packageName))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (service in enabledServices) {
            val id = service.id
            if (id.contains(packageName) && id.contains(CommunityJoinAccessibilityService::class.java.simpleName)) {
                return true
            }
        }
        return false
    }

    private class SimpleTextWatcher(private val onChange: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = onChange()
    }

    private class SimpleItemSelectedListener(private val onChange: () -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = onChange()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    companion object {
        private const val PREF_UI = "main_ui_state"
        private const val KEY_SELECTED_LINE_NAMES = "selected_line_names"
        private const val KEY_SELECTED_LINE_PACKAGES = "selected_line_packages"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_SECONDS_MIN = "seconds_min"
        private const val KEY_SECONDS_MAX = "seconds_max"
        private const val KEY_SCHEDULE = "schedule"
        private const val KEY_POST_MESSAGE = "post_message"
        private const val KEY_URLS = "urls"
    }
}
