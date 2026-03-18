package com.example.linecommunityjoiner

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.regex.Pattern

object CommunityScreenDetector {
    private val roomListHints = listOf(
        "聊天室列表",
        "可加入的聊天室",
        "請選擇您要加入的聊天室",
        "您也可由社群的聊天室選單中"
    )
    private val suspendedHints = listOf(
        "因違反服務條款",
        "您的帳號已被停用",
        "帳號已被停用"
    )
    private val blockerHints = listOf(
        "此社群不存在",
        "發生不明錯誤",
        "發生不明錯誤，請稍後再試",
        "無法正常執行請稍後再試",
        "此社群目前不開放使用網址/行動條碼的邀請",
        "不存在的社群",
        "稍後再試"
    )

    data class RoomCandidate(
        val bounds: Rect,
        val node: AccessibilityNodeInfo,
        val text: String,
        val count: Int?
    )

    fun detect(root: AccessibilityNodeInfo?): CommunityScreenType {
        if (root == null) return CommunityScreenType.UNKNOWN
        if (containsAnyText(root, listOf("需年滿18歲才能加入", "確認並加入"))) {
            return CommunityScreenType.AGE_CONFIRM_DIALOG
        }
        if (containsAnyText(root, suspendedHints)) {
            return CommunityScreenType.ACCOUNT_SUSPENDED
        }
        if (containsAnyText(root, listOf("等待核准中", "等待審核中", "等待批准中"))) {
            return CommunityScreenType.APPROVAL_PENDING
        }
        if (containsAnyText(root, blockerHints)) {
            return CommunityScreenType.GENERIC_BLOCKER_DIALOG
        }
        if (containsAnyText(root, listOf("輸入參加密碼"))) {
            return CommunityScreenType.PASSWORD_ENTRY
        }
        if (containsAnyText(root, listOf("您已送出加入此社群的請求"))) {
            return CommunityScreenType.APPLICATION_SENT
        }
        if (containsAnyText(root, listOf("社群使用小提醒"))) {
            return CommunityScreenType.REMINDER
        }
        if (containsAnyText(root, listOf("社群專屬個人檔案"))) {
            return CommunityScreenType.PROFILE
        }
        if (containsAnyText(root, listOf("回答問題"))) {
            return CommunityScreenType.QUESTION
        }
        if (containsAnyText(root, roomListHints)) {
            return CommunityScreenType.ROOM_LIST
        }
        if (containsAnyText(root, listOf("建立個人檔案並加入"))) {
            return CommunityScreenType.COMMUNITY_HOME
        }
        if (containsAllTexts(root, listOf("聊天", "記事本"))) {
            return CommunityScreenType.ALREADY_IN_COMMUNITY
        }
        if (isLikelyChatRoom(root)) {
            return CommunityScreenType.CHAT_ROOM
        }
        return CommunityScreenType.UNKNOWN
    }

    fun containsAnyText(root: AccessibilityNodeInfo?, targets: List<String>): Boolean {
        if (root == null) return false
        var found = false
        traverse(root) { node ->
            if (found) return@traverse
            val text = normalize(node.text?.toString().orEmpty())
            val desc = normalize(node.contentDescription?.toString().orEmpty())
            val viewId = normalize(node.viewIdResourceName.orEmpty())
            for (target in targets) {
                val t = normalize(target)
                if (text.contains(t) || desc.contains(t) || viewId.contains(t)) {
                    found = true
                    break
                }
            }
        }
        return found
    }

    fun containsAllTexts(root: AccessibilityNodeInfo?, targets: List<String>): Boolean {
        if (root == null) return false
        for (t in targets) {
            if (!containsAnyText(root, listOf(t))) return false
        }
        return true
    }

    fun findClickableNodeByTexts(root: AccessibilityNodeInfo?, targets: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        var result: AccessibilityNodeInfo? = null
        traverse(root) { node ->
            if (result != null) return@traverse
            val text = normalize(node.text?.toString().orEmpty())
            val desc = normalize(node.contentDescription?.toString().orEmpty())
            val viewId = normalize(node.viewIdResourceName.orEmpty())
            for (target in targets) {
                val t = normalize(target)
                if (text.contains(t) || desc.contains(t) || viewId.contains(t)) {
                    result = findClickableParent(node)
                    break
                }
            }
        }
        return result
    }

    fun findNodeByTexts(root: AccessibilityNodeInfo?, targets: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        var result: AccessibilityNodeInfo? = null
        traverse(root) { node ->
            if (result != null) return@traverse
            val text = normalize(node.text?.toString().orEmpty())
            val desc = normalize(node.contentDescription?.toString().orEmpty())
            val viewId = normalize(node.viewIdResourceName.orEmpty())
            for (target in targets) {
                val t = normalize(target)
                if (text.contains(t) || desc.contains(t) || viewId.contains(t)) {
                    result = node
                    break
                }
            }
        }
        return result
    }

