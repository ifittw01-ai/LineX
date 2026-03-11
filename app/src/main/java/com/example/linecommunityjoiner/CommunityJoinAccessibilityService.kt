package com.example.linecommunityjoiner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CommunityJoinAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var receiversRegistered = false
    private var lastActionSignature = ""
    private var lastActionAt = 0L
    private var flowStage: FlowStage = FlowStage.IDLE
    private var currentDetectedScreen: CommunityScreenType = CommunityScreenType.UNKNOWN
    private var currentUrlOpenedAt = 0L
    private var lastMeaningfulScreenAt = 0L
    private var openStageRecoveryCount = 0
    private var roomListRecoveryCount = 0
    private var finishingCurrentUrl = false
    private var isIntervalWaiting = false
    private var intervalEndRealtime = 0L
    private var intervalRemainingMs = 0L
    private var countdownRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null

    private val startReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (CommunityJoinSession.isRunning) {
                handler.removeCallbacksAndMessages(null)
                clearCountdown()
                clearWatchdog()
                resetFlowState()
                resetIntervalState()
                sendProgress("▶️ 開始執行社群加入流程")
                openCurrentUrlImmediately()
            }
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            clearCountdown()
            clearWatchdog()
            handler.removeCallbacksAndMessages(null)
            resetFlowState()
            resetIntervalState()
            sendProgress("⛔ 已停止")
            OverlayStatusManager.hide()
        }
    }

    private val pauseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (CommunityJoinSession.isRunning) {
                if (isIntervalWaiting) {
                    intervalRemainingMs = (intervalEndRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                }
                CommunityJoinSession.isPaused = true
                clearCountdown()
                clearWatchdog()
                handler.removeCallbacksAndMessages(null)
                sendProgress("⏸ 已暫停")
            }
        }
    }

    private val resumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!CommunityJoinSession.isRunning) return
            CommunityJoinSession.isPaused = false
            sendProgress("⏯ 已繼續")
            if (isIntervalWaiting) {
                startIntervalCountdownFromMillis(intervalRemainingMs)
            } else {
                openCurrentUrlImmediately()
            }
        }
    }

    private enum class FlowStage {
        IDLE,
        URL_OPENED,
        JOIN_BUTTON_TAPPED,
        IN_FLOW
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerInternalReceivers()
        sendProgress("✅ 無障礙服務已連線，請回主畫面按開始")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentPkg = CommunityJoinSession.currentLinePackage()
        if (!CommunityJoinSession.isRunning ||
            CommunityJoinSession.stopRequested ||
            CommunityJoinSession.isPaused ||
            finishingCurrentUrl ||
            isIntervalWaiting ||
            currentPkg == null
        ) {
            return
        }
        val packageName = event?.packageName?.toString().orEmpty()
        if (packageName == currentPkg) {
            processCurrentScreen()
        }
    }

    override fun onInterrupt() {
        Log.d("CommunityService", "onInterrupt")
    }

    override fun onDestroy() {
        if (receiversRegistered) {
            try {
                unregisterReceiver(startReceiver)
                unregisterReceiver(stopReceiver)
                unregisterReceiver(pauseReceiver)
                unregisterReceiver(resumeReceiver)
            } catch (_: Exception) {
            }
        }
        clearCountdown()
        clearWatchdog()
        handler.removeCallbacksAndMessages(null)
        OverlayStatusManager.hide()
        super.onDestroy()
    }

    private fun processCurrentScreen() {
        if (!CommunityJoinSession.isRunning ||
            CommunityJoinSession.stopRequested ||
            CommunityJoinSession.isPaused ||
            finishingCurrentUrl ||
            isIntervalWaiting
        ) return
        val root = rootInActiveWindow ?: return
        val screen = CommunityScreenDetector.detect(root)
        val now = System.currentTimeMillis()
        currentDetectedScreen = screen
        if (screen != CommunityScreenType.UNKNOWN) {
            lastMeaningfulScreenAt = now
        }
        when (flowStage) {
            FlowStage.URL_OPENED -> handleScreenInUrlOpened(root, screen)
            FlowStage.JOIN_BUTTON_TAPPED,
            FlowStage.IN_FLOW -> handleScreenInFlow(root, screen)
            FlowStage.IDLE -> Unit
        }
    }

    private fun handleScreenInUrlOpened(root: AccessibilityNodeInfo, screen: CommunityScreenType) {
        when (screen) {
            CommunityScreenType.AGE_CONFIRM_DIALOG -> handleAgeConfirm(root)
            CommunityScreenType.APPROVAL_PENDING -> handleApprovalPending()
            CommunityScreenType.GENERIC_BLOCKER_DIALOG -> handleGenericBlocker(root)
            CommunityScreenType.ALREADY_IN_COMMUNITY -> handleAlreadyInCommunity()
            CommunityScreenType.COMMUNITY_HOME -> handleCommunityHome(root)
            else -> Unit
        }
    }

    private fun handleScreenInFlow(root: AccessibilityNodeInfo, screen: CommunityScreenType) {
        when (screen) {
            CommunityScreenType.AGE_CONFIRM_DIALOG -> handleAgeConfirm(root)
            CommunityScreenType.APPROVAL_PENDING -> handleApprovalPending()
            CommunityScreenType.GENERIC_BLOCKER_DIALOG -> handleGenericBlocker(root)
            CommunityScreenType.ALREADY_IN_COMMUNITY -> handleAlreadyInCommunity()
            CommunityScreenType.COMMUNITY_HOME -> handleCommunityHome(root)
            CommunityScreenType.ROOM_LIST -> handleRoomList(root)
            CommunityScreenType.QUESTION -> handleQuestion(root)
            CommunityScreenType.PROFILE -> handleProfile(root)
            CommunityScreenType.REMINDER -> handleReminder(root)
            CommunityScreenType.APPLICATION_SENT -> handleApplicationSent(root)
            CommunityScreenType.PASSWORD_ENTRY -> handlePasswordEntry()
            else -> Unit
        }
    }

    private fun handleAgeConfirm(root: AccessibilityNodeInfo) {
        val signature = "age_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1500L) {
            sendProgress("📍 偵測到年齡確認視窗，點「確認並加入」")
            val clicked = clickText(root, listOf("確認並加入")) || tapByRatio(0.74f, 0.6125f)
            if (!clicked) sendProgress("⚠️ 已看到年齡確認視窗，但暫時點不到「確認並加入」")
        }
    }

    private fun handleApprovalPending() {
        val signature = "approval_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1500L) {
            skipCurrentUrlImmediately("⏭ 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆顯示等待核准中，已直接跳下一筆")
        }
    }

    private fun handleGenericBlocker(root: AccessibilityNodeInfo) {
        val signature = "blocker_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1500L) {
            val summary = CommunityScreenDetector.extractDialogSummary(root).ifBlank { "阻塞畫面" }
            sendProgress("⚠️ 偵測到阻塞畫面：$summary\n準備略過此筆社群網址")
            if (!clickText(root, listOf("確定", "關閉"))) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            handler.postDelayed({
                skipCurrentUrlImmediately("⏭ 因阻塞畫面已跳過第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆社群網址")
            }, 400L)
        }
    }

    private fun handleAlreadyInCommunity() {
        val signature = "already_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1500L) {
            sendProgress("ℹ️ 偵測到已進入社群內容頁，視為此筆已加入，直接跳下一筆")
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({
                skipCurrentUrlImmediately("⏭ 此筆社群已在內容頁，已跳下一筆")
            }, 300L)
        }
    }

    private fun handleCommunityHome(root: AccessibilityNodeInfo) {
        val signature = "home_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1800L) {
            sendProgress("📍 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆：點「建立個人檔案並加入」")
            var clicked = clickText(root, listOf("建立個人檔案並加入", "加入"))
                || tapByRatio(0.5f, 0.78f)
                || tapByRatio(0.5f, 0.82f)
                || tapByRatio(0.5f, 0.86f)
                || tapByRatio(0.5f, 0.90f)
                || tapByRatio(0.5f, 0.94f)
            if (!clicked) {
                val bottomButton = CommunityScreenDetector.findLargestClickableBottomButton(root)
                clicked = clickNode(bottomButton)
            }
            if (clicked) {
                flowStage = FlowStage.JOIN_BUTTON_TAPPED
            } else {
                sendProgress("⚠️ 找到首頁，但暫時點不到綠色按鈕")
            }
        }
    }

    private fun handleRoomList(root: AccessibilityNodeInfo) {
        val signature = "room_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1800L) {
            flowStage = FlowStage.IN_FLOW
            val maxCountClicked = clickRoomWithLargestCount(root)
            if (maxCountClicked) {
                roomListRecoveryCount = 0
                sendProgress("✅ 已點選括號數字最大的聊天室")
                return@runThrottled
            }
            val fallbackClicked = clickFallbackFirstRoom(root) || tapByRatio(0.09259259f, 0.22916667f)
            if (fallbackClicked) {
                roomListRecoveryCount = 0
                sendProgress("⚠️ 抓不到有效數字，已改點第一個聊天室")
            } else {
                roomListRecoveryCount++
                sendProgress("⚠️ 聊天室列表暫時點不到，重新掃描中…")
                nudgeUiAndRescan()
            }
        }
    }

    private fun handleQuestion(root: AccessibilityNodeInfo) {
        val signature = "question_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 2500L) {
            flowStage = FlowStage.IN_FLOW
            val question = CommunityScreenDetector.extractLikelyQuestion(root)
            val answer = CommunityAnswerEngine.generateAnswer(question, CommunityJoinSession.getNickname())
            sendProgress("📍 看到回答問題頁，準備自動填答")
            val filled = setFirstEditableText(root, answer)
            if (!filled) {
                sendProgress("⚠️ 問題頁已出現，但找不到可輸入欄位")
                return@runThrottled
            }
            handler.postDelayed({
                val nextClicked = clickText(rootInActiveWindow, listOf("下一步")) || tapByRatio(0.9027778f, 0.08166666f)
                if (!nextClicked) sendProgress("⚠️ 已填好答案，但點不到「下一步」")
            }, 500L)
        }
    }

    private fun handleProfile(root: AccessibilityNodeInfo) {
        val signature = "profile_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 2200L) {
            flowStage = FlowStage.IN_FLOW
            sendProgress("📍 看到社群專屬個人檔案頁")
            setFirstEditableText(root, CommunityJoinSession.getNickname())
            val actionText = CommunityScreenDetector.findProfileActionText(root)
            val clicked = when (actionText) {
                "送出" -> {
                    sendProgress("🟡 有問題流程，右上角是「送出」")
                    clickText(root, listOf("送出")) || tapByRatio(0.9259259f, 0.083333336f)
                }
                "加入" -> {
                    recordValidNoQuestionUrl()
                    sendProgress("🟢 無問題流程，右上角是「加入」")
                    clickText(root, listOf("加入")) || tapByRatio(0.9259259f, 0.083333336f)
                }
                else -> tapByRatio(0.9259259f, 0.083333336f)
            }
            if (!clicked) sendProgress("⚠️ 個人檔案頁已出現，但點不到右上角按鈕")
        }
    }

    private fun handleReminder(root: AccessibilityNodeInfo) {
        val signature = "reminder_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1800L) {
            flowStage = FlowStage.IN_FLOW
            sendProgress("📍 看到社群使用小提醒，點「確定」")
            val clicked = clickText(root, listOf("確定")) || tapByRatio(0.5f, 0.75f)
            if (!clicked) {
                sendProgress("⚠️ 小提醒頁已出現，但點不到「確定」")
                return@runThrottled
            }
            finishingCurrentUrl = true
            clearWatchdog()
            sendProgress("✅ 已點下小提醒的「確定」\n視為完成第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆社群網址\n準備進入等待 ${CommunityJoinSession.getWaitSeconds()} 秒")
            handler.postDelayed({
                finishCurrentUrlAndWait("✅ 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆社群網址已完成，開始等待 ${CommunityJoinSession.getWaitSeconds()} 秒")
            }, 700L)
        }
    }

    private fun handleApplicationSent(root: AccessibilityNodeInfo) {
        val signature = "sent_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1800L) {
            flowStage = FlowStage.IN_FLOW
            sendProgress("📍 看到申請送出完成頁，點「確定」")
            if (!clickText(root, listOf("確定"))) {
                tapByRatio(0.5f, 0.5083333f)
            }
        }
    }

    private fun handlePasswordEntry() {
        val signature = "password_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1500L) {
            if (flowStage == FlowStage.JOIN_BUTTON_TAPPED || flowStage == FlowStage.IN_FLOW) {
                skipCurrentUrlImmediately("🔐 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆需要參加密碼，已直接跳下一筆")
            }
        }
    }

    private fun recordValidNoQuestionUrl() {
        val added = CommunityJoinSession.markCurrentUrlAsValid()
        if (!added) return
        val currentUrl = CommunityJoinSession.currentUrl().orEmpty()
        val fileName = CommunityJoinSession.getValidOutputFileName()
        val ok = ValidCommunityUrlExporter.rewriteToDownloads(this, fileName, CommunityJoinSession.getValidCommunityUrls())
        if (ok) {
            sendProgress("✅ 已收錄有效社群網址（無問題流程）\n$currentUrl\n已輸出：$fileName")
        } else {
            sendProgress("⚠️ 已判定為有效社群網址，但寫入下載資料夾失敗\n$currentUrl")
        }
    }

    private fun getLineDisplayName(linePackage: String?): String {
        if (linePackage.isNullOrBlank()) return "未知 LINE"
        val names = resources.getStringArray(R.array.line_names)
        val packages = resources.getStringArray(R.array.line_packages)
        for (i in packages.indices) {
            if (packages[i] == linePackage) {
                return names.getOrNull(i) ?: linePackage
            }
        }
        return linePackage
    }

    private fun getCurrentUrlForDisplay(): String {
        return CommunityJoinSession.currentUrl().orEmpty()
    }

    private fun openCurrentUrlImmediately() {
        if (!CommunityJoinSession.isRunning || CommunityJoinSession.stopRequested || CommunityJoinSession.isPaused) return
        clearCountdown()
        clearWatchdog()
        resetIntervalState()
        val url = CommunityJoinSession.currentUrl()
        val linePackage = CommunityJoinSession.currentLinePackage()
        if (!url.isNullOrBlank() && !linePackage.isNullOrBlank()) {
            try {
                flowStage = FlowStage.URL_OPENED
                currentDetectedScreen = CommunityScreenType.UNKNOWN
                currentUrlOpenedAt = System.currentTimeMillis()
                lastMeaningfulScreenAt = currentUrlOpenedAt
                openStageRecoveryCount = 0
                roomListRecoveryCount = 0
                finishingCurrentUrl = false
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                    setPackage(linePackage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                val lineName = getLineDisplayName(linePackage)
                val currentNumber = CommunityJoinSession.getCurrentIndex() + 1
                sendProgress("🚀 立即開啟${lineName}的第 $currentNumber 筆社群網址\n$url")
                scheduleWatchdog()
                return
            } catch (e: Exception) {
                finishCurrentUrlAndWait("❌ 無法開啟第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆：${e.message}")
                return
            }
        }
        completeAll("✅ 全部社群網址已處理完成")
    }

    private fun reopenCurrentUrlOnce() {
        val url = CommunityJoinSession.currentUrl() ?: return
        val linePackage = CommunityJoinSession.currentLinePackage() ?: return
        if (!CommunityJoinSession.isRunning || CommunityJoinSession.stopRequested || CommunityJoinSession.isPaused || isIntervalWaiting) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                setPackage(linePackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            currentUrlOpenedAt = System.currentTimeMillis()
            sendProgress("🔁 重新開啟目前社群網址，嘗試重新抓取畫面\n$url")
        } catch (_: Exception) {
        }
    }

    private fun handleWatchdog() {
        if (!CommunityJoinSession.isRunning ||
            CommunityJoinSession.stopRequested ||
            CommunityJoinSession.isPaused ||
            finishingCurrentUrl ||
            isIntervalWaiting
        ) return
        val now = System.currentTimeMillis()
        when (flowStage) {
            FlowStage.URL_OPENED -> {
                val elapsed = now - currentUrlOpenedAt
                when {
                    elapsed > 4000 && openStageRecoveryCount == 0 -> {
                        openStageRecoveryCount = 1
                        sendProgress("🔄 正在重新掃描社群首頁畫面…")
                        nudgeUiAndRescan()
                    }
                    elapsed > 8000 && openStageRecoveryCount == 1 -> {
                        openStageRecoveryCount = 2
                        reopenCurrentUrlOnce()
                    }
                    elapsed > 15000 -> {
                        skipCurrentUrlImmediately("⏭ 首頁辨識逾時，已跳下一筆社群網址")
                    }
                }
            }
            FlowStage.JOIN_BUTTON_TAPPED, FlowStage.IN_FLOW -> {
                val idle = now - lastMeaningfulScreenAt
                when {
                    currentDetectedScreen == CommunityScreenType.ROOM_LIST && roomListRecoveryCount < 3 && idle > 2500 -> {
                        roomListRecoveryCount++
                        sendProgress("🔄 聊天室列表重新抓取中…")
                        nudgeUiAndRescan()
                    }
                    idle > 12000 -> {
                        sendProgress("🔄 畫面停留過久，重新掃描中…")
                        nudgeUiAndRescan()
                        lastMeaningfulScreenAt = now
                    }
                    idle > 22000 -> {
                        skipCurrentUrlImmediately("⏭ 此筆社群畫面停留過久，已跳下一筆")
                    }
                }
            }
            FlowStage.IDLE -> Unit
        }
    }

    private fun scheduleWatchdog() {
        clearWatchdog()
        val runnable = object : Runnable {
            override fun run() {
                handleWatchdog()
                if (CommunityJoinSession.isRunning && !CommunityJoinSession.stopRequested && !CommunityJoinSession.isPaused) {
                    if (!isIntervalWaiting) {
                        handler.postDelayed(this, 1000L)
                    }
                }
            }
        }
        watchdogRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    private fun clearWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun nudgeUiAndRescan() {
        microSwipe(0.5f, 0.49166667f, 0.5f, 0.46666667f)
        handler.postDelayed({ processCurrentScreen() }, 700L)
    }

    private fun microSwipe(startXRatio: Float, startYRatio: Float, endXRatio: Float, endYRatio: Float): Boolean {
        val dm = resources.displayMetrics
        val startX = dm.widthPixels * startXRatio
        val startY = dm.heightPixels * startYRatio
        val endX = dm.widthPixels * endXRatio
        val endY = dm.heightPixels * endYRatio
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 120L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun skipCurrentUrlImmediately(message: String) {
        sendProgress(message)
        val hasNext = CommunityJoinSession.moveNextTarget()
        if (!hasNext) {
            completeAll("✅ 全部社群網址已處理完成")
            return
        }
        resetFlowState()
        resetIntervalState()
        handler.postDelayed({ openCurrentUrlImmediately() }, 400L)
    }

    private fun finishCurrentUrlAndWait(message: String) {
        sendProgress(message)
        val hasNext = CommunityJoinSession.moveNextTarget()
        if (!hasNext) {
            completeAll("✅ 全部社群網址已處理完成")
            return
        }
        resetFlowState()
        val seconds = CommunityJoinSession.getWaitSeconds()
        if (seconds <= 0) {
            resetIntervalState()
            openCurrentUrlImmediately()
            return
        }
        startIntervalCountdown(seconds)
    }

    private fun startIntervalCountdown(seconds: Int) {
        startIntervalCountdownFromMillis(seconds * 1000L)
    }

    private fun startIntervalCountdownFromMillis(totalMillis: Long) {
        clearCountdown()
        clearWatchdog()
        if (!CommunityJoinSession.isRunning || CommunityJoinSession.stopRequested) return
        isIntervalWaiting = true
        intervalRemainingMs = totalMillis.coerceAtLeast(0L)
        intervalEndRealtime = SystemClock.elapsedRealtime() + intervalRemainingMs
        val runnable = object : Runnable {
            override fun run() {
                if (!CommunityJoinSession.isRunning || CommunityJoinSession.stopRequested) return
                if (!CommunityJoinSession.isPaused) {
                    val remainingMs = (intervalEndRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    val remainingSeconds = ((remainingMs + 999) / 1000).toInt()
                    if (remainingMs <= 0) {
                        clearCountdown()
                        resetIntervalState()
                        openCurrentUrlImmediately()
                        return
                    }
                    val lineName = getLineDisplayName(CommunityJoinSession.currentLinePackage())
                    val nextNumber = CommunityJoinSession.getCurrentIndex() + 1
                    val nextUrl = getCurrentUrlForDisplay()
                    sendProgress("⏳ $lineName 間隔倒數 $remainingSeconds / ${CommunityJoinSession.getWaitSeconds()} 秒\n下一筆：第 $nextNumber 筆社群網址\n$nextUrl")
                    handler.postDelayed(this, 250L)
                } else {
                    intervalRemainingMs = (intervalEndRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                }
            }
        }
        countdownRunnable = runnable
        handler.post(runnable)
    }

    private fun resetFlowState() {
        flowStage = FlowStage.IDLE
        currentDetectedScreen = CommunityScreenType.UNKNOWN
        currentUrlOpenedAt = 0L
        lastMeaningfulScreenAt = 0L
        openStageRecoveryCount = 0
        roomListRecoveryCount = 0
        finishingCurrentUrl = false
        clearWatchdog()
    }

    private fun resetIntervalState() {
        isIntervalWaiting = false
        intervalEndRealtime = 0L
        intervalRemainingMs = 0L
    }

    private fun clearCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun completeAll(message: String) {
        clearCountdown()
        resetFlowState()
        resetIntervalState()
        CommunityJoinSession.finishAll()
        handler.removeCallbacksAndMessages(null)
        sendProgress(message)
        handler.postDelayed({ OverlayStatusManager.hide() }, 1800L)
    }

    private fun clickText(root: AccessibilityNodeInfo?, targets: List<String>): Boolean {
        val node = CommunityScreenDetector.findClickableNodeByTexts(root, targets) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun clickRoomWithLargestCount(root: AccessibilityNodeInfo?): Boolean {
        val target = CommunityScreenDetector.findRoomClickableWithLargestCount(root) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickFallbackFirstRoom(root: AccessibilityNodeInfo?): Boolean {
        val target = CommunityScreenDetector.findFallbackFirstRoomClickable(root) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun setFirstEditableText(root: AccessibilityNodeInfo?, text: String): Boolean {
        val node = CommunityScreenDetector.findFirstEditable(root) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun tapByRatio(xRatio: Float, yRatio: Float): Boolean {
        val dm: DisplayMetrics = resources.displayMetrics
        val x = dm.widthPixels * xRatio
        val y = dm.heightPixels * yRatio
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun runThrottled(signature: String, minIntervalMs: Long, block: () -> Unit) {
        val now = System.currentTimeMillis()
        if (lastActionSignature == signature && now - lastActionAt < minIntervalMs) return
        lastActionSignature = signature
        lastActionAt = now
        block()
    }

    private fun sendProgress(msg: String) {
        Log.d("CommunityService", msg)
        OverlayStatusManager.update(this, msg)
        val intent = Intent(CommunityJoinSession.ACTION_PROGRESS)
        intent.setPackage(packageName)
        intent.putExtra(CommunityJoinSession.EXTRA_MSG, msg)
        sendBroadcast(intent)
    }

    private fun registerInternalReceivers() {
        if (receiversRegistered) return
        val startFilter = IntentFilter(CommunityJoinSession.ACTION_START)
        val stopFilter = IntentFilter(CommunityJoinSession.ACTION_STOP)
        val pauseFilter = IntentFilter(CommunityJoinSession.ACTION_PAUSE)
        val resumeFilter = IntentFilter(CommunityJoinSession.ACTION_RESUME)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(startReceiver, startFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(stopReceiver, stopFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(pauseReceiver, pauseFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(resumeReceiver, resumeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(startReceiver, startFilter)
            registerReceiver(stopReceiver, stopFilter)
            registerReceiver(pauseReceiver, pauseFilter)
            registerReceiver(resumeReceiver, resumeFilter)
        }
        receiversRegistered = true
    }
}
