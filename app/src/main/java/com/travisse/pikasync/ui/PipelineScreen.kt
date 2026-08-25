package com.travisse.pikasync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.travisse.pikasync.pipeline.PipelineResult
import com.travisse.pikasync.pipeline.PipelineRunner
import com.travisse.pikasync.pipeline.RunStore
import com.travisse.pikasync.pipeline.SavedRun
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

/** Create-a-book flow: pick a month, watch friendly progress, open the result. */
@Composable
fun PipelineScreen(onOpenBook: (SavedRun) -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val months = remember { lastTwelveMonths() }
    var selected by remember { mutableStateOf(months.first()) }
    var menuOpen by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val timings = remember { mutableStateListOf<StageTiming>() }
    var result by remember { mutableStateOf<PipelineResult?>(null) }

    Column(Modifier.fillMaxSize().background(Pika.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = Pika.Ink)
            }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("New book", style = Pika.Headline)
            Spacer(Modifier.height(4.dp))
            Text("Pick a month; Pikabook does the rest.", style = Pika.Caption, fontSize = 15.sp)
            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    Row(
                        Modifier
                            .background(Pika.Section, Pika.PillShape)
                            .clickable(enabled = !running) { menuOpen = true }
                            .padding(horizontal = 20.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(selected.label, style = Pika.Body, fontWeight = FontWeight.Medium)
                        Text("  ▾", color = Pika.InkSecondary, fontSize = 13.sp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        months.forEach { m ->
                            DropdownMenuItem(text = { Text(m.label) }, onClick = { selected = m; menuOpen = false })
                        }
                    }
                }
                PillButton(text = if (running) "Making…" else "Make it", enabled = !running) {
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
                        if (r.error == null && r.judge != null) {
                            RunStore.load(context).firstOrNull()?.let(onOpenBook)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp),
        ) {
            items(timings) { t -> StageRow(t, done = true) }
            if (running) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Pika.Coral, strokeWidth = 2.dp)
                        Text("Working…", style = Pika.Caption, fontSize = 14.sp)
                    }
                }
            }
            result?.error?.let { err ->
                item {
                    Text(
                        err,
                        color = Color(0xFFC13515),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFC13515).copy(alpha = 0.06f), RoundedCornerShape(Pika.ChipRadius))
                            .padding(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StageRow(t: StageTiming, done: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 5.dp).size(8.dp).background(if (done) Pika.Coral else Pika.Hairline, CircleShape)
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                Text(t.name.substringAfter(' '), style = Pika.Body, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text("${t.ms / 1000.0}s", style = Pika.Caption)
            }
            Text(t.detail, style = Pika.Caption, fontSize = 12.sp)
        }
    }
}
