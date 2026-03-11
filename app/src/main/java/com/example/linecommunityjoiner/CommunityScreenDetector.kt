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
        val blacklist = setOf("確定", "取消", "確認並加入")
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
        val screenHeight = if (rootBounds.height() > 0) rootBounds.height() else 0
        if (screenHeight <= 0) return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        traverse(root) { node ->
            if (!node.isClickable || !node.isEnabled) return@traverse
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
}
