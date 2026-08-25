package com.travisse.pikasync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.travisse.pikasync.pipeline.PipelineResult
import com.travisse.pikasync.pipeline.PipelineRunner
import com.travisse.pikasync.pipeline.StageTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private data class MonthOption(val year: Int, val month: Int, val label: String)

private fun lastTwelveMonths(): List<MonthOption> {
    val fmt = SimpleDateFormat("MMMM yyyy", Locale.US)
    val cal = Calendar.getInstance()
    cal.set(Calendar.DAY_OF_MONTH, 1)
    return (0 until 12).map {
        val opt = MonthOption(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, fmt.format(cal.time))
        cal.add(Calendar.MONTH, -1)
        opt
    }
}

@Composable
fun PipelineScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val months = remember { lastTwelveMonths() }
    var selected by remember { mutableStateOf(months.first()) }
    var menuOpen by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val timings = remember { mutableStateListOf<StageTiming>() }
    var result by remember { mutableStateOf<PipelineResult?>(null) }
    var showBook by remember { mutableStateOf(false) }

    val bookResult = result
    if (showBook && bookResult?.judge != null) {
        BookViewer(bookResult) { showBook = false }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Photobook pipeline", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, enabled = !running) {
                        Text(selected.label)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        months.forEach { m ->
                            DropdownMenuItem(text = { Text(m.label) }, onClick = {
                                selected = m; menuOpen = false
                            })
                        }
                    }
                }
                Button(enabled = !running, onClick = {
                    running = true
                    timings.clear()
                    result = null
                    scope.launch {
                        val r = withContext(Dispatchers.Default) {
                            PipelineRunner(context).run(selected.year, selected.month) { t ->
                                scope.launch { timings.add(t) }
                            }
                        }
                        result = r
                        running = false
                    }
                }) { Text(if (running) "Running..." else "Run") }
                if (running) CircularProgressIndicator(Modifier.padding(start = 4.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        items(timings) { t ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.name, fontWeight = FontWeight.Bold)
                Text("${t.ms} ms", color = MaterialTheme.colorScheme.secondary)
            }
            Text(t.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        item {
            result?.error?.let { err ->
                Text("Error: $err", color = MaterialTheme.colorScheme.error)
            }
            result?.judge?.let { j ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("\"${j.title}\"", style = MaterialTheme.typography.titleMedium)
                Text("Tokens: ${j.inputTokens} in / ${j.outputTokens} out")
                Text("Cost estimate: $" + String.format(Locale.US, "%.4f", j.costUsd) +
                        "  (\$3/M in, \$15/M out)")
                Button(onClick = { showBook = true }) { Text("Open book") }
            }
        }
    }
}

/** Stage 8: book viewer. Page 0 is the cover; then the picks in page order. */
@Composable
private fun BookViewer(result: PipelineResult, onClose: () -> Unit) {
    val judge = result.judge ?: return
    val pagerState = rememberPagerState(pageCount = { judge.selections.size + 1 })
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(judge.title, color = Color.White, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            if (page == 0) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    result.shortlist.getOrNull(judge.coverIndex)?.let { cover ->
                        AsyncImage(
                            model = cover.uri,
                            contentDescription = "cover",
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Text(
                        judge.title,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                val sel = judge.selections[page - 1]
                val photo = result.shortlist.getOrNull(sel.index)
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    if (photo != null) {
                        AsyncImage(
                            model = photo.uri,
                            contentDescription = "page ${sel.page}",
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Text(
                        "page ${sel.page}",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
        }
    }
}
