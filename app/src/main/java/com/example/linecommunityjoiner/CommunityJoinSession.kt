package com.example.linecommunityjoiner

import android.util.Log
import java.util.Calendar
import java.util.LinkedHashSet
import kotlin.math.max
import kotlin.random.Random

object CommunityJoinSession {
    const val ACTION_START = "com.example.linecommunityjoiner.ACTION_START"
    const val ACTION_STOP = "com.example.linecommunityjoiner.ACTION_STOP"
    const val ACTION_PAUSE = "com.example.linecommunityjoiner.ACTION_PAUSE"
    const val ACTION_RESUME = "com.example.linecommunityjoiner.ACTION_RESUME"
    const val ACTION_PROGRESS = "com.example.linecommunityjoiner.ACTION_PROGRESS"
    const val EXTRA_MSG = "msg"

    private val moveNextLock = Any()

    @Volatile var isRunning: Boolean = false
    @Volatile var isPaused: Boolean = false
    @Volatile var stopRequested: Boolean = false

    private var urls: MutableList<String> = mutableListOf()
    private var linePackages: MutableList<String> = mutableListOf()
    private var nickname: String = ""
    private var waitSeconds: Int = 0
    private var waitMinSeconds: Int = 0
    private var waitMaxSeconds: Int = 0
    private var currentCycleWaitSeconds: Int = 0
    private var messageTemplate: String = ""
    private var scheduledStartTimeText: String = ""
    private var scheduleHour: Int = -1
    private var scheduleMinute: Int = -1
    private var currentIndex: Int = 0
    private var currentLineIndex: Int = 0
    private var currentLineRunCount: Int = 0
    private val runsPerLine: Int = 10
    private var importedFileBaseName: String = "社群網址"
    private var postReminderConfirmedAt: Long? = null
    private var currentUrlCanPostAfterJoin: Boolean = false
    private var currentUrlJoinRecorded: Boolean = false
    private var completedJoinCount: Int = 0
    private val validCommunityUrls: LinkedHashSet<String> = LinkedHashSet()

    fun start(
        newUrls: List<String>,
        selectedPackages: List<String>,
        selectedNickname: String,
        selectedWaitMinSeconds: Int,
        selectedWaitMaxSeconds: Int,
        selectedPostMessage: String,
        selectedScheduleTime: String
    ) {
        urls.clear()
        urls.addAll(newUrls)
        linePackages.clear()
        linePackages.addAll(selectedPackages)
        currentIndex = 0
        currentLineIndex = 0
        currentLineRunCount = 0
        nickname = selectedNickname.trim()
        waitMinSeconds = max(selectedWaitMinSeconds, 1)
        waitMaxSeconds = max(selectedWaitMaxSeconds, waitMinSeconds)
        waitSeconds = waitMaxSeconds
        currentCycleWaitSeconds = 0
        messageTemplate = selectedPostMessage
        applySchedule(selectedScheduleTime)
        stopRequested = false
        isPaused = false
        isRunning = true
        postReminderConfirmedAt = null
        if (importedFileBaseName.isBlank()) {
            importedFileBaseName = "社群網址"
        }
        validCommunityUrls.clear()
        currentUrlCanPostAfterJoin = false
        currentUrlJoinRecorded = false
        completedJoinCount = 0
    }

    private fun applySchedule(text: String) {
        val normalized = text.trim()
        scheduledStartTimeText = normalized
        if (normalized.isBlank()) {
            scheduleHour = -1
            scheduleMinute = -1
            return
        }
        val parts = normalized.split(":")
        if (parts.size == 2) {
            val h = parts[0].toIntOrNull()
            val m = parts[1].toIntOrNull()
            if (h != null && m != null && h in 0..23 && m in 0..59) {
                scheduleHour = h
                scheduleMinute = m
                return
            }
        }
        scheduleHour = -1
        scheduleMinute = -1
        scheduledStartTimeText = ""
    }

    fun hasScheduledStart(): Boolean {
        return scheduleHour in 0..23 && scheduleMinute in 0..59
    }

    fun computeDelayMillisUntilScheduledStart(nowMillis: Long = System.currentTimeMillis()): Long {
        if (!hasScheduledStart()) return 0L
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val target = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, scheduleHour)
            set(Calendar.MINUTE, scheduleMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        return max(target.timeInMillis - now.timeInMillis, 0L)
    }

