package dev.sfehhrths.ekispertwear.tiles

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.sfehhrths.ekispertwear.CourseStore
import dev.sfehhrths.ekispertwear.MainActivity
import dev.sfehhrths.ekispertwear.shared.Course

/** Apple Watch palette as ARGB ints for ProtoLayout. Keep in sync with ui/AwTheme.kt. */
object AwArgb {
    const val TEXT = 0xFFFFFFFF.toInt()
    const val SECONDARY = 0xFF9E9EA3.toInt()
    const val TITLE = 0xFF8E8E93.toInt()
    const val PLATFORM = 0xFF30D158.toInt()
    const val HIGHLIGHT = 0xFF1C3B6C.toInt()
    const val ARROW = 0xFFFFCC00.toInt()
    const val MAJOR = 0xFFD8D8DC.toInt()
    const val MINOR = 0xFF8E8E93.toInt()
    const val DATE = 0xFFE5E5EA.toInt()
}

/**
 * Common plumbing for the three tiles: builds the Tile from a layout, resources, click-to-open
 * (on the page named by [launchPage]). Subclasses implement [layout] and [launchPage].
 */
abstract class CourseTileBase : TileService() {

    abstract fun layout(course: Course?, now: Long): LayoutElement

    /** Re-request interval; 0 = only when the app asks. */
    open val freshnessMillis: Long = 0

    /** Page ([MainActivity.PAGE_IMAKOKO] etc.) the app opens on when the tile is tapped. */
    abstract val launchPage: Int

    override fun onTileRequest(request: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            CourseStore.init(this)
            val course = CourseStore.payload.value?.course
            val root = LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                // Box centres its child by default; we lay out from the top like system tiles.
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_TOP)
                .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openAppClickable()).build())
                .addContent(layout(course, System.currentTimeMillis()))
                .build()
            val tile = TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .apply { if (freshnessMillis > 0) setFreshnessIntervalMillis(freshnessMillis) }
                .build()
            completer.set(tile)
            "tile"
        }

    override fun onTileResourcesRequest(request: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
            "resources"
        }

    private fun openAppClickable() = ModifiersBuilders.Clickable.Builder()
        .setId("open")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(MainActivity::class.java.name)
                        .addKeyToExtraMapping(MainActivity.EXTRA_PAGE, ActionBuilders.AndroidIntExtra.Builder().setValue(launchPage).build())
                        .build(),
                )
                .build(),
        )
        .build()

    // --- layout helpers (plain ProtoLayout, no material) --------------------------------------

    protected fun text(
        s: String,
        sizeSp: Float,
        color: Int = AwArgb.TEXT,
        bold: Boolean = false,
        maxLines: Int = 1,
        align: Int = LayoutElementBuilders.TEXT_ALIGN_START,
    ): LayoutElement = LayoutElementBuilders.Text.Builder()
        .setText(s)
        .setMaxLines(maxLines)
        .setMultilineAlignment(align)
        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(sizeSp))
                .setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build(),
        )
        .build()

    protected fun spacer(h: Float): LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    protected fun hspacer(w: Float): LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setWidth(dp(w)).build()

    /** Solid rectangle (vertical bar, marker...). */
    protected fun block(w: Float, h: Float, color: Int, radius: Float = 2f): LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(w))
            .setHeight(dp(h))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(color))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(radius)).build())
                            .build(),
                    )
                    .build(),
            )
            .build()

    /**
     * Tile title in the platform's own style (matches the system / Samsung tiles on the carousel):
     * top centre, white, medium weight. Unlike the app pages, which follow the Apple Watch look.
     */
    protected fun titleRow(title: String): LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            // System tiles centre their title about 17dp below the top edge.
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setPadding(ModifiersBuilders.Padding.Builder().setTop(dp(8f)).setBottom(dp(6f)).build()).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(title)
                    .setMaxLines(1)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(16f))
                            .setColor(argb(AwArgb.TEXT))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build(),
                    )
                    .build(),
            )
            .build()

    protected fun column(vararg children: LayoutElement, horizontalAlign: Int = LayoutElementBuilders.HORIZONTAL_ALIGN_START): LayoutElement =
        LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(horizontalAlign)
            .apply { children.forEach { addContent(it) } }
            .build()

    protected fun row(vararg children: LayoutElement, verticalAlign: Int = LayoutElementBuilders.VERTICAL_ALIGN_CENTER): LayoutElement =
        LayoutElementBuilders.Row.Builder()
            .setWidth(expand())
            .setVerticalAlignment(verticalAlign)
            .apply { children.forEach { addContent(it) } }
            .build()

    protected fun weightSpacer(): LayoutElement = LayoutElementBuilders.Spacer.Builder().setWidth(expand()).build()

    companion object {
        const val RESOURCES_VERSION = "2"

        /** Right-side inset (dp) keeping right-aligned text clear of the round bezel. */
        const val BEZEL_INSET = 40f

        fun requestUpdateAll(context: Context) {
            val updater = TileService.getUpdater(context)
            updater.requestUpdate(RouteTileService::class.java)
            updater.requestUpdate(TimerTileService::class.java)
            updater.requestUpdate(ImakokoTileService::class.java)
        }
    }
}
