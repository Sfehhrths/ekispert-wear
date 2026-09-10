package dev.sfehhrths.ekispertwear.ui

import androidx.compose.ui.graphics.Color

/** Colours sampled from the Apple Watch 駅すぱあと screenshots (ext/reference/applewatch/2026_*.png). */
object AwColors {
    val background = Color(0xFF000000)
    val text = Color(0xFFFFFFFF)
    val secondary = Color(0xFF9E9EA3)     // 路線名・「徒歩」・「出発まであと」・途中駅
    val title = Color(0xFF8E8E93)         // ページ名（右上）
    val platform = Color(0xFF30D158)      // 番線
    val highlight = Color(0xFF1C3B6C)     // イマココ 停車中の帯
    val arrow = Color(0xFFFFCC00)         // イマココ ▼
    val majorMarker = Color(0xFFD8D8DC)   // 四角マーカー
    val minorMarker = Color(0xFF8E8E93)   // 小さい丸
    val walk = Color(0xFFD8D8DC)          // 徒歩の線・バー
    val date = Color(0xFFE5E5EA)
}
