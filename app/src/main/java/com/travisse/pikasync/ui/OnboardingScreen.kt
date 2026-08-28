package com.travisse.pikasync.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.travisse.pikasync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.travisse.pikasync.pipeline.PeopleScanner
import com.travisse.pikasync.pipeline.PeopleStore
import com.travisse.pikasync.pipeline.RunStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * First-run onboarding: welcome -> photo-permission priming -> people scan ->
 * name & star the family -> auto-create last month's book. Existing users
 * (people or books already present) are marked done silently and never see it.
 */
object Onboarding {
    private const val KEY = "onboardingDone"
    private fun prefs(context: Context) =
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun markDone(context: Context) = prefs(context).edit().putBoolean(KEY, true).apply()

    fun shouldShow(context: Context): Boolean {
        if (prefs(context).getBoolean(KEY, false)) return false
        val existing = PeopleStore.load(context).isNotEmpty() ||
            PeopleScanner.hasScanned(context) ||
            RunStore.load(context).isNotEmpty()
        if (existing) {
            markDone(context)  // grandfather existing installs
            return false
        }
        return true
    }
}

private enum class Step { TagIntro, Permission, Scan, Select, FirstBook }

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(Step.TagIntro) }

    // While the user lingers on the people grid, pre-score last month's photos
    // in the background so "Make my first book" starts with work already done.
    LaunchedEffect(step) {
        if (step == Step.Select) {
            withContext(Dispatchers.Default) {
                val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -1) }
                try {
                    com.travisse.pikasync.pipeline.PipelineRunner(context.applicationContext).prescoreMonth(
                        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                        shouldStop = { BookCreator.active?.running == true },
                    )
                } catch (_: Exception) { /* prescore is pure optimization */ }
            }
        }
    }

    fun finish() {
        Onboarding.markDone(context)
        onDone()
    }

    Box(Modifier.fillMaxSize().background(Pika.Bg).statusBarsPadding().navigationBarsPadding()) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { (slideInHorizontally { it / 8 } + fadeIn()) togetherWith fadeOut() },
            label = "onboarding",
        ) { s ->
            when (s) {
                Step.TagIntro -> TagIntroStep { step = Step.Permission }
                Step.Permission -> PermissionStep(
                    onGranted = { step = Step.Scan },
                    onContinuePartial = { step = Step.Scan },
                )
                Step.Scan -> ScanStep { step = Step.Select }
                Step.Select -> SelectPeopleStep(onNext = { step = Step.FirstBook })
                Step.FirstBook -> FirstBookStep(
                    onMake = {
                        startFirstBook(context)
                        finish()
                    },
                    onLater = { finish() },
                )
            }
        }
    }
}

// MARK: tag intro

@Composable
private fun TagIntroStep(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).background(Pika.Coral, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("📖", fontSize = 44.sp) }
        Spacer(Modifier.height(28.dp))
        Text("Tag who shows up most", style = Pika.Headline, fontSize = 30.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Pikabook turns each month of photos into a little book, built around your people. " +
                "First, let's find the faces that show up most.",
            style = Pika.Body,
            color = Pika.InkSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
        )
        Spacer(Modifier.height(40.dp))
        PillButton("Find my people", modifier = Modifier.fillMaxWidth().height(52.dp), onClick = onNext)
    }
}

// MARK: permission priming

