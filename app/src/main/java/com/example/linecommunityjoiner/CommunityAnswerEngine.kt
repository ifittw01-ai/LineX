package com.example.linecommunityjoiner

import java.util.Locale

object CommunityAnswerEngine {
    fun generateAnswer(question: String, nickname: String): String {
        val q = question.lowercase(Locale.ROOT)
        return when {
            q.contains("暱稱") || q.contains("名字") || q.contains("姓名") || q.contains("怎麼稱呼") -> nickname
            q.contains("為什麼") || q.contains("原因") || q.contains("加入目的") || q.contains("想加入") ->
                "想加入社群交流與學習，會遵守社群規範，謝謝。"
            q.contains("哪裡人") || q.contains("住哪") || q.contains("地區") || q.contains("地點") -> "桃園"
            q.contains("是否遵守") || q.contains("會遵守") || q.contains("規範") || q.contains("規則") ->
                "會，我會遵守社群規範，謝謝。"
            q.contains("工作") || q.contains("職業") -> "不動產相關工作。"
            q.contains("推薦") || q.contains("怎麼知道") || q.contains("從哪知道") -> "朋友推薦。"
            q.contains("line id") || q.contains("聯絡方式") -> "加入後會依社群規範互動，謝謝。"
            else -> "想加入社群交流，會遵守社群規範，謝謝。"
        }
    }
}
