package dev.sfehhrths.ekispertwear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfehhrths.ekispertwear.shared.Course
import dev.sfehhrths.ekispertwear.shared.CoursePayload
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug / status screen. Opening it once after install also takes the app out of the
 * "stopped" state so manifest-declared receivers start getting broadcasts.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = CourseRepository.get(this)
        setContent {
            MaterialTheme {
                val state by repo.state.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                Scaffold { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text("Ekispert Wear companion", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text("last event: ${state.lastEvent} @ ${fmtTs(state.lastEventAt)}")
                        Text("search result: ${state.searchCourses.size} courses @ ${fmtTs(state.searchedAt)}")
                        Spacer(Modifier.height(12.dp))
                        val cur = state.current
                        if (cur == null) {
                            Text("No course yet. Search a route in 駅すぱあと and open one.")
                        } else {
                            PayloadView(cur)
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { scope.launch { repo.resend() } }, enabled = cur != null) {
                            Text("Resend to watch")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PayloadView(p: CoursePayload) {
    Text("source: ${p.source} @ ${fmtTs(p.updatedAt)}", style = MaterialTheme.typography.labelMedium)
    CourseView(p.course)
}

@Composable
private fun CourseView(c: Course) {
    Text(
        "#${c.index}  ${hhmm(c.departure)} → ${hhmm(c.arrival)}  ${c.totalMinutes}分  乗換${c.transferCount}回" +
            (c.totalOneway?.let { "  ¥$it" } ?: ""),
        style = MaterialTheme.typography.titleMedium,
    )
    c.points.forEachIndexed { i, pt ->
        Text("● ${pt.name}")
        c.lines.getOrNull(i)?.let { ln ->
            val when_ = if (ln.departure.isNotEmpty()) "${hhmm(ln.departure)}→${hhmm(ln.arrival)} " else ""
            Text("   │ $when_${ln.name}" + (ln.destination?.let { " (${it}行)" } ?: ""))
        }
    }
}

private val tsFmt = SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN)
private fun fmtTs(ms: Long) = if (ms > 0) tsFmt.format(Date(ms)) else "-"

/** "2026-09-11T05:42:00+09:00" -> "05:42" */
private fun hhmm(iso: String?): String =
    iso?.takeIf { it.length >= 16 }?.substring(11, 16) ?: "--:--"