@Composable
private fun PermissionStep(onGranted: () -> Unit, onContinuePartial: () -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(SyncEngine.photoAccess(context)) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        asked = true
        access = SyncEngine.photoAccess(context)
        if (SyncEngine.photoAccess(context) == SyncEngine.PhotoAccess.FULL) onGranted()
    }

    // Already granted from a previous partial run-through.
    LaunchedEffect(Unit) { if (access == SyncEngine.PhotoAccess.FULL) onGranted() }

    fun request() {
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
        launcher.launch(perms)
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔒", fontSize = 44.sp)
        Spacer(Modifier.height(20.dp))
        Text("Your photos stay on your phone", style = Pika.Title, fontSize = 24.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Pikabook looks through each month's photos right here on your device to pick the best moments. " +
                "Full access matters: it's how the good photos get found without you lifting a finger.",
            style = Pika.Body, color = Pika.InkSecondary, textAlign = TextAlign.Center, lineHeight = 23.sp,
        )
        Spacer(Modifier.height(32.dp))
        when {
            access == SyncEngine.PhotoAccess.PARTIAL -> {
                Text(
                    "You've shared selected photos. Books will only use those — full access makes far better books.",
                    style = Pika.Caption, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(16.dp))
                PillButton("Grant full access", modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    context.openAppSettings()
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onContinuePartial) {
                    Text("Continue with selected photos", color = Pika.InkSecondary, fontSize = 15.sp)
                }
            }
            asked && access == SyncEngine.PhotoAccess.DENIED -> {
                Text(
                    "Pikabook can't make books without photo access.",
                    style = Pika.Caption, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                PillButton("Open Settings", modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    context.openAppSettings()
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { request() }) {
                    Text("Try again", color = Pika.InkSecondary, fontSize = 15.sp)
                }
            }
            else -> PillButton("Allow photo access", modifier = Modifier.fillMaxWidth().height(52.dp)) { request() }
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

// MARK: people scan

@Composable
private fun ScanStep(onNext: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (!PeopleScanner.hasScanned(context)) {
            PeopleScanner.scan(context, limitCount = 200)  // fast first pass; deep rescan lives in People
            PeopleScanner.notifyPeopleChanged()
        }
        onNext()
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Finding your people", style = Pika.Title, fontSize = 24.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Scanning your 200 most recent photos for the faces that show up most. This stays on your phone.",
            style = Pika.Body, color = Pika.InkSecondary, textAlign = TextAlign.Center, lineHeight = 23.sp,
        )
        Spacer(Modifier.height(32.dp))
        LinearProgressIndicator(
            progress = { PeopleScanner.progress.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = Pika.Coral,
            trackColor = Pika.Hairline,
        )
        Spacer(Modifier.height(12.dp))
        Text(PeopleScanner.statusText.ifEmpty { "Warming up…" }, style = Pika.Caption)
    }
}

// MARK: select your people

@Composable
private fun SelectPeopleStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val version = PeopleScanner.peopleVersion
    val clusters = remember(version) {
        PeopleStore.load(context).sortedByDescending { it.count }.take(10)
    }
    // Top 3 most-photographed start selected; tap toggles.
    var selectedIds by remember(clusters.size) {
        mutableStateOf(clusters.take(3).map { it.id }.toSet())
    }

    // Nothing found (tiny library, partial access): don't strand the user here.
    LaunchedEffect(clusters.size) { if (clusters.isEmpty()) onNext() }

    fun applyAndFinish() {
        val all = PeopleStore.load(context)
        for (c in all) {
            val want = if (c.id in selectedIds) "required" else "neutral"
            if (c.role != want && c.role != "excluded") {
                c.role = want
                PeopleStore.update(context, c)
            }
        }
        PeopleScanner.notifyPeopleChanged()
        onNext()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Your books are about them", style = Pika.Title, fontSize = 24.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "We found who shows up most. Every photo in your books will include at least one selected person; tap to change who's in. Naming faces can wait; do it anytime in People.",
            style = Pika.Body, color = Pika.InkSecondary, lineHeight = 22.sp, fontSize = 15.sp,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 20.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(clusters, key = { it.id }) { c ->
                val isSelected = c.id in selectedIds
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        val bmp = remember(c.id) {
                            c.repCropPNG?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                        val ring = if (isSelected) {
                            Modifier.border(3.dp, Pika.Coral, CircleShape)
                        } else Modifier
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(), "person",
                                modifier = Modifier.fillMaxSize().clip(CircleShape).then(ring)
                                    .clickable {
                                        selectedIds = if (isSelected) selectedIds - c.id else selectedIds + c.id
                                    },
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                Modifier.fillMaxSize().clip(CircleShape).background(Pika.Section).then(ring)
                                    .clickable {
                                        selectedIds = if (isSelected) selectedIds - c.id else selectedIds + c.id
                                    }
                            )
                        }
                        if (isSelected) {
                            Text(
                                "⭐", fontSize = 16.sp,
                                modifier = Modifier.align(Alignment.TopEnd)
                                    .background(Color.White, CircleShape).padding(2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${c.count} photos",
                        style = Pika.Caption, fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Pika.Ink else Pika.InkSecondary,
                    )
                }
            }
        }
        PillButton("Continue", modifier = Modifier.fillMaxWidth().height(52.dp)) { applyAndFinish() }
        Spacer(Modifier.height(16.dp))
    }
}

