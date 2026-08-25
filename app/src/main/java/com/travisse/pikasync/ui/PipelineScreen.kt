package com.travisse.pikasync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.travisse.pikasync.pipeline.StageTiming
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class MonthOption(val year: Int, val month: Int, val label: String)

fun lastTwelveMonths(): List<MonthOption> {
    val fmt = SimpleDateFormat("MMMM yyyy", Locale.US)
    val cal = Calendar.getInstance()
    cal.set(Calendar.DAY_OF_MONTH, 1)
    return (0 until 12).map {
        val opt = MonthOption(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, fmt.format(cal.time))
        cal.add(Calendar.MONTH, -1)
        opt
    }
}

/** "Create Photobook" bottom sheet: pick a month, hit Create. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateBookSheet(
    onDismiss: () -> Unit,
    onCreate: (year: Int, month: Int, label: String) -> Unit,
) {
    val months = remember { lastTwelveMonths() }
    var selected by remember { mutableStateOf(months.first()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = Pika.SheetShape,
        containerColor = Pika.Bg,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
            Text("Create Photobook", style = Pika.Title, fontSize = 22.sp)
            Text(
                "Pick a month; Pikabook does the rest.",
                style = Pika.Caption, fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )
            // 2-column grid of month chips (DESIGN.md v2: accent tint 10% + stroke when selected)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                months.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { m ->
                            val active = m == selected
                            Box(
                                Modifier
                                    .weight(1f)
                                    .background(
                                        if (active) Pika.Coral.copy(alpha = 0.10f) else Pika.Section,
                                        Pika.PillShape,
                                    )
                                    .then(
                                        if (active) Modifier.border(
                                            1.5.dp, Pika.Coral, Pika.PillShape
                                        ) else Modifier
                                    )
                                    .clickable { selected = m }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    m.label,
                                    color = if (active) Pika.Coral else Pika.Ink,
                                    fontSize = 15.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            PillButton(
                "Create",
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { onCreate(selected.year, selected.month, selected.label) }
        }
    }
}

/** Stage-timing list shared by the progress sheet and "Pipeline details". */
@Composable
fun StageList(stages: List<StageTiming>, running: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stages.forEach { t -> StageRow(t) }
        if (running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Pika.Coral, strokeWidth = 2.dp)
                Text("Working…", style = Pika.Caption, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun StageRow(t: StageTiming) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).background(Pika.Coral, CircleShape))
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
