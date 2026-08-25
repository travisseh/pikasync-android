package com.travisse.pikasync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home: image-forward gallery of saved books, Airbnb-style hero cards. */
@Composable
fun BooksScreen(
    runs: List<SavedRun>,
    onOpenBook: (SavedRun) -> Unit,
    onCreate: () -> Unit,
    onOpenSync: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Pika.Bg).statusBarsPadding()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp
            ),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your books", style = Pika.Headline)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenSync) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Sync & diagnostics",
                            tint = Pika.InkSecondary,
                        )
                    }
                }
            }

            if (runs.isEmpty()) {
                item { EmptyBooks(onCreate) }
            } else {
                items(runs, key = { it.id }) { run -> BookCard(run) { onOpenBook(run) } }
            }
        }

        // Floating primary action, always reachable.
        PillButton(
            text = "Create a book",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .shadow(12.dp, Pika.PillShape, spotColor = Color.Black.copy(alpha = 0.25f)),
            onClick = onCreate,
        )
    }
}

@Composable
private fun BookCard(run: SavedRun, onClick: () -> Unit) {
    val interaction = androidx.compose.runtime.remember { MutableInteractionSource() }
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
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Pika.Scrim,
                    )
                )
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
            Modifier
                .size(72.dp)
                .background(Pika.Section, CircleShape),
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
    }
}
