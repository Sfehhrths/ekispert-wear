package dev.sfehhrths.ekispertwear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.PageIndicatorStyle
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TimeTextDefaults
import dev.sfehhrths.ekispertwear.CourseLogic.hhmm
import dev.sfehhrths.ekispertwear.shared.Course
import dev.sfehhrths.ekispertwear.shared.CoursePayload
import dev.sfehhrths.ekispertwear.shared.Line
import dev.sfehhrths.ekispertwear.ui.AwColors
import kotlinx.coroutines.delay

private const val PAGE_IMAKOKO = 0
private const val PAGE_ROUTE = 1
private const val PAGE_TIMER = 2

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CourseStore.init(this)
        setContent {
            MaterialTheme {
                val payload by CourseStore.payload.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { CourseStore.refreshFromDataLayer(this@MainActivity) }
                Root(payload)
            }
        }
    }
}

/** Ticks once a second; every page derives "now" from this. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000)
        }
    }
    return now
}

@Composable
private fun Root(payload: CoursePayload?) {
    val pagerState = rememberPagerState(initialPage = PAGE_ROUTE) { 3 }
    val now = rememberNow()
    val indicatorState = remember(pagerState) {
        object : PageIndicatorState {
            override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
            override val selectedPage: Int get() = pagerState.currentPage
            override val pageCount: Int get() = pagerState.pageCount
        }
    }
    Box(Modifier.fillMaxSize().background(AwColors.background)) {
        HorizontalPager(state = pagerState, beyondViewportPageCount = 2) { page ->
            when (page) {
                PAGE_IMAKOKO -> ImakokoPage(payload?.course, now, pagerState, page)
                PAGE_ROUTE -> RoutePage(payload?.course, pagerState, page)
                else -> TimerPage(payload?.course, now)
            }
        }
        // Opaque backdrop under the clock so scrolled content never shows through it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(CLOCK_BAR_HEIGHT)
                .align(Alignment.TopCenter)
                .background(AwColors.background),
        )
        TimeText(timeTextStyle = TimeTextDefaults.timeTextStyle(color = AwColors.text))
        HorizontalPageIndicator(
            pageIndicatorState = indicatorState,
            indicatorStyle = PageIndicatorStyle.Linear,
            selectedColor = AwColors.text,
            unselectedColor = AwColors.title,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

/** Height of the black backdrop behind the clock; page content starts below it. */
private val CLOCK_BAR_HEIGHT = 28.dp

/** Page name, gray, right-aligned under the clock (watchOS navigation title). */
@Composable
private fun PageTitle(text: String) {
    Text(
        text,
        color = AwColors.title,
        fontSize = 14.sp,
        textAlign = TextAlign.End,
        modifier = Modifier
            .fillMaxWidth()
            // Inset from the right so the bezel curve does not clip the last glyph.
            .padding(top = CLOCK_BAR_HEIGHT + 2.dp, end = 36.dp),
    )
}

@Composable
private fun Empty(title: String, message: String) {
    Column(Modifier.fillMaxSize()) {
        PageTitle(title)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(message, color = AwColors.secondary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
        }
    }
}

/** LazyColumn with bezel/crown scrolling and the standard position indicator. */
@Composable
private fun ScrollingList(
    pagerState: PagerState,
    page: Int,
    listState: LazyListState,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val focusRequester = rememberActiveFocusRequester()
    Scaffold(positionIndicator = { PositionIndicator(lazyListState = listState) }) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = CLOCK_BAR_HEIGHT + 20.dp, bottom = 32.dp),
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
            content = content,
        )
    }
    // Only the visible page owns the rotary focus.
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == page) focusRequester.requestFocus()
    }
}

// --- 経路 ---------------------------------------------------------------------------------------

@Composable
private fun RoutePage(course: Course?, pagerState: PagerState, page: Int) {
    if (course == null) {
        Empty("経路", "スマホの駅すぱあとで\n経路を開いてください")
        return
    }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        ScrollingList(pagerState, page, listState) {
            item {
                Text(
                    CourseLogic.dateSlash(course.departure),
                    color = AwColors.date,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 10.dp),
                )
            }
            course.points.forEachIndexed { i, pt ->
                item { StationName(pt.name) }
                course.lines.getOrNull(i)?.let { ln -> item { LegBlock(ln) } }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        PageTitle("経路")
    }
}

@Composable
private fun StationName(name: String) {
    Text(
        name,
        color = AwColors.text,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 10.dp),
    )
}

