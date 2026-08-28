package com.travisse.pikasync.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.travisse.pikasync.pipeline.RunStore
import com.travisse.pikasync.pipeline.SavedRun
import com.travisse.pikasync.pipeline.SavedSelection
import com.travisse.pikasync.pipeline.ShareClient
import kotlinx.coroutines.launch

/**
 * Book viewer as printed spreads: a title page (one square + title), then two
 * square photos per spread. Tapping any square opens the full uncropped photo,
 * where per-photo feedback lives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(run: SavedRun, onDelete: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = remember(run.id) { run.selections.sortedBy { it.page } }
    val spreads = remember(run.id) { pages.chunked(2) }
    val pagerState = rememberPagerState(pageCount = { spreads.size + 1 })

    var showActions by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    // Full-photo detail: sharePage 0 = cover, selection.page otherwise.
    var detail by remember { mutableStateOf<Pair<String, Int>?>(null) }  // (uri, sharePage)
    var feedbackTarget by remember { mutableStateOf<Int?>(null) }        // sharePage; -1 = whole book
    var shareStatus by remember { mutableStateOf<String?>(null) }  // brief error/confirm capsule only
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(run.id) {
        com.travisse.pikasync.Analytics.capture("book_viewed",
            mapOf("pages" to run.selections.size, "shared" to (run.shareUrl != null)))
    }

    // Lazily create the server book, caching share info on the stored run.
    suspend fun ensureShared(): SavedRun {
        val current = RunStore.load(context).firstOrNull { it.id == run.id } ?: run
        if (current.shareId != null) return current
        sharing = true
        try {
            val share = ShareClient.upload(context, current)
            val updated = current.copy(shareId = share.shareId, shareUrl = share.url, needsShare = false)
            RunStore.update(context, updated)
            BookCreator.notifyRunsChanged()
            return updated
        } finally {
            sharing = false
        }
    }

    fun shareBook() {
        scope.launch {
            try {
                val shared = ensureShared()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "${shared.title} — ${shared.shareUrl}")
                }
                context.startActivity(Intent.createChooser(send, "Share book"))
            } catch (e: Exception) {
                shareStatus = "share failed: ${e.message?.take(60)}"
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Pika.Bg)) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
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
                // Title page: one square + the book title, like the printed cover.
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SquarePhoto(run.coverUri) { detail = run.coverUri to 0 }
                    Text(
                        run.title,
                        style = Pika.Headline,
                        fontSize = 25.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 26.dp),
                    )
                    Text(run.monthLabel, style = Pika.Caption, modifier = Modifier.padding(top = 6.dp))
                }
            } else {
                // Spread: two squares side by side, like an open book.
                val spread = spreads[page - 1]
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    spread.forEach { sel ->
                        Box(Modifier.weight(1f)) {
                            SquarePhoto(sel.uri) { detail = sel.uri to sel.page }
                        }
                    }
                    if (spread.size == 1) {
                        // odd last page: blank facing square, like a printed book
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(Pika.CardShape)
                                .background(Pika.Section)
                        )
                    }
                }
            }
        }

        if (sharing || shareStatus != null) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sharing) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Pika.Coral)
                    Spacer(Modifier.size(8.dp))
                    Text("Preparing link…", style = Pika.Caption, fontSize = 13.sp)
                } else {
                    Text(shareStatus ?: "", style = Pika.Caption, fontSize = 13.sp)
                }
            }
        }

        // Page dots (no numbers anywhere).
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.Center,
        ) {
            val count = spreads.size + 1
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

    // Full uncropped photo, feedback affordance lives here.
    detail?.let { (uri, sharePage) ->
        Box(Modifier.fillMaxSize().background(Pika.Bg).statusBarsPadding().clickable(enabled = false) {}) {
            AsyncImage(
                model = uri,
                contentDescription = "photo",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 60.dp)
                    .clip(Pika.CardShape),
                contentScale = ContentScale.Fit,
            )
            IconButton(onClick = { detail = null }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Outlined.Close, "close", tint = Pika.Ink)
            }
            PillButton(
                "Leave feedback",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .shadow(10.dp, Pika.PillShape, spotColor = Color.Black.copy(alpha = 0.2f)),
            ) { feedbackTarget = sharePage }
        }
    }
    }  // outer Box

    if (showActions) {
        ModalBottomSheet(
            onDismissRequest = { showActions = false },
            sheetState = rememberModalBottomSheetState(),
            shape = Pika.SheetShape,
            containerColor = Pika.Bg,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text(run.title, style = Pika.Title, modifier = Modifier.padding(bottom = 16.dp))
                SheetAction("Share this book", run.shareUrl ?: "Uploads and copies a link anyone can open") {
                    showActions = false
                    shareBook()
                }
                SheetAction("Feedback on this book", "What did we get right or wrong?") {
                    showActions = false
                    feedbackTarget = -1
                }
                SheetAction("Pipeline details", "How this book was made") {
                    showActions = false
                    showDetails = true
                }
                SheetAction("Delete book", "Removes it from your library; photos are untouched", destructive = true) {
                    showActions = false
                    showDeleteConfirm = true
                }
            }
        }
    }

    if (showDetails) {
        ModalBottomSheet(
            onDismissRequest = { showDetails = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = Pika.SheetShape,
            containerColor = Pika.Bg,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 36.dp)) {
                Text("Behind the scenes", style = Pika.Title)
                Text(run.judgeInfo, style = Pika.Caption, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
                if (run.stages.isEmpty()) {
                    Text("No stage log for this book.", style = Pika.Caption)
                } else {
                    StageList(run.stages, running = false)
                }
            }
        }
    }

    feedbackTarget?.let { target ->
        FeedbackSheet(
            label = if (target == -1) "this book" else if (target == 0) "the cover" else "this photo",
            onDismiss = { feedbackTarget = null },
            onSend = { reaction, text ->
                scope.launch {
                    try {
                        val shared = ensureShared()
                        ShareClient.sendFeedback(
                            shareId = shared.shareId!!,
                            page = if (target == -1) null else target,
                            reaction = reaction,
                            text = text,
                        )
                        com.travisse.pikasync.Analytics.capture("feedback_posted",
                            mapOf("page" to (if (target == -1) -1 else target), "has_text" to !text.isNullOrBlank(), "reaction" to (reaction ?: "")))
                        shareStatus = "Feedback sent"
                        kotlinx.coroutines.delay(1500)
                        shareStatus = null
                    } catch (e: Exception) {
                        shareStatus = "feedback failed: ${e.message?.take(60)}"
                    }
                }
                feedbackTarget = null
            },
        )
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
                    onClick = {
                        showDeleteConfirm = false
                        com.travisse.pikasync.Analytics.capture("book_deleted", mapOf("pages" to run.selections.size))
                        onDelete()
                    },
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

/** Square, top-anchored crop — how the photo will sit in the printed book. */
@Composable
private fun SquarePhoto(uri: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    AsyncImage(
        model = uri,
        contentDescription = "photo",
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pressScale(interaction)
            .shadow(8.dp, Pika.CardShape, spotColor = Color.Black.copy(alpha = 0.15f))
            .clip(Pika.CardShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
    )
}

/** Reaction pills + optional note, posting into the shared feedback table. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackSheet(
    label: String,
    onDismiss: () -> Unit,
    onSend: (reaction: String?, text: String?) -> Unit,
) {
    var reaction by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = Pika.SheetShape,
        containerColor = Pika.Bg,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Feedback · $label", style = Pika.Title)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf("❤️" to "love", "😐" to "meh", "✂️" to "cut").forEach { (emoji, value) ->
                    val active = reaction == value
                    Box(
                        Modifier
                            .size(56.dp)
                            .background(
                                if (active) Pika.Coral.copy(alpha = 0.12f) else Pika.Section,
                                RoundedCornerShape(14.dp),
                            )
                            .then(
                                if (active) Modifier.background(
                                    Color.Transparent, RoundedCornerShape(14.dp)
                                ) else Modifier
                            )
                            .clickable { reaction = if (active) null else value },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 26.sp) }
                }
            }
            Spacer(Modifier.height(18.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(fontSize = 16.sp, color = Pika.Ink),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Pika.Section, RoundedCornerShape(14.dp))
                            .padding(16.dp),
                    ) {
                        if (text.isEmpty()) Text("Add details (optional)", style = Pika.Caption, fontSize = 16.sp)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
            Spacer(Modifier.height(18.dp))
            PillButton(
                "Send feedback",
                enabled = reaction != null || text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { onSend(reaction, text.ifBlank { null }) }
        }
    }
}

@Composable
private fun SheetAction(
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Pika.ChipRadius))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
    ) {
        Text(
            title,
            style = Pika.Body,
            color = if (destructive) Color(0xFFC13515) else Pika.Ink,
            fontSize = 17.sp,
        )
        Text(subtitle, style = Pika.Caption, modifier = Modifier.padding(top = 2.dp))
    }
}
