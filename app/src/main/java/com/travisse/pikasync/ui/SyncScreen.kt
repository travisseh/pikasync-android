package com.travisse.pikasync.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.travisse.pikasync.SyncEngine
import com.travisse.pikasync.WakeEvent
import com.travisse.pikasync.WakeLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Dev/diagnostics surface: setup + wake log. Light restyle only (DESIGN.md). */
@Composable
fun SyncScreen(onClose: () -> Unit = {}) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(SyncEngine.photoAccess(context)) }
    var events by remember { mutableStateOf(WakeLog.load(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        access = SyncEngine.photoAccess(context)
        events = WakeLog.load(context)
    }

    fun requestPhotoAccess() {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Pika.Bg).statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = Pika.Ink)
                }
                Text("Diagnostics", style = Pika.Title)
            }
            Text(
                "Developer surface: background wake history and sync internals. Nothing here affects your books.",
                style = Pika.Caption,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        item {
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Photos access")
                Spacer(Modifier.width(8.dp))
                Text(
                    when (access) {
                        SyncEngine.PhotoAccess.FULL -> "Full"
                        SyncEngine.PhotoAccess.PARTIAL -> "Partial (Android 14 selected photos)"
                        SyncEngine.PhotoAccess.DENIED -> "Not granted"
                    },
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (access != SyncEngine.PhotoAccess.FULL) {
                OutlinedButton(onClick = { requestPhotoAccess() }) { Text("Request photo access") }
            }
            Button(onClick = {
                thread {
                    SyncEngine.runSync(context, "foreground")
                    events = WakeLog.load(context)
                }
            }) { Text("Sync now (foreground)") }
            Text(
                "Wake beacon: ntfy.sh/${WakeLog.NTFY_TOPIC}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "Arms: JobScheduler content trigger, WorkManager 15 min, FCM (stub)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            OutlinedButton(onClick = { events = WakeLog.load(context) }) { Text("Refresh log") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Wake log (${events.size})", style = MaterialTheme.typography.titleMedium)
            if (events.isEmpty()) {
                Text("No wakes recorded yet", color = MaterialTheme.colorScheme.secondary)
            }
        }
        items(events) { e -> WakeRow(e) }
    }
}

@Composable
private fun WakeRow(e: WakeEvent) {
    val fmt = remember { SimpleDateFormat("MMM d HH:mm:ss", Locale.US) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(e.trigger, fontWeight = FontWeight.Bold)
            Text(fmt.format(Date(e.timestamp)), color = MaterialTheme.colorScheme.secondary)
        }
        Text(
            "new: ${e.newPhotos}  total: ${e.totalPhotos}" + if (e.note.isNotEmpty()) "  ${e.note}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}
