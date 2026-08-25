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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.travisse.pikasync.pipeline.SavedRun

/** Immersive saved-book viewer: photo large on white, page dots, sheet actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(run: SavedRun, onDelete: () -> Unit, onClose: () -> Unit) {
    val pages = remember(run) { run.selections.sortedBy { it.page } }
    val pagerState = rememberPagerState(pageCount = { pages.size + 1 })
    var showActions by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Pika.Bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = Pika.Ink)
            }
            Spacer(Modifier.weight(1f))
            Text(run.monthLabel, style = Pika.Caption, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showActions = true }) {
                Icon(Icons.Outlined.MoreVert, "actions", tint = Pika.Ink)
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            if (page == 0) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AsyncImage(
                        model = run.coverUri,
                        contentDescription = "cover",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .clip(Pika.CardShape),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        run.title,
                        style = Pika.Headline,
                        fontSize = 26.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    Text("${pages.size} photos", style = Pika.Caption)
                }
            } else {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = pages[page - 1].uri,
                        contentDescription = "page $page",
                        modifier = Modifier.fillMaxWidth().clip(Pika.CardShape),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        // Page dots
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.Center,
        ) {
            val count = pages.size + 1
            val shown = minOf(count, 12)
            val current = pagerState.currentPage
            repeat(shown) { i ->
                val active = if (count <= 12) i == current else i == (current * shown / count)
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (active) 8.dp else 6.dp)
                        .background(if (active) Pika.Ink else Pika.Hairline, CircleShape)
                )
            }
        }
    }

    if (showActions) {
        ModalBottomSheet(
            onDismissRequest = { showActions = false },
            sheetState = rememberModalBottomSheetState(),
            shape = Pika.SheetShape,
            containerColor = Pika.Bg,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text(run.title, style = Pika.Title, modifier = Modifier.padding(bottom = 16.dp))
                SheetAction("Share this book", "Coming to Android — share from the iOS app for now", enabled = false) {}
                SheetAction("Delete book", "Removes it from your library; photos are untouched", destructive = true) {
                    showActions = false
                    showDeleteConfirm = true
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ModalBottomSheet(
            onDismissRequest = { showDeleteConfirm = false },
            sheetState = rememberModalBottomSheetState(),
            shape = Pika.SheetShape,
            containerColor = Pika.Bg,
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp).padding(bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Delete this book?", style = Pika.Title)
                Spacer(Modifier.height(6.dp))
                Text("\"${run.title}\" will be removed. Your photos stay in your library.", style = Pika.Caption, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                androidx.compose.material3.Button(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    shape = Pika.PillShape,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC13515), contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Delete book", fontSize = 16.sp) }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showDeleteConfirm = false },
                    shape = Pika.PillShape,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Keep it", color = Pika.Ink, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun SheetAction(
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(Pika.ChipRadius))
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ) else Modifier
            )
            .padding(vertical = 12.dp),
    ) {
        Text(
            title,
            style = Pika.Body,
            color = when {
                !enabled -> Pika.InkSecondary
                destructive -> Color(0xFFC13515)
                else -> Pika.Ink
            },
            fontSize = 17.sp,
        )
        Text(subtitle, style = Pika.Caption, modifier = Modifier.padding(top = 2.dp))
    }
}