    fun currentUrl(): String? = urls.getOrNull(currentIndex)
    fun currentLinePackage(): String? = linePackages.getOrNull(currentLineIndex)

    fun getCurrentIndex(): Int = currentIndex
    fun getCurrentLineIndex(): Int = currentLineIndex
    fun getNickname(): String = nickname
    fun getWaitSeconds(): Int = waitSeconds
    fun getWaitMinSeconds(): Int = waitMinSeconds
    fun getWaitMaxSeconds(): Int = waitMaxSeconds
    fun getCurrentCycleWaitSeconds(): Int = currentCycleWaitSeconds
    fun getMessageTemplate(): String = messageTemplate
    fun getScheduledStartTimeText(): String = scheduledStartTimeText
    fun getPostReminderConfirmedAt(): Long? = postReminderConfirmedAt
    fun getValidCommunityUrls(): LinkedHashSet<String> = validCommunityUrls
    fun getUrls(): List<String> = urls
    fun getLinePackages(): List<String> = linePackages
    fun getCurrentUrlCanPostAfterJoin(): Boolean = currentUrlCanPostAfterJoin
    fun getCurrentUrlJoinRecorded(): Boolean = currentUrlJoinRecorded
    fun getCompletedJoinCount(): Int = completedJoinCount

    fun setCurrentUrlCanPostAfterJoin(value: Boolean) {
        currentUrlCanPostAfterJoin = value
    }

    fun setCurrentUrlJoinRecorded(value: Boolean) {
        currentUrlJoinRecorded = value
    }

    fun setCurrentCycleWaitSeconds(value: Int) {
        currentCycleWaitSeconds = value
    }

    fun setPostReminderConfirmedAt(value: Long?) {
        postReminderConfirmedAt = value
    }

    fun hasMessageTemplate(): Boolean = messageTemplate.isNotBlank()

    fun nextWaitSeconds(): Int {
        val min = max(waitMinSeconds, 1)
        val maxWait = max(waitMaxSeconds, min)
        currentCycleWaitSeconds = if (min == maxWait) min else Random.Default.nextInt(min, maxWait + 1)
        currentCycleWaitSeconds = max(currentCycleWaitSeconds, 1)
        return currentCycleWaitSeconds
    }

    fun markJoinCompletedOnce(): Int {
        if (!currentUrlJoinRecorded) {
            currentUrlJoinRecorded = true
            completedJoinCount++
        }
        return completedJoinCount
    }

    fun moveNextTarget(): Boolean {
        synchronized(moveNextLock) {
            Log.d("CommunitySession", "=== moveNextTarget() 被調用 ===")
            Log.d("CommunitySession", "調用前 - currentIndex: $currentIndex, currentLineIndex: $currentLineIndex")
            postReminderConfirmedAt = null
            currentUrlCanPostAfterJoin = false
            currentUrlJoinRecorded = false
            if (urls.isEmpty() || linePackages.isEmpty()) {
                return false
            }
            currentIndex = (currentIndex + 1) % urls.size
            currentLineRunCount++
            if (currentLineRunCount >= runsPerLine) {
                currentLineRunCount = 0
                currentLineIndex = (currentLineIndex + 1) % linePackages.size
                Log.d(
                    "CommunitySession",
                    "調用後 - currentIndex: $currentIndex, currentLineIndex: $currentLineIndex（已跑滿 $runsPerLine 次，切換到下一個 LINE 帳號）"
                )
            } else {
                Log.d(
                    "CommunitySession",
                    "調用後 - currentIndex: $currentIndex（同一個 LINE 帳號，第 $currentLineRunCount / $runsPerLine 次）"
                )
            }
            return true
        }
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
        currentLineRunCount = 0
        nickname = ""
        waitSeconds = 0
        waitMinSeconds = 0
        waitMaxSeconds = 0
        currentCycleWaitSeconds = 0
        messageTemplate = ""
        scheduledStartTimeText = ""
        scheduleHour = -1
        scheduleMinute = -1
        postReminderConfirmedAt = null
        importedFileBaseName = "社群網址"
        validCommunityUrls.clear()
        currentUrlCanPostAfterJoin = false
        currentUrlJoinRecorded = false
        completedJoinCount = 0
    }

    fun finishAll() {
        isRunning = false
        isPaused = false
        stopRequested = false
    }
}