// MARK: first book

@Composable
private fun FirstBookStep(onMake: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    val cal = remember { Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -1) } }
    val label = remember { SimpleDateFormat("MMMM", Locale.US).format(cal.time) }
    val count = remember {
        photoCountInMonth(context, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }
    val alreadyMade = remember {
        val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.US).format(cal.time)
        RunStore.load(context).any { it.monthLabel == monthLabel } || BookCreator.active?.running == true
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("📕", fontSize = 44.sp)
        Spacer(Modifier.height(20.dp))
        Text("Your first book", style = Pika.Headline, fontSize = 30.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        when {
            alreadyMade -> Text(
                "Good news: your $label book is already on its way. From here Pikabook makes one every month, automatically.",
                style = Pika.Body, color = Pika.InkSecondary, textAlign = TextAlign.Center, lineHeight = 24.sp,
            )
            count < 8 -> Text(
                "$label only has $count photos, so we'll skip it. Your first book will arrive automatically at the start of next month.",
                style = Pika.Body, color = Pika.InkSecondary, textAlign = TextAlign.Center, lineHeight = 24.sp,
            )
            else -> Text(
                "Let's turn your $label photos into your first book. It takes a couple of minutes; we'll notify you when it's ready.",
                style = Pika.Body, color = Pika.InkSecondary, textAlign = TextAlign.Center, lineHeight = 24.sp,
            )
        }
        Spacer(Modifier.height(40.dp))
        if (!alreadyMade && count >= 8) {
            PillButton("Make my first book", modifier = Modifier.fillMaxWidth().height(52.dp), onClick = onMake)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onLater) {
                Text("Maybe later", color = Pika.InkSecondary, fontSize = 15.sp)
            }
        } else {
            PillButton("Done", modifier = Modifier.fillMaxWidth().height(52.dp), onClick = onLater)
        }
    }
}

/**
 * Kick off last month's book — skipped for tiny months, and skipped when the
 * background AutoBook worker already made it (it can race onboarding).
 */
private fun startFirstBook(context: Context) {
    val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -1) }
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) + 1
    val label = SimpleDateFormat("MMMM yyyy", Locale.US).format(cal.time)
    val exists = RunStore.load(context).any { it.monthLabel == label } || BookCreator.active?.running == true
    if (!exists && photoCountInMonth(context, year, month) >= 8) {
        BookCreator.start(context, year, month, label)
    }
}

private fun photoCountInMonth(context: Context, year: Int, month: Int): Int {
    val start = Calendar.getInstance().apply {
        clear(); set(year, month - 1, 1)
    }
    val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
    val (s, e) = start.timeInMillis to end.timeInMillis
    var count = 0
    try {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "(${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?) OR " +
                "((${MediaStore.Images.Media.DATE_TAKEN} IS NULL OR ${MediaStore.Images.Media.DATE_TAKEN} = 0) AND " +
                "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?)",
            arrayOf("$s", "$e", "${s / 1000}", "${e / 1000}"),
            null,
        )?.use { c -> count = c.count }
    } catch (_: Exception) {
    }
    return count
}
