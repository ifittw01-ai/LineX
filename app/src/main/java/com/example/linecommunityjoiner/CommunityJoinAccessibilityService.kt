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
    private var processingUrlTransition = false
    private var joinCompletedAwaitingPost = false
    private var chatComposeInProgress = false
    private var disableNodeSendForCurrentUrl = false
    private var mentionCorruptionCountForCurrentUrl = 0
    private var postJoinRecoveryCount = 0
    private var isIntervalWaiting = false
    private var isScheduleWaiting = false
    private var intervalEndRealtime = 0L
    private var intervalRemainingMs = 0L
    private var scheduleEndRealtime = 0L
    private var scheduleRemainingMs = 0L
    private var scheduleTotalSeconds = 0
    private var countdownRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private var scheduleRunnable: Runnable? = null
    private var stageToken = 0L
    private var urlOpenedGuardUntil = 0L

    private val startReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (CommunityJoinSession.isRunning) {
                handler.removeCallbacksAndMessages(null)
                clearCountdown()
                clearWatchdog()
                clearScheduleCountdown()
                invalidateStage()
                resetFlowState()
                resetIntervalState()
                resetScheduleState()
                resetThrottle()
                resetCurrentUrlSendState()
                sendProgress("▶️ 開始執行社群加入流程")
                if (CommunityJoinSession.hasScheduledStart()) {
                    startScheduleCountdown()
                } else {
                    openCurrentUrlImmediately()
                }
            }
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            clearCountdown()
            clearWatchdog()
            clearScheduleCountdown()
            handler.removeCallbacksAndMessages(null)
            resetFlowState()
            resetIntervalState()
            resetScheduleState()
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
                if (isScheduleWaiting) {
                    scheduleRemainingMs = (scheduleEndRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                }
                CommunityJoinSession.isPaused = true
                clearCountdown()
                clearWatchdog()
                clearScheduleCountdown()
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
            if (isScheduleWaiting) {
                startScheduleCountdownFromMillis(scheduleRemainingMs)
            } else if (isIntervalWaiting) {
                startIntervalCountdownFromMillis(intervalRemainingMs, CommunityJoinSession.getCurrentCycleWaitSeconds())
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
            processingUrlTransition ||
            chatComposeInProgress ||
            isIntervalWaiting ||
            isScheduleWaiting ||
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
        clearScheduleCountdown()
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
        if (screen == CommunityScreenType.ACCOUNT_SUSPENDED) {
            handleAccountSuspended(root)
            return
        }
        when (flowStage) {
            FlowStage.URL_OPENED -> {
                when (screen) {
                    CommunityScreenType.AGE_CONFIRM_DIALOG -> handleAgeConfirm(root)
                    CommunityScreenType.APPROVAL_PENDING -> handleApprovalPending()
                    CommunityScreenType.GENERIC_BLOCKER_DIALOG -> handleGenericBlocker(root)
                    CommunityScreenType.COMMUNITY_HOME -> handleCommunityHome(root)
                    CommunityScreenType.PASSWORD_ENTRY -> handlePasswordEntry()
                    CommunityScreenType.ALREADY_IN_COMMUNITY,
                    CommunityScreenType.CHAT_ROOM -> {
                        if (now >= urlOpenedGuardUntil) {
                            handleAlreadyInCommunity()
                        }
                    }
                    else -> Unit
                }
            }
            FlowStage.JOIN_BUTTON_TAPPED,
            FlowStage.IN_FLOW -> {
                if (joinCompletedAwaitingPost) {
                    when (screen) {
                        CommunityScreenType.ALREADY_IN_COMMUNITY -> {
                            if (CommunityJoinSession.hasMessageTemplate()) {
                                handleJoinedContent(root)
                            } else {
                                finishCurrentUrlAndWait("✅ 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆社群網址已完成")
                            }
                        }
                        CommunityScreenType.CHAT_ROOM -> {
                            if (CommunityJoinSession.hasMessageTemplate()) {
                                handleChatRoom(root)
                            } else {
                                finishCurrentUrlAndWait("✅ 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆社群網址已完成")
                            }
                        }
                        else -> Unit
                    }
                    return
                }
                when (screen) {
                    CommunityScreenType.AGE_CONFIRM_DIALOG -> handleAgeConfirm(root)
                    CommunityScreenType.APPROVAL_PENDING -> handleApprovalPending()
                    CommunityScreenType.GENERIC_BLOCKER_DIALOG -> handleGenericBlocker(root)
                    CommunityScreenType.COMMUNITY_HOME -> handleCommunityHome(root)
                    CommunityScreenType.PASSWORD_ENTRY -> handlePasswordEntry()
                    CommunityScreenType.ALREADY_IN_COMMUNITY -> handleAlreadyInCommunity()
                    CommunityScreenType.CHAT_ROOM -> handleAlreadyInCommunity()
                    CommunityScreenType.ROOM_LIST -> handleRoomList(root)
                    CommunityScreenType.QUESTION -> handleQuestion(root)
                    CommunityScreenType.PROFILE -> handleProfile(root)
                    CommunityScreenType.REMINDER -> handleReminder(root)
                    CommunityScreenType.APPLICATION_SENT -> handleApplicationSent(root)
                    else -> Unit
                }
            }
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

    private fun handleAccountSuspended(root: AccessibilityNodeInfo) {
        runThrottled("account_suspended", 2000L) {
            val summary = CommunityScreenDetector.extractDialogSummary(root).ifBlank {
                "因違反服務條款，您的帳號已被停用"
            }
            val joined = CommunityJoinSession.getCompletedJoinCount()
            val currentLineName = getLineDisplayName(CommunityJoinSession.currentLinePackage())
            val currentUrlNum = CommunityJoinSession.getCurrentIndex() + 1
            val totalUrls = CommunityJoinSession.getUrls().size
            stopAllWithMessage(
                "⛔ 已停止\n" +
                    "LINE 帳號：$currentLineName\n" +
                    "已完成加群：$joined 個\n" +
                    "當前進度：第 $currentUrlNum / $totalUrls 筆社群網址\n" +
                    "停止原因：$summary"
            )
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
            sendProgress("ℹ️ 偵測到此筆已直接進入社群內容，視為非新加入流程，直接跳下一筆")
            performGlobalAction(GLOBAL_ACTION_BACK)
            val token = currentStageToken()
            postStageDelay(300L, token) {
                skipCurrentUrlImmediately("⏭ 此筆社群已在內容頁，已跳下一筆")
            }
        }
    }

    private fun handleCommunityHome(root: AccessibilityNodeInfo) {
        val signature = "home_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1800L) {
            sendProgress("📍 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆：點「建立個人檔案並加入」")
            val clicked = clickText(root, listOf("建立個人檔案並加入")) ||
                tapByRatio(0.5f, 0.84166664f)
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
            sendProgress("📍 看到聊天室列表，正在選擇人數最多的聊天室...")
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
            val token = currentStageToken()
            postStageDelay(500L, token) {
                val nextClicked = clickText(rootInActiveWindow, listOf("下一步")) || tapByRatio(0.9027778f, 0.08166666f)
                if (!nextClicked) sendProgress("⚠️ 已填好答案，但點不到「下一步」")
            }
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
                    CommunityJoinSession.setCurrentUrlCanPostAfterJoin(true)
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
            CommunityJoinSession.setPostReminderConfirmedAt(System.currentTimeMillis())
            val joinedCount = CommunityJoinSession.markJoinCompletedOnce()
            val token = currentStageToken()
            if (CommunityJoinSession.hasMessageTemplate() && CommunityJoinSession.getCurrentUrlCanPostAfterJoin()) {
                joinCompletedAwaitingPost = true
                chatComposeInProgress = false
                postJoinRecoveryCount = 0
                sendProgress("✅ 已完成加入（已加 $joinedCount 群），接下來準備進聊天室自動貼文案")
                postStageDelay(500L, token) { processCurrentScreen() }
                return@runThrottled
            }
            sendProgress("✅ 已點下小提醒的「確定」\n已加 $joinedCount 群\n視為完成第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆社群網址")
            postStageDelay(700L, token) {
                finishCurrentUrlAndWait("✅ 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆社群網址已完成")
            }
        }
    }

    private fun handleApplicationSent(root: AccessibilityNodeInfo) {
        val signature = "sent_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1800L) {
            flowStage = FlowStage.IN_FLOW
            sendProgress("📍 看到申請送出完成頁，點「確定」")
            val clicked = clickText(root, listOf("確定")) || tapByRatio(0.5f, 0.5083333f)
            if (clicked) {
                val token = currentStageToken()
                postStageDelay(600L, token) {
                    finishCurrentUrlAndWait("✅ 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆已送出申請，開始等待下一輪")
                }
            }
        }
    }

    private fun handlePasswordEntry() {
        val signature = "password_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1500L) {
            if (flowStage != FlowStage.IDLE) {
                skipCurrentUrlImmediately("🔐 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆需要參加密碼，已直接跳下一筆")
            }
        }
    }

    private data class SendUiSnapshot(
        val hasEditable: Boolean,
        val inputText: String,
        val inputLength: Int,
        val sendText: String,
        val sendDesc: String,
        val sendViewId: String
    )

    private fun handleJoinedContent(root: AccessibilityNodeInfo) {
        val signature = "joined_content_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 1800L) {
            postJoinRecoveryCount = 0
            sendProgress("📍 已完成加入，準備切到聊天頁發送文案")
            val chatTab = CommunityScreenDetector.findChatTabClickable(root)
            val clicked = (chatTab != null && chatTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ||
                clickText(root, listOf("聊天")) ||
                tapByRatio(0.25925925f, 0.93333334f)
            if (!clicked) {
                sendProgress("⚠️ 已進到社群內容頁，但暫時點不到「聊天」")
                nudgeUiAndRescan()
            }
        }
    }

    private fun handleChatRoom(root: AccessibilityNodeInfo) {
        val signature = "chat_room_${CommunityJoinSession.getCurrentLineIndex()}_${CommunityJoinSession.getCurrentIndex()}"
        runThrottled(signature, 2200L) {
            if (chatComposeInProgress) return@runThrottled
            val template = CommunityJoinSession.getMessageTemplate()
            if (template.isBlank()) {
                joinCompletedAwaitingPost = false
                finishCurrentUrlAndWait("✅ 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆社群網址已完成")
                return@runThrottled
            }
            chatComposeInProgress = true
            postJoinRecoveryCount = 0
            lastMeaningfulScreenAt = System.currentTimeMillis()
            sendProgress("📍 已進入聊天室，準備貼上文案")
            val focused = focusChatInput(root)
            if (!focused) {
                chatComposeInProgress = false
                sendProgress("⚠️ 已進聊天室，但找不到底部輸入框")
                nudgeUiAndRescan()
                return@runThrottled
            }
            val token = currentStageToken()
            postStageDelay(500L, token) {
                val filled = setChatEditableText(rootInActiveWindow, template)
                if (!filled) {
                    chatComposeInProgress = false
                    sendProgress("⚠️ 已進聊天室，但文案貼上失敗")
                    nudgeUiAndRescan()
                    return@postStageDelay
                }
                sendProgress("✅ 文案已貼上，等待送出按鈕穩定")
                lastMeaningfulScreenAt = System.currentTimeMillis()
                postStageDelay(600L, token) {
                    if (canWork(token)) {
                        sendProgress("✅ 文案已貼上，準備送出")
                        attemptSendMessage(token, 1, template)
                    }
                }
            }
        }
    }

    private fun attemptSendMessage(token: Long, attemptCount: Int, originalTemplate: String) {
        if (!canWork(token)) return
        val latestRoot = rootInActiveWindow
        val beforeSnapshot = captureSendUiSnapshot(latestRoot)
        if (attemptCount > 1) {
            inferAlreadySentBeforeRetry(beforeSnapshot, originalTemplate)?.let {
                sendProgress("✅ 偵測到上一輪其實已送出（$it）")
                completeCurrentChatPost(token)
                return
            }
        }
        if (beforeSnapshot.hasEditable && beforeSnapshot.inputLength == 0) {
            sendProgress("✅ 偵測到文案已發送（輸入框已清空）")
            completeCurrentChatPost(token)
            return
        }
        sendProgress("📍 第 $attemptCount 次嘗試點擊發送按鈕...")
        lastMeaningfulScreenAt = System.currentTimeMillis()
        val sendMethod = if (disableNodeSendForCurrentUrl) {
            sendProgress("⚠️ 本筆已停用節點送出，改用紙飛機座標")
            if (tapChatSendFallback(attemptCount)) "coord" else null
        } else if (clickChatSend(latestRoot)) {
            "node"
        } else {
            sendProgress("⚠️ 節點送出失敗，改用紙飛機座標")
            if (tapChatSendFallback(attemptCount)) "coord" else null
        }
        if (sendMethod == null) {
            retryOrRescan(token, attemptCount, 4, originalTemplate)
            return
        }
        postStageDelay(1500L, token) {
            inspectComposerAfterSend(token, attemptCount, 4, originalTemplate, beforeSnapshot, false, false)
        }
    }

    private fun inspectComposerAfterSend(
        token: Long,
        attemptCount: Int,
        maxAttempts: Int,
        originalTemplate: String,
        beforeSnapshot: SendUiSnapshot,
        backAttempted: Boolean,
        secondCheck: Boolean
    ) {
        if (!canWork(token)) return
        val currentRoot = rootInActiveWindow
        val afterSnapshot = captureSendUiSnapshot(currentRoot)
        val successReason = inferSendSuccessReason(beforeSnapshot, afterSnapshot)
        if (successReason != null) {
            sendProgress("✅ 文案已送出（$successReason）")
            completeCurrentChatPost(token)
            return
        }
        if (!afterSnapshot.hasEditable) {
            if (!backAttempted) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                postStageDelay(500L, token) {
                    inspectComposerAfterSend(token, attemptCount, maxAttempts, originalTemplate, beforeSnapshot, true, secondCheck)
                }
                return
            }
            retryOrRescan(token, attemptCount, maxAttempts, originalTemplate)
            return
        }
        if (looksLikeMentionCorruption(afterSnapshot.inputText, originalTemplate)) {
            mentionCorruptionCountForCurrentUrl++
            disableNodeSendForCurrentUrl = true
            sendProgress("⚠️ 偵測到輸入框多出 @，表示誤點頭像/姓名\n本筆已停用節點送出（第 $mentionCorruptionCountForCurrentUrl 次）")
            restoreChatTemplateAsync(token, originalTemplate) { restored ->
                if (restored) {
                    postStageDelay(500L, token) {
                        attemptSendMessage(token, attemptCount + 1, originalTemplate)
                    }
                } else {
                    retryOrRescan(token, attemptCount, maxAttempts, originalTemplate)
                }
            }
            return
        }
        if (!secondCheck) {
            postStageDelay(500L, token) {
                inspectComposerAfterSend(token, attemptCount, maxAttempts, originalTemplate, beforeSnapshot, backAttempted, true)
            }
            return
        }
        retryOrRescan(token, attemptCount, maxAttempts, originalTemplate)
    }

    private fun retryOrRescan(token: Long, attemptCount: Int, maxAttempts: Int, originalTemplate: String) {
        if (attemptCount < maxAttempts) {
            postStageDelay(600L, token) {
                attemptSendMessage(token, attemptCount + 1, originalTemplate)
            }
        } else {
            chatComposeInProgress = false
            joinCompletedAwaitingPost = false
            finishCurrentUrlAndWait("⚠️ 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆已完成加入，但文案未成功送出")
        }
    }

    private fun completeCurrentChatPost(token: Long) {
        if (!canWork(token)) return
        joinCompletedAwaitingPost = false
        chatComposeInProgress = false
        finishCurrentUrlAndWait("✅ 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆社群網址已完成並送出文案")
    }

    private fun captureSendUiSnapshot(root: AccessibilityNodeInfo?): SendUiSnapshot {
        val input = CommunityScreenDetector.findLikelyChatEditable(root)
            ?: CommunityScreenDetector.findFirstEditable(root)
        val inputText = input?.text?.toString().orEmpty()
        val inputLength = inputText.length
        val sendNode = CommunityScreenDetector.findChatSendClickable(root)
        val sendText = sendNode?.text?.toString().orEmpty()
        val sendDesc = sendNode?.contentDescription?.toString().orEmpty()
        val sendViewId = sendNode?.viewIdResourceName.orEmpty()
        return SendUiSnapshot(input != null, inputText, inputLength, sendText, sendDesc, sendViewId)
    }

    private fun inferAlreadySentBeforeRetry(snapshot: SendUiSnapshot, originalTemplate: String): String? {
        return if (snapshot.hasEditable && snapshot.inputLength == 0) {
            "輸入框已清空"
        } else {
            null
        }
    }

    private fun inferSendSuccessReason(before: SendUiSnapshot, after: SendUiSnapshot): String? {
        if (before.hasEditable && after.hasEditable && after.inputLength == 0 && before.inputLength > 0) {
            return "輸入框已清空"
        }
        return null
    }

    private fun focusChatInput(root: AccessibilityNodeInfo?): Boolean {
        val node = CommunityScreenDetector.findLikelyChatEditable(root)
            ?: CommunityScreenDetector.findFirstEditable(root)
            ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun setChatEditableText(root: AccessibilityNodeInfo?, text: String): Boolean {
        val node = CommunityScreenDetector.findLikelyChatEditable(root)
            ?: CommunityScreenDetector.findFirstEditable(root)
            ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun clickChatSend(root: AccessibilityNodeInfo?): Boolean {
        val sendNode = CommunityScreenDetector.findChatSendClickable(root) ?: return false
        return sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun tapChatSendFallback(attemptCount: Int): Boolean {
        return when (attemptCount) {
            1 -> tapByDesignCoordWithLog("第1次：下方主座標", 1010f, 2144f, 1080f, 2340f)
            2 -> tapByDesignCoordWithLog("第2次：鍵盤彈出標準座標", 1010f, 1280f, 1080f, 2340f)
            3 -> tapByDesignCoordWithLog("第3次：鍵盤彈出右下偏移", 1020f, 1290f, 1080f, 2340f)
            4 -> tapByDesignCoordWithLog("第4次：鍵盤彈出左上偏移", 1000f, 1270f, 1080f, 2340f)
            else -> tapByDesignCoordWithLog("其他嘗試：下方主座標", 1010f, 2144f, 1080f, 2340f)
        }
    }

    private fun tapByDesignCoordWithLog(label: String, designX: Float, designY: Float, designWidth: Float, designHeight: Float): Boolean {
        val xRatio = designX / designWidth
        val yRatio = designY / designHeight
        val dm = resources.displayMetrics
        val actualX = (dm.widthPixels * xRatio).toInt()
        val actualY = (dm.heightPixels * yRatio).toInt()
        val ok = tapByRatio(xRatio, yRatio)
        Log.d("CommunityService", "$label -> 設計座標=($designX,$designY) 實際座標=($actualX,$actualY) gestureDispatch=$ok")
        return ok
    }

    private fun restoreChatTemplateAsync(token: Long, template: String, onDone: (Boolean) -> Unit) {
        if (!canWork(token)) {
            onDone(false)
            return
        }
        val focused = focusChatInput(rootInActiveWindow)
        if (!focused) {
            onDone(false)
            return
        }
        postStageDelay(350L, token) {
            val ok = setChatEditableText(rootInActiveWindow, template)
            onDone(ok)
        }
    }

    private fun looksLikeMentionCorruption(currentText: String, originalTemplate: String): Boolean {
        val current = normalizeComposerText(currentText)
        val original = normalizeComposerText(originalTemplate)
        if (current.isBlank() || current == original) return false
        val originalHasAt = original.contains("@")
        val currentHasAt = current.contains("@")
        return if (originalHasAt || !currentHasAt) {
            current.startsWith(original) && current.length > original.length + 2
        } else {
            true
        }
    }

    private fun normalizeComposerText(text: String): String {
        return text.replace("\r\n", "\n").trim()
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
        clearScheduleCountdown()
        resetIntervalState()
        resetScheduleState()
        invalidateStage()
        resetThrottle()
        resetCurrentUrlSendState()
        val url = CommunityJoinSession.currentUrl()
        val linePackage = CommunityJoinSession.currentLinePackage()
        if (!url.isNullOrBlank() && !linePackage.isNullOrBlank()) {
            try {
                flowStage = FlowStage.URL_OPENED
                currentDetectedScreen = CommunityScreenType.UNKNOWN
                currentUrlOpenedAt = System.currentTimeMillis()
                urlOpenedGuardUntil = currentUrlOpenedAt + 2500
                lastMeaningfulScreenAt = currentUrlOpenedAt
                openStageRecoveryCount = 0
                roomListRecoveryCount = 0
                finishingCurrentUrl = false
                processingUrlTransition = false
                joinCompletedAwaitingPost = false
                chatComposeInProgress = false
                CommunityJoinSession.setCurrentUrlCanPostAfterJoin(false)
                CommunityJoinSession.setCurrentUrlJoinRecorded(false)
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
        if (!CommunityJoinSession.isRunning || CommunityJoinSession.stopRequested || CommunityJoinSession.isPaused || isIntervalWaiting || isScheduleWaiting) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                setPackage(linePackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            currentUrlOpenedAt = System.currentTimeMillis()
            urlOpenedGuardUntil = currentUrlOpenedAt + 2500
            sendProgress("🔁 重新開啟目前社群網址，嘗試重新抓取畫面\n$url")
        } catch (_: Exception) {
        }
    }

    private fun handleWatchdog() {
        if (!CommunityJoinSession.isRunning ||
            CommunityJoinSession.stopRequested ||
            CommunityJoinSession.isPaused ||
            finishingCurrentUrl ||
            processingUrlTransition ||
            isIntervalWaiting ||
            isScheduleWaiting
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
                    joinCompletedAwaitingPost && !chatComposeInProgress && idle > 3000 -> {
                        when {
                            postJoinRecoveryCount < 3 -> {
                                postJoinRecoveryCount++
                                sendProgress("🔄 已完成加入，重新嘗試抓聊天室畫面（$postJoinRecoveryCount/3）…")
                                lastMeaningfulScreenAt = now
                                nudgeUiAndRescan()
                            }
                            postJoinRecoveryCount == 3 -> {
                                postJoinRecoveryCount++
                                lastMeaningfulScreenAt = now
                                recoverJoinedChatFlow()
                            }
                            else -> {
                                joinCompletedAwaitingPost = false
                                chatComposeInProgress = false
                                sendProgress("⚠️ 聊天室恢復失敗，改為直接進入下一筆等待")
                                finishCurrentUrlAndWait("⚠️ 第 ${CommunityJoinSession.getCurrentIndex() + 1} 筆已完成加入，但文案未成功送出")
                            }
                        }
                    }
                    idle > 22000 -> {
                        skipCurrentUrlImmediately("⏭ 此筆社群畫面停留過久，已跳下一筆")
                    }
                    idle > 12000 -> {
                        sendProgress("🔄 畫面停留過久，重新掃描中…")
                        nudgeUiAndRescan()
                        lastMeaningfulScreenAt = now
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
                    if (!isIntervalWaiting && !isScheduleWaiting) {
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
        resetScheduleState()
        resetCurrentUrlSendState()
        handler.postDelayed({ openCurrentUrlImmediately() }, 400L)
    }

    private fun recoverJoinedChatFlow() {
        val token = currentStageToken()
        sendProgress("🔄 重新掃描仍未恢復，嘗試返回後重進聊天頁…")
        performGlobalAction(GLOBAL_ACTION_BACK)
        postStageDelay(600L, token) {
            val root = rootInActiveWindow
            val chatTab = CommunityScreenDetector.findChatTabClickable(root)
            val clicked = (chatTab != null && chatTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ||
                clickText(root, listOf("聊天")) ||
                tapByRatio(0.25925925f, 0.93333334f)
            if (clicked) {
                sendProgress("✅ 已嘗試重新進入聊天頁")
                postStageDelay(700L, token) { processCurrentScreen() }
            } else {
                sendProgress("⚠️ 返回後暫時點不到聊天頁，改為重新掃描")
                nudgeUiAndRescan()
            }
        }
    }

    private fun finishCurrentUrlAndWait(message: String) {
        if (processingUrlTransition) return
        processingUrlTransition = true
        finishingCurrentUrl = true
        invalidateStage()
        clearWatchdog()
        sendProgress(message)
        val hasNext = CommunityJoinSession.moveNextTarget()
        if (!hasNext) {
            processingUrlTransition = false
            completeAll("✅ 全部社群網址已處理完成")
            return
        }
        flowStage = FlowStage.IDLE
        currentDetectedScreen = CommunityScreenType.UNKNOWN
        currentUrlOpenedAt = 0L
        urlOpenedGuardUntil = 0L
        lastMeaningfulScreenAt = 0L
        openStageRecoveryCount = 0
        roomListRecoveryCount = 0
        joinCompletedAwaitingPost = false
        chatComposeInProgress = false
        resetThrottle()
        resetCurrentUrlSendState()
        val nextWaitSeconds = CommunityJoinSession.nextWaitSeconds()
        if (nextWaitSeconds <= 0) {
            resetIntervalState()
            processingUrlTransition = false
            openCurrentUrlImmediately()
            return
        }
        processingUrlTransition = false
        startIntervalCountdown(nextWaitSeconds)
    }

    private fun startIntervalCountdown(seconds: Int) {
        startIntervalCountdownFromMillis(seconds * 1000L, seconds)
    }

    private fun startIntervalCountdownFromMillis(totalMillis: Long, totalSeconds: Int) {
        clearCountdown()
        clearWatchdog()
        clearScheduleCountdown()
        if (!CommunityJoinSession.isRunning || CommunityJoinSession.stopRequested) return
        isIntervalWaiting = true
        intervalRemainingMs = totalMillis.coerceAtLeast(0L)
        intervalEndRealtime = SystemClock.elapsedRealtime() + intervalRemainingMs
        CommunityJoinSession.setCurrentCycleWaitSeconds(maxOf(totalSeconds, 1))
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
                    val total = CommunityJoinSession.getCurrentCycleWaitSeconds()
                    sendProgress("⏳ $lineName 間隔倒數 $remainingSeconds / $total 秒\n下一筆：第 $nextNumber 筆社群網址\n$nextUrl")
                    handler.postDelayed(this, 1000L)
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
        urlOpenedGuardUntil = 0L
        lastMeaningfulScreenAt = 0L
        openStageRecoveryCount = 0
        roomListRecoveryCount = 0
        finishingCurrentUrl = false
        processingUrlTransition = false
        joinCompletedAwaitingPost = false
        chatComposeInProgress = false
        clearWatchdog()
    }

    private fun resetIntervalState() {
        isIntervalWaiting = false
        intervalEndRealtime = 0L
        intervalRemainingMs = 0L
    }

    private fun startScheduleCountdown() {
        val delayMs = CommunityJoinSession.computeDelayMillisUntilScheduledStart()
        startScheduleCountdownFromMillis(delayMs)
    }

    private fun startScheduleCountdownFromMillis(totalMillis: Long) {
        clearScheduleCountdown()
        if (!CommunityJoinSession.isRunning || CommunityJoinSession.stopRequested) return
        isScheduleWaiting = true
        scheduleRemainingMs = totalMillis.coerceAtLeast(0L)
        scheduleEndRealtime = SystemClock.elapsedRealtime() + scheduleRemainingMs
        scheduleTotalSeconds = maxOf(1, ((scheduleRemainingMs + 999) / 1000).toInt())
        val runnable = object : Runnable {
            override fun run() {
                if (!CommunityJoinSession.isRunning || CommunityJoinSession.stopRequested) return
                if (!CommunityJoinSession.isPaused) {
                    val remainingMs = (scheduleEndRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    val remainingSeconds = ((remainingMs + 999) / 1000).toInt()
                    if (remainingMs <= 0) {
                        clearScheduleCountdown()
                        resetScheduleState()
                        openCurrentUrlImmediately()
                        return
                    }
                    val timeText = CommunityJoinSession.getScheduledStartTimeText().ifBlank { "--:--" }
                    sendProgress("⏰ 已排程至 $timeText 開始\n倒數 $remainingSeconds / $scheduleTotalSeconds 秒")
                    handler.postDelayed(this, 1000L)
                } else {
                    scheduleRemainingMs = (scheduleEndRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                }
            }
        }
        scheduleRunnable = runnable
        handler.post(runnable)
    }

    private fun clearScheduleCountdown() {
        scheduleRunnable?.let { handler.removeCallbacks(it) }
        scheduleRunnable = null
    }

    private fun resetScheduleState() {
        isScheduleWaiting = false
        scheduleEndRealtime = 0L
        scheduleRemainingMs = 0L
        scheduleTotalSeconds = 0
    }

    private fun clearCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun completeAll(message: String) {
        clearCountdown()
        resetFlowState()
        resetIntervalState()
        resetScheduleState()
        CommunityJoinSession.finishAll()
        handler.removeCallbacksAndMessages(null)
        sendProgress(message)
        handler.postDelayed({ OverlayStatusManager.hide() }, 1800L)
    }

    private fun stopAllWithMessage(message: String) {
        clearCountdown()
        clearWatchdog()
        clearScheduleCountdown()
        resetFlowState()
        resetIntervalState()
        resetScheduleState()
        CommunityJoinSession.finishAll()
        handler.removeCallbacksAndMessages(null)
        sendProgress(message)
        OverlayStatusManager.hide()
    }

    private fun clickText(root: AccessibilityNodeInfo?, targets: List<String>): Boolean {
        val node = CommunityScreenDetector.findClickableNodeByTexts(root, targets) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val dm: DisplayMetrics = resources.displayMetrics
        val x = rect.centerX().coerceIn(0, dm.widthPixels)
        val y = rect.centerY().coerceIn(0, dm.heightPixels)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun tapBottomWideArea(): Boolean {
        val xList = listOf(0.2f, 0.5f, 0.8f)
        val yList = listOf(0.90f, 0.93f, 0.96f)
        for (y in yList) {
            for (x in xList) {
                if (tapByRatio(x, y)) return true
            }
        }
        return false
    }

    private fun tapWebViewBottomArea(webView: AccessibilityNodeInfo?): Boolean {
        if (webView == null) return false
        val rect = android.graphics.Rect()
        webView.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val xList = listOf(0.2f, 0.5f, 0.8f)
        val yList = listOf(0.82f, 0.88f, 0.94f)
        for (yr in yList) {
            val y = rect.top + (rect.height() * yr)
            for (xr in xList) {
                val x = rect.left + (rect.width() * xr)
                if (tapAbsolute(x, y)) return true
            }
        }
        return false
    }

    private fun tapAbsolute(x: Float, y: Float): Boolean {
        val dm: DisplayMetrics = resources.displayMetrics
        val clampedX = x.coerceIn(0f, dm.widthPixels.toFloat())
        val clampedY = y.coerceIn(0f, dm.heightPixels.toFloat())
        val path = Path().apply { moveTo(clampedX, clampedY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        return dispatchGesture(gesture, null, null)
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

    private fun currentStageToken(): Long = stageToken

    private fun invalidateStage() {
        stageToken++
    }

    private fun canWork(token: Long): Boolean {
        return token == stageToken &&
            CommunityJoinSession.isRunning &&
            !CommunityJoinSession.stopRequested &&
            !CommunityJoinSession.isPaused &&
            !finishingCurrentUrl &&
            !processingUrlTransition &&
            !isIntervalWaiting &&
            !isScheduleWaiting
    }

    private fun postStageDelay(delayMs: Long, token: Long, block: () -> Unit) {
        handler.postDelayed({
            if (canWork(token)) {
                block()
            }
        }, delayMs)
    }

    private fun resetThrottle() {
        lastActionSignature = ""
        lastActionAt = 0L
    }

    private fun resetCurrentUrlSendState() {
        disableNodeSendForCurrentUrl = false
        mentionCorruptionCountForCurrentUrl = 0
        postJoinRecoveryCount = 0
    }

    private fun tapByRatioRepeated(xRatio: Float, yRatio: Float, count: Int = 3, intervalMs: Long = 120L): Boolean {
        val ok = tapByRatio(xRatio, yRatio)
        if (count <= 1) return ok
        var i = 1
        while (i < count) {
            val delay = intervalMs * i
            handler.postDelayed({ tapByRatio(xRatio, yRatio) }, delay)
            i++
        }
        return ok
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