@Composable
private fun LegBlock(line: Line) {
    val bar = Color(CourseLogic.lineColor(line))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, bottom = 10.dp)
            .height(IntrinsicSize.Min),
    ) {
        Spacer(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(bar, RoundedCornerShape(2.dp)),
        )
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(hhmm(line.departure), color = AwColors.text, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                CourseLogic.platformLabel(line, line.departurePlatform)?.let {
                    Text(it, color = AwColors.platform, fontSize = 14.sp)
                }
            }
            Text(
                if (line.type == "walk") "徒歩" else line.name,
                color = AwColors.secondary,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
            Text(hhmm(line.arrival), color = AwColors.text, fontSize = 15.sp)
        }
    }
}

// --- タイマー -----------------------------------------------------------------------------------

@Composable
private fun TimerPage(course: Course?, now: Long) {
    val target = course?.let { CourseLogic.timerTarget(it, now) }
    Column(Modifier.fillMaxSize()) {
        PageTitle("タイマー")
        if (target == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (course == null) "経路がありません" else "この経路は終了しました", color = AwColors.secondary, fontSize = 13.sp)
            }
            return
        }
        Column(
            // Sits a little above centre so the platform line stays inside the round bezel.
            Modifier.fillMaxSize().padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(target.stationName, color = AwColors.text, fontSize = 17.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(if (target.isArrival) "到着まであと" else "出発まであと", color = AwColors.secondary, fontSize = 13.sp)
            Text(
                CourseLogic.countdown(target.targetMillis - now),
                color = AwColors.text,
                fontSize = 50.sp,
                lineHeight = 54.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Row(Modifier.fillMaxWidth().padding(end = 40.dp)) {
                Spacer(Modifier.weight(1f))
                Text(target.platformLabel ?: "", color = AwColors.platform, fontSize = 15.sp)
            }
        }
    }
}

// --- イマココ -----------------------------------------------------------------------------------

@Composable
private fun ImakokoPage(course: Course?, now: Long, pagerState: PagerState, page: Int) {
    if (course == null) {
        Empty("イマココ", "経路がありません")
        return
    }
    val seq = remember(course) { CourseLogic.sequence(course) }
    val pos = CourseLogic.position(seq, now)
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        ScrollingList(pagerState, page, listState) {
            seq.nodes.forEachIndexed { i, node ->
                val stopped = (pos as? CourseLogic.Position.Stopped)?.nodeIndex == i
                item { NodeRow(node, stopped) }
                seq.segments.firstOrNull { it.fromIndex == i }?.let { seg ->
                    val between = (pos as? CourseLogic.Position.Between)?.fromIndex == i
                    item { SegmentRow(Color(seg.color), between) }
                }
            }
            item {
                Text(
                    "※時刻表通りに運行した場合の\n現在位置の目安です。",
                    color = AwColors.text,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp),
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        PageTitle("イマココ")
    }
}

private val MARKER_COLUMN = 32.dp

@Composable
private fun NodeRow(node: CourseLogic.Node, stopped: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .then(if (stopped) Modifier.background(AwColors.highlight, RoundedCornerShape(6.dp)) else Modifier)
            .padding(vertical = 6.dp),
    ) {
        Box(Modifier.width(MARKER_COLUMN), contentAlignment = Alignment.Center) {
            if (node.isMajor) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(AwColors.majorMarker))
            } else {
                Box(Modifier.size(11.dp).clip(CircleShape).background(AwColors.minorMarker))
            }
        }
        if (stopped) {
            Arrow()
            Spacer(Modifier.width(6.dp))
        }
        Text(
            node.name,
            color = if (node.isMajor) AwColors.text else AwColors.secondary,
            fontSize = if (node.isMajor) 17.sp else 15.sp,
            fontWeight = if (node.isMajor) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SegmentRow(color: Color, between: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(if (between) 44.dp else 34.dp)) {
        Box(Modifier.width(MARKER_COLUMN).fillMaxSize(), contentAlignment = Alignment.Center) {
            if (between) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(4.dp).height(8.dp).background(color, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(4.dp).height(8.dp).background(color, RoundedCornerShape(2.dp)))
                }
                Arrow()
            } else {
                Box(Modifier.width(4.dp).fillMaxSize().padding(vertical = 2.dp).background(color, RoundedCornerShape(2.dp)))
            }
        }
    }
}

/** Yellow ▼ (Apple uses a chevron-like triangle). */
@Composable
private fun Arrow() {
    Text("▼", color = AwColors.arrow, fontSize = 16.sp)
}
