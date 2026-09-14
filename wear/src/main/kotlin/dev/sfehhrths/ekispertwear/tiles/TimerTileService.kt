package dev.sfehhrths.ekispertwear.tiles

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.protolayout.expression.DynamicBuilders
import dev.sfehhrths.ekispertwear.CourseLogic
import dev.sfehhrths.ekispertwear.MainActivity
import dev.sfehhrths.ekispertwear.shared.Course
import java.time.Instant

/**
 * タイマー tile: station, 「出発まであと」, live countdown, platform.
 * The countdown is a ProtoLayout dynamic expression, so it ticks without re-requesting the tile;
 * the tile is re-requested every minute to roll over to the next leg.
 */
class TimerTileService : CourseTileBase() {

    override val freshnessMillis: Long = 60_000

    /** Tapping the tile opens the app on the タイマー page. */
    override val launchPage: Int = MainActivity.PAGE_TIMER

    override fun layout(course: Course?, now: Long): LayoutElement {
        val target = course?.let { CourseLogic.timerTarget(it, now) }
        if (target == null) {
            return column(
                titleRow("タイマー"), spacer(40f),
                text(if (course == null) "経路がありません" else "この経路は終了しました", 13f, AwArgb.SECONDARY, align = LayoutElementBuilders.TEXT_ALIGN_CENTER),
                horizontalAlign = LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER,
            )
        }
        val platform = target.platformLabel ?: ""
        val body = column(
            text(target.stationName, 16f, align = LayoutElementBuilders.TEXT_ALIGN_CENTER),
            spacer(8f),
            text(if (target.isArrival) "到着まであと" else "出発まであと", 12f, AwArgb.SECONDARY),
            countdownText(target.targetMillis),
            // Directly under the countdown and well inside the bezel (bottom-right clips first).
            row(weightSpacer(), text(platform, 14f, AwArgb.PLATFORM), hspacer(BEZEL_INSET + 6f)),
            horizontalAlign = LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER,
        )
        // Title pinned at the top like other tiles; the short body centred in the remaining space.
        return LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(titleRow("タイマー"))
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(expand())
                    .setHeight(expand())
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .setModifiers(ModifiersBuilders.Modifiers.Builder().setPadding(ModifiersBuilders.Padding.Builder().setBottom(dp(28f)).build()).build())
                    .addContent(body)
                    .build(),
            )
            .build()
    }

    /** `h:mm:ss` / `m:ss` driven by the platform clock. */
    private fun countdownText(targetMillis: Long): LayoutElement {
        val remaining = DynamicBuilders.DynamicInstant.platformTimeWithSecondsPrecision()
            .durationUntil(DynamicBuilders.DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(targetMillis)))
        // Clamp at zero: max(0, seconds).
        val secs = remaining.toIntSeconds()
        val zero = DynamicBuilders.DynamicInt32.constant(0)
        val clamped = DynamicBuilders.DynamicInt32.onCondition(secs.lt(0)).use(zero).elseUse(secs)
        val h = clamped.div(3600)
        val m = clamped.rem(3600).div(60)
        val s = clamped.rem(60)
        val two = DynamicBuilders.DynamicInt32.constant(2)
        val mm = m.format(DynamicBuilders.DynamicInt32.IntFormatter.Builder().setMinIntegerDigits(2).build())
        val ss = s.format(DynamicBuilders.DynamicInt32.IntFormatter.Builder().setMinIntegerDigits(2).build())
        val withHours = h.format().concat(DynamicBuilders.DynamicString.constant(":")).concat(mm)
            .concat(DynamicBuilders.DynamicString.constant(":")).concat(ss)
        val noHours = m.format().concat(DynamicBuilders.DynamicString.constant(":")).concat(ss)
        val dynamic = DynamicBuilders.DynamicString.onCondition(h.gt(0)).use(withHours).elseUse(noHours)

        return LayoutElementBuilders.Text.Builder()
            .setText(
                TypeBuilders.StringProp.Builder(CourseLogic.countdown(targetMillis - System.currentTimeMillis()))
                    .setDynamicValue(dynamic)
                    .build(),
            )
            .setLayoutConstraintsForDynamicText(
                TypeBuilders.StringLayoutConstraint.Builder("00:00:00")
                    .setAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                    .build(),
            )
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(40f))
                    .setColor(argb(AwArgb.TEXT))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                    .build(),
            )
            .build()
    }
}
