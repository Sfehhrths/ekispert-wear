package dev.sfehhrths.ekispertwear.tiles

import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import dev.sfehhrths.ekispertwear.CourseLogic
import dev.sfehhrths.ekispertwear.CourseLogic.hhmm
import dev.sfehhrths.ekispertwear.shared.Course
import dev.sfehhrths.ekispertwear.shared.Line

/**
 * 経路 tile: the top of the route page — date, first station, first leg, next station.
 * When the course is under way it starts from the leg the user should board next.
 */
class RouteTileService : CourseTileBase() {

    override val freshnessMillis: Long = 60_000

    override fun layout(course: Course?, now: Long): LayoutElement {
        if (course == null) return column(titleRow("経路"), spacer(40f), text("経路がありません", 13f, AwArgb.SECONDARY, align = LayoutElementBuilders.TEXT_ALIGN_CENTER), horizontalAlign = LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // First leg whose arrival is still ahead (skip walks for the "next boarding" feel).
        val idx = course.lines.indexOfFirst { (CourseLogic.epoch(it.arrival) ?: Long.MAX_VALUE) > now && it.type != "walk" }
            .takeIf { it >= 0 } ?: 0
        val line = course.lines[idx]
        val from = course.points.getOrNull(idx)?.name ?: ""
        val to = course.points.getOrNull(idx + 1)?.name ?: ""

        return column(
            titleRow("経路"),
            spacer(2f),
            padded(text(CourseLogic.dateSlash(course.departure), 12f, AwArgb.DATE)),
            spacer(4f),
            padded(text(from, 16f, bold = true)),
            spacer(4f),
            legBlock(line),
            spacer(4f),
            padded(text(to, 16f, bold = true)),
        )
    }

    private fun padded(e: LayoutElement): LayoutElement = row(hspacer(28f), e, weightSpacer())

    private fun legBlock(line: Line): LayoutElement {
        val bar = block(4f, 58f, CourseLogic.lineColor(line))
        val platform = CourseLogic.platformLabel(line, line.departurePlatform)?.let { text(it, 13f, AwArgb.PLATFORM) }
        val top = LayoutElementBuilders.Row.Builder()
            .setWidth(expand())
            .addContent(text(hhmm(line.departure), 14f))
            .addContent(weightSpacer())
            .apply { if (platform != null) addContent(platform) }
            .addContent(hspacer(BEZEL_INSET))
            .build()
        val body = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .addContent(top)
            .addContent(text(if (line.type == "walk") "徒歩" else line.name, 12f, AwArgb.SECONDARY, maxLines = 1))
            .addContent(text(hhmm(line.arrival), 14f))
            .build()
        return row(hspacer(28f), bar, hspacer(8f), body, verticalAlign = LayoutElementBuilders.VERTICAL_ALIGN_TOP)
    }
}
