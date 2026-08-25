package com.travisse.pikasync.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.travisse.pikasync.pipeline.PeopleScanner
import com.travisse.pikasync.pipeline.PeopleStore
import kotlinx.coroutines.launch

/** People: photo-forward grid of discovered faces; star your family, exclude anyone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = PeopleScanner.peopleVersion
    val clusters = remember(version) { PeopleStore.load(context).sortedByDescending { it.count } }
    var selected by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Pika.Bg).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("People", style = Pika.Headline, modifier = Modifier.weight(1f))
                if (!PeopleScanner.scanning) {
                    OutlinedButton(
                        onClick = { scope.launch { PeopleScanner.scan(context); PeopleScanner.notifyPeopleChanged() } },
                        shape = Pika.PillShape,
                    ) { Text("Rescan", color = Pika.Ink, fontSize = 13.sp) }
                }
            }
            Text(
                "Star your family — every book is built around them.",
                style = Pika.Caption, modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (PeopleScanner.scanning) {
                Column(Modifier.padding(20.dp)) {
                    LinearProgressIndicator(
                        progress = { PeopleScanner.progress.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        color = Pika.Coral,
                    )
                    Text(PeopleScanner.statusText, style = Pika.Caption, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (clusters.isEmpty() && !PeopleScanner.scanning) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No people yet", style = Pika.Title)
                    Spacer(Modifier.height(6.dp))
                    Text("Scan your photos to find the faces\nthat matter.", style = Pika.Caption,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    PillButton("Scan my photos") {
                        scope.launch { PeopleScanner.scan(context); PeopleScanner.notifyPeopleChanged() }
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(clusters, key = { it.id }) { c ->
                    PersonTile(
                        name = c.name,
                        role = c.role,
                        count = c.count,
                        cropPNG = c.repCropPNG,
                        onClick = { selected = c.id },
                    )
                }
            }
        }
    }

    val selId = selected
    if (selId != null) {
        val cluster = clusters.firstOrNull { it.id == selId }
        if (cluster != null) {
            var name by remember(selId) { mutableStateOf(cluster.name) }
            var role by remember(selId) { mutableStateOf(cluster.role) }
            ModalBottomSheet(
                onDismissRequest = {
                    if (name.isNotBlank() && (name != cluster.name || role != cluster.role)) {
                        PeopleStore.update(context, cluster.apply { this.name = name; this.role = role })
                        PeopleScanner.notifyPeopleChanged()
                    }
                    selected = null
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = Pika.SheetShape,
                containerColor = Pika.Bg,
            ) {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FaceImage(cluster.repCropPNG, 64.dp)
                        Spacer(Modifier.size(14.dp))
                        Column {
                            Text(cluster.name, style = Pika.Title)
                            Text("${cluster.count} photos", style = Pika.Caption)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RoleChip("⭐ Star", selectedNow = role == "required") { role = "required" }
                        RoleChip("Neutral", selectedNow = role == "neutral") { role = "neutral" }
                        RoleChip("🚫 Exclude", selectedNow = role == "excluded") { role = "excluded" }
                    }
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = {
                            PeopleStore.remove(context, cluster.id)
                            PeopleScanner.notifyPeopleChanged()
                            selected = null
                        },
                        shape = Pika.PillShape,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("Remove this person", color = Color(0xFFC13515), fontSize = 15.sp) }
                }
            }
        }
    }
}

@Composable
private fun PersonTile(name: String, role: String, count: Int, cropPNG: ByteArray?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pressScale(interaction)
                .shadow(6.dp, RoundedCornerShape(Pika.CardRadius), spotColor = Color.Black.copy(alpha = 0.12f))
                .clip(RoundedCornerShape(Pika.CardRadius))
                .background(Pika.Section)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            FaceFill(cropPNG)
            if (role != "neutral") {
                Text(
                    if (role == "required") "⭐" else "🚫",
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.White.copy(alpha = 0.92f), CircleShape)
                        .padding(4.dp),
                )
            }
        }
        Text(
            name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Pika.Ink,
            maxLines = 1, modifier = Modifier.padding(top = 6.dp),
        )
        Text("$count", style = Pika.Caption, fontSize = 11.sp)
    }
}

@Composable
private fun FaceFill(cropPNG: ByteArray?) {
    val bmp = remember(cropPNG) {
        cropPNG?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("🙂", fontSize = 28.sp) }
    }
}

@Composable
private fun FaceImage(cropPNG: ByteArray?, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Pika.Section)) { FaceFill(cropPNG) }
}

@Composable
private fun RoleChip(label: String, selectedNow: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 14.sp,
        fontWeight = if (selectedNow) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selectedNow) Color.White else Pika.Ink,
        modifier = Modifier
            .clip(Pika.PillShape)
            .background(if (selectedNow) Pika.Coral else Pika.Section)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
