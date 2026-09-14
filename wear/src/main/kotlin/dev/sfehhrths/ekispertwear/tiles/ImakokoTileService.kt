package dev.sfehhrths.ekispertwear.tiles

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import dev.sfehhrths.ekispertwear.CourseLogic
import dev.sfehhrths.ekispertwear.MainActivity
import dev.sfehhrths.ekispertwear.shared.Course

/**
 * イマココ tile: a window of the stop sequence around the estimated current position
 * (previous / current / next stations) drawn like the app page. Advances through the stops via
 * the timeline built from [changePoints]; the minute re-request is only a backstop.
 */
class ImakokoTileService : CourseTileBase() {

    override val freshnessMillis: Long = 60_000

    /** Tapping the tile opens the app on the イマココ page. */
    override val launchPage: Int = MainActivity.PAGE_IMAKOKO

    /**
     * [CourseLogic.position] can change at every node arrival, departure, and one minute after
     * the departure (the "still stopped" grace period).
     */
    override fun changePoints(course: Course): List<Long> =
        CourseLogic.sequence(course).nodes.flatMap { n ->
            listOfNotNull(n.arrival, n.departure, n.departure?.let { it + 60_000 })
        }

    override fun layout(course: Course?, now: Long): LayoutElement {
        if (course == null) return column(titleRow("イマココ"), spacer(40f), text("経路がありません", 13f, AwArgb.SECONDARY, align = LayoutElementBuilders.TEXT_ALIGN_CENTER), horizontalAlign = LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        val seq = CourseLogic.sequence(course)
        val pos = CourseLogic.position(seq, now)
        if (seq.nodes.isEmpty()) return column(titleRow("イマココ"))

        // Choose a 3-node window around the position (or the start before departure).
        val anchor = when (pos) {
            is CourseLogic.Position.Stopped -> pos.nodeIndex
            is CourseLogic.Position.Between -> pos.fromIndex
            CourseLogic.Position.None -> if (seq.nodes.first().departure?.let { it > now } == true) 0 else seq.nodes.lastIndex - 1
        }
        val first = (anchor - 1).coerceIn(0, (seq.nodes.size - 3).coerceAtLeast(0))
        val last = (first + 2).coerceAtMost(seq.nodes.lastIndex)

        val col = LayoutElementBuilders.Column.Builder().setWidth(expand())
        col.addContent(titleRow("イマココ")).addContent(spacer(6f))
        for (i in first..last) {
            val node = seq.nodes[i]
            val stopped = (pos as? CourseLogic.Position.Stopped)?.nodeIndex == i
            col.addContent(nodeRow(node, stopped))
            if (i < last) {
                val seg = seq.segments.firstOrNull { it.fromIndex == i }
                val between = (pos as? CourseLogic.Position.Between)?.fromIndex == i
                col.addContent(segmentRow(seg?.color ?: AwArgb.MINOR, between))
            }
        }
        return col.build()
    }

    private fun nodeRow(node: CourseLogic.Node, stopped: Boolean): LayoutElement {
        val marker = if (node.isMajor) block(16f, 16f, AwArgb.MAJOR, 3f) else block(10f, 10f, AwArgb.MINOR, 5f)
        val label = text(node.name, if (node.isMajor) 16f else 14f, if (node.isMajor) AwArgb.TEXT else AwArgb.SECONDARY, bold = node.isMajor)
        val row = LayoutElementBuilders.Row.Builder()
            .setWidth(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(hspacer(26f))
            .addContent(LayoutElementBuilders.Box.Builder().setWidth(dp(24f)).addContent(marker).build())
            .apply { if (stopped) addContent(text("▼", 14f, AwArgb.ARROW)).addContent(hspacer(4f)) }
            .addContent(label)
        if (stopped) {
            row.setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(AwArgb.HIGHLIGHT))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(6f)).build())
                            .build(),
                    )
                    .setPadding(ModifiersBuilders.Padding.Builder().setTop(dp(4f)).setBottom(dp(4f)).build())
                    .build(),
            )
        }
        return row.build()
    }

    private fun segmentRow(color: Int, between: Boolean): LayoutElement {
        val line = if (between) {
            LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(block(4f, 6f, color))
                .addContent(text("▼", 14f, AwArgb.ARROW))
                .addContent(block(4f, 6f, color))
                .build()
        } else {
            block(4f, 22f, color)
        }
        return LayoutElementBuilders.Row.Builder()
            .setWidth(expand())
            .addContent(hspacer(26f))
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(dp(24f))
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(line)
                    .build(),
            )
            .build()
    }
}
