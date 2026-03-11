package com.example.linecommunityjoiner

import kotlin.math.max

object CommunityJoinSession {
    const val ACTION_START = "com.example.linecommunityjoiner.ACTION_START"
    const val ACTION_STOP = "com.example.linecommunityjoiner.ACTION_STOP"
    const val ACTION_PAUSE = "com.example.linecommunityjoiner.ACTION_PAUSE"
    const val ACTION_RESUME = "com.example.linecommunityjoiner.ACTION_RESUME"
    const val ACTION_PROGRESS = "com.example.linecommunityjoiner.ACTION_PROGRESS"
    const val EXTRA_MSG = "msg"

    @Volatile var isRunning: Boolean = false
    @Volatile var isPaused: Boolean = false
    @Volatile var stopRequested: Boolean = false

    private var urls: MutableList<String> = mutableListOf()
    private var linePackages: MutableList<String> = mutableListOf()
    private var nickname: String = ""
    private var waitSeconds: Int = 0
    private var currentIndex: Int = 0
    private var currentLineIndex: Int = 0
    private var importedFileBaseName: String = "社群網址"
    private val validCommunityUrls: LinkedHashSet<String> = LinkedHashSet()

    fun start(newUrls: List<String>, selectedPackages: List<String>, selectedNickname: String, selectedWaitSeconds: Int) {
        urls.clear()
        urls.addAll(newUrls)
        linePackages.clear()
        linePackages.addAll(selectedPackages)
        currentIndex = 0
        currentLineIndex = 0
        nickname = selectedNickname.trim()
        waitSeconds = max(selectedWaitSeconds, 1)
        stopRequested = false
        isPaused = false
        isRunning = true
        validCommunityUrls.clear()
    }

    fun currentUrl(): String? {
        return urls.getOrNull(currentIndex)
    }

    fun currentLinePackage(): String? {
        return linePackages.getOrNull(currentLineIndex)
    }

    fun getCurrentIndex(): Int = currentIndex
    fun getCurrentLineIndex(): Int = currentLineIndex
    fun getNickname(): String = nickname
    fun getWaitSeconds(): Int = waitSeconds
    fun getValidCommunityUrls(): LinkedHashSet<String> = validCommunityUrls

    fun moveNextTarget(): Boolean {
        currentIndex++
        if (currentIndex < urls.size) {
            return true
        }
        currentIndex = 0
        currentLineIndex++
        return currentLineIndex < linePackages.size
    }

    fun setImportedFileName(displayName: String?) {
        importedFileBaseName = sanitizeFileBaseName(displayName)
    }

    fun getValidOutputFileName(): String = "${importedFileBaseName}可加.txt"

    fun markCurrentUrlAsValid(): Boolean {
        val url = currentUrl() ?: return false
        return validCommunityUrls.add(url)
    }

    private fun sanitizeFileBaseName(displayName: String?): String {
        if (displayName.isNullOrBlank()) return "社群網址"
        val raw = displayName.substringAfterLast('/').trim()
        val base = raw.substringBeforeLast('.', raw).trim()
        return if (base.isBlank()) "社群網址" else base
    }

    fun resetAll() {
        isRunning = false
        isPaused = false
        stopRequested = false
        urls.clear()
        currentIndex = 0
        linePackages.clear()
        currentLineIndex = 0
        nickname = ""
        waitSeconds = 0
        importedFileBaseName = "社群網址"
        validCommunityUrls.clear()
    }

    fun finishAll() {
        isRunning = false
        isPaused = false
        stopRequested = false
    }
}