    fun findTappableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isVisibleToUser) {
                val rect = Rect()
                current.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) return current
            }
            current = current.parent
        }
        return null
    }

    fun findBottomJoinCandidate(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        val screenHeight = rootBounds.height().coerceAtLeast(0)
        val screenWidth = rootBounds.width().coerceAtLeast(0)
        if (screenHeight <= 0 || screenWidth <= 0) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        traverse(root) { node ->
            if (!node.isEnabled || !node.isVisibleToUser) return@traverse
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (!(text.contains("加入") || desc.contains("加入"))) return@traverse
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return@traverse
            if (rect.centerY() < (screenHeight * 0.6f)) return@traverse
            if (rect.width() < (screenWidth * 0.4f)) return@traverse
            val area = rect.width() * rect.height()
            var score = area
            if (node.isClickable) score += 30000
            val className = node.className?.toString().orEmpty()
            if (className.contains("Button", ignoreCase = true)) score += 20000
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }

    fun findLargestWebView(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        traverse(root) { node ->
            val className = node.className?.toString().orEmpty()
            if (!className.contains("WebView", ignoreCase = true)) return@traverse
            if (!node.isVisibleToUser) return@traverse
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return@traverse
            val area = rect.width() * rect.height()
            if (area > bestArea) {
                bestArea = area
                best = node
            }
        }
        return best
    }

    fun findFirstEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        var result: AccessibilityNodeInfo? = null
        traverse(root) { node ->
            if (result != null) return@traverse
            if (node.isEditable) {
                result = node
            }
        }
        return result
    }

    fun findClickableNodeByViewIdHints(root: AccessibilityNodeInfo?, hints: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        var result: AccessibilityNodeInfo? = null
        traverse(root) { node ->
            if (result != null) return@traverse
            val viewId = normalize(node.viewIdResourceName.orEmpty())
            for (hint in hints) {
                val h = normalize(hint)
                if (viewId.contains(h)) {
                    result = findClickableParent(node)
                    break
                }
            }
        }
        return result
    }

    fun findLikelyChatEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val screen = getScreenBounds(root)
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        traverse(root) { node ->
            if (!node.isEditable) return@traverse
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return@traverse
            if (rect.bottom >= (screen.bottom * 0.72f) && rect.width() >= (screen.width() * 0.25f)) {
                candidates.add(node to rect)
            }
        }
        val best = candidates.sortedWith(
            compareByDescending<Pair<AccessibilityNodeInfo, Rect>> { it.second.top }
                .thenByDescending { it.second.width() }
        ).firstOrNull()
        return best?.first
    }

    fun findChatSendClickable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val byId = findClickableNodeByViewIdHints(
            root,
            listOf("chat_ui_send_button", "chat_ui_send_bu", "chat_ui_send", "send_button", "chat_send")
        )
        if (byId != null) return byId
        val byText = findClickableNodeByTexts(root, listOf("傳送", "送出", "send", "發送"))
        if (byText != null) return byText
        val screen = getScreenBounds(root)
        val input = findLikelyChatEditable(root) ?: return null
        val inputRect = Rect()
        input.getBoundsInScreen(inputRect)
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        traverse(root) { node ->
            val clickable = if (node.isClickable) node else findClickableParent(node) ?: return@traverse
            val rect = Rect()
            clickable.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return@traverse
            if (rect.top >= screen.bottom * 0.5f &&
                rect.left >= screen.right * 0.6f &&
                rect.width() <= screen.width() * 0.25f &&
                rect.height() <= screen.height() * 0.16f
            ) {
                val centerYDistance = kotlin.math.abs(rect.centerY() - inputRect.centerY())
                val maxDistance = maxOf(220, inputRect.height() * 2)
                if (centerYDistance <= maxDistance) {
                    candidates.add(clickable to rect)
                }
            }
        }
        val best = candidates.sortedWith(
            compareBy<Pair<AccessibilityNodeInfo, Rect>> { kotlin.math.abs(it.second.centerY() - inputRect.centerY()) }
                .thenByDescending { it.second.right }
                .thenByDescending { it.second.width() * it.second.height() }
        ).firstOrNull()
        return best?.first
    }

    fun findChatTabClickable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return findClickableNodeByTexts(root, listOf("聊天"))
    }

    fun extractLikelyQuestion(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val texts = mutableListOf<String>()
        traverse(root) { node ->
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotBlank()) texts.add(t)
        }
        val blacklist = setOf("回答問題", "下一步", "請先回答上方的問題才可送出加入申請。", "社群專屬個人檔案", "送出", "加入", "確定")
        val candidates = texts.map { it.trim() }.filter { it.isNotBlank() && !blacklist.contains(it) }
        candidates.firstOrNull { it.contains("？") || it.contains("?") }?.let { return it }
        return candidates.maxByOrNull { it.length } ?: ""
    }

    fun extractDialogSummary(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val blacklist = setOf("確定", "取消", "確認並加入", "關閉", "瞭解更多")
        val texts = mutableListOf<String>()
        traverse(root) { node ->
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotBlank() && !blacklist.contains(t)) texts.add(t)
        }
        return texts.maxByOrNull { it.length } ?: ""
    }

    fun findRoomClickableWithLargestCount(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val candidates = collectRoomCandidates(root).filter { it.count != null }
        val best = candidates.sortedWith(
            compareByDescending<RoomCandidate> { it.count ?: Int.MIN_VALUE }
                .thenBy { it.bounds.top }
                .thenBy { it.bounds.left }
        ).firstOrNull()
        return best?.node
    }

    fun findFallbackFirstRoomClickable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val candidates = collectRoomCandidates(root)
        val best = candidates.sortedWith(compareBy<RoomCandidate> { it.bounds.top }.thenBy { it.bounds.left }).firstOrNull()
        return best?.node
    }

    fun findProfileActionText(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        if (containsAnyText(root, listOf("送出"))) return "送出"
        if (containsAnyText(root, listOf("加入"))) return "加入"
        return null
    }

    fun findLargestClickableBottomButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        val screenHeight = rootBounds.height().coerceAtLeast(0)
        val screenWidth = rootBounds.width().coerceAtLeast(0)
        if (screenHeight <= 0 || screenWidth <= 0) return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        traverse(root) { node ->
            if (!node.isClickable || !node.isEnabled) return@traverse
            if (!node.isVisibleToUser) return@traverse
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return@traverse
            val centerY = rect.centerY()
            if (centerY < (screenHeight * 0.6f)) return@traverse
            val area = rect.width() * rect.height()
            if (area > bestArea) {
                bestArea = area
                best = node
            }
        }
        return best
    }

    fun findLargestBottomNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        val screenHeight = rootBounds.height().coerceAtLeast(0)
        val screenWidth = rootBounds.width().coerceAtLeast(0)
        if (screenHeight <= 0 || screenWidth <= 0) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        traverse(root) { node ->
            if (!node.isEnabled || !node.isVisibleToUser) return@traverse
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return@traverse
            val centerY = rect.centerY()
            if (centerY < (screenHeight * 0.6f)) return@traverse
            if (rect.width() < (screenWidth * 0.5f)) return@traverse
            if (rect.height() > (screenHeight * 0.35f)) return@traverse
            val area = rect.width() * rect.height()
            var score = area
            if (node.isClickable) score += 40000
            val className = node.className?.toString().orEmpty()
            if (className.contains("Button", ignoreCase = true)) score += 30000
            val text = node.text?.toString().orEmpty()
            if (text.contains("加入")) score += 20000
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }

    private fun collectRoomCandidates(root: AccessibilityNodeInfo): List<RoomCandidate> {
        val blacklist = listOf(
            "聊天室列表",
            "可加入的聊天室",
            "請選擇您要加入的聊天室",
            "您也可由社群的聊天室選單中",
            "來瀏覽此社群中的所有聊天室",
            "下一步",
            "返回"
        )
        val unique = LinkedHashMap<String, RoomCandidate>()
        traverse(root) { node ->
            val rawText = node.text?.toString()?.trim()
                ?: node.contentDescription?.toString()?.trim()
                ?: return@traverse
            if (rawText.isBlank()) return@traverse
            if (blacklist.any { rawText.contains(it) }) return@traverse
            val clickable = findClickableParent(node) ?: return@traverse
            val rect = Rect()
            clickable.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) return@traverse
            if (rect.top < 150) return@traverse
            val count = extractCountInParentheses(rawText)
            val looksLikeRoom = count != null || (!(rawText.contains("(") || rawText.contains("（")) && rawText.length in 2..30)
            if (!looksLikeRoom) return@traverse
            val key = "${rect.left}_${rect.top}_${rect.right}_${rect.bottom}"
            val candidate = RoomCandidate(Rect(rect), clickable, rawText, count)
            val old = unique[key]
            if (old == null) {
                unique[key] = candidate
            } else {
                val oldCount = old.count ?: -1
                val newCount = candidate.count ?: -1
                if (newCount > oldCount) unique[key] = candidate
            }
        }
        return unique.values.toList()
    }

    private fun extractCountInParentheses(text: String): Int? {
        val regex = Pattern.compile("[\\(（]\\s*([0-9,]+)\\s*[\\)）]")
        val matcher = regex.matcher(text)
        if (!matcher.find()) return null
        val raw = matcher.group(1)?.replace(",", "") ?: return null
        return raw.toIntOrNull()
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun getScreenBounds(root: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        return rect
    }

    private fun traverse(node: AccessibilityNodeInfo?, block: (AccessibilityNodeInfo) -> Unit) {
        if (node == null) return
        block(node)
        for (i in 0 until node.childCount) {
            traverse(node.getChild(i), block)
        }
    }

    private fun normalize(text: String): String {
        return text.replace("\\s+".toRegex(), "").lowercase(Locale.ROOT)
    }

    private fun isLikelyChatRoom(root: AccessibilityNodeInfo): Boolean {
        val input = findLikelyChatEditable(root) ?: return false
        val rect = Rect()
        input.getBoundsInScreen(rect)
        val screen = getScreenBounds(root)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        if (rect.bottom < screen.bottom * 0.72f || rect.height() > screen.height() * 0.15f) return false
        val hasComposerHint = containsAnyText(root, listOf("傳送", "送出", "send", "訊息", "message"))
        val hasSendNode = findChatSendClickable(root) != null
        return hasComposerHint || hasSendNode
    }
}
