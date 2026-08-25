package com.travisse.pikasync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.travisse.pikasync.pipeline.SavedRun

/** Home: image-forward gallery of saved books, with a live entry while one is being made. */
@Composable
fun BooksScreen(
    runs: List<SavedRun>,
    creatorJob: BookCreator.Job?,
    onOpenBook: (SavedRun) -> Unit,
    onCreate: () -> Unit,
) {
    var showProgress by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Pika.Bg).statusBarsPadding()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        ) {
            item { Text("Your books", style = Pika.Headline, modifier = Modifier.padding(top = 4.dp)) }

            if (creatorJob != null) {
                item { CreatingCard(creatorJob) { showProgress = true } }
            }

            if (runs.isEmpty() && creatorJob == null) {
                item { EmptyBooks(onCreate) }
            } else {
                items(runs, key = { it.id }) { run -> BookCard(run) { onOpenBook(run) } }
            }
        }
    }

    if (showProgress && creatorJob != null) {
        ProgressSheet(creatorJob, onDismiss = { showProgress = false })
    }
}

/** The in-flight book: placeholder card with live stage text; tap for details. */
@Composable
private fun CreatingCard(job: BookCreator.Job, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val failed = job.error != null
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .pressScale(interaction)
            .shadow(6.dp, Pika.CardShape, spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(Pika.CardShape)
            .background(Pika.Section)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(18.dp)
    ) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            if (failed) {
                Box(
                    Modifier.size(34.dp).background(Color(0xFFC13515), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("!", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                CircularProgressIndicator(
                    Modifier.size(30.dp), color = Pika.Coral, strokeWidth = 3.dp
                )
            }
            Spacer(Modifier.size(16.dp))
            Column {
                Text(
                    if (failed) "Couldn't make ${job.monthLabel}" else "Making your ${job.monthLabel} book",
                    style = Pika.Title, fontSize = 17.sp,
                )
                Text(
                    if (failed) "Tap for details" else job.status,
                    style = Pika.Caption, modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** Dev progress: the stage list, in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressSheet(job: BookCreator.Job, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = Pika.SheetShape,
        containerColor = Pika.Bg,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 36.dp)) {
            Text("Behind the scenes", style = Pika.Title)
            Text(job.monthLabel, style = Pika.Caption, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
            StageList(job.stages, running = job.running)
            job.error?.let { err ->
                Spacer(Modifier.height(12.dp))
                Text(
                    err,
                    color = Color(0xFFC13515), fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0xFFC13515).copy(alpha = 0.06f),
                            androidx.compose.foundation.shape.RoundedCornerShape(Pika.ChipRadius)
                        )
                        .padding(14.dp),
                )
                Spacer(Modifier.height(14.dp))
                val context = androidx.compose.ui.platform.LocalContext.current
                PillButton("Try again", modifier = Modifier.fillMaxWidth()) {
                    BookCreator.dismissFailed()
                    BookCreator.start(context, job.year, job.month, job.monthLabel)
                    onDismiss()
                }
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        BookCreator.dismissFailed()
                        onDismiss()
                    },
                    shape = Pika.PillShape,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Remove", color = Pika.Ink, fontSize = 15.sp) }
            }
        }
    }
}

@Composable
private fun BookCard(run: SavedRun, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .pressScale(interaction)
            .shadow(
                elevation = 10.dp,
                shape = Pika.CardShape,
                spotColor = Color.Black.copy(alpha = 0.20f),
                ambientColor = Color.Black.copy(alpha = 0.08f),
            )
            .clip(Pika.CardShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        AsyncImage(
            model = run.coverUri,
            contentDescription = run.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,  // portrait covers: keep faces, crop feet
        )
        // Bottom-third scrim for legibility (DESIGN.md: black 0% -> 55%).
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Pika.Scrim))
        )
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(run.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${run.monthLabel} · ${run.selections.size} photos" + if (run.trigger == "bg") "  ·  made for you" else "",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
            )
        }
        if (isRecent(run.createdAt)) {
            Text(
                "New",
                color = Pika.Ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .background(Color.White.copy(alpha = 0.92f), Pika.PillShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

private fun isRecent(createdAt: Long) = System.currentTimeMillis() - createdAt < 48 * 3600_000L

@Composable
private fun EmptyBooks(onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).background(Pika.Section, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("📖", fontSize = 34.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text("No books yet", style = Pika.Title)
        Spacer(Modifier.height(6.dp))
        Text(
            "Pikabook turns each month of photos\ninto a little book, automatically.",
            style = Pika.Caption,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        PillButton("Create a book", onClick = onCreate)
    }
}
