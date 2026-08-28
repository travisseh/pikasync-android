package com.travisse.pikasync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.travisse.pikasync.pipeline.RunStore
import com.travisse.pikasync.pipeline.SavedRun
import com.travisse.pikasync.ui.BookCreator
import com.travisse.pikasync.ui.BookScreen
import com.travisse.pikasync.ui.BooksScreen
import com.travisse.pikasync.ui.CreateBookSheet
import com.travisse.pikasync.ui.Pika
import com.travisse.pikasync.ui.PikaTheme
import com.travisse.pikasync.ui.SyncScreen

private enum class Tab { Books, People, Sync }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Analytics.setup(this)
        enableEdgeToEdge()
        // arm wake paths on every foreground launch; retry any failed auto-shares
        SyncEngine.scheduleContentJob(this)
        SyncEngine.schedulePeriodicWork(this)
        BookCreator.retryNeedsShare(this)
        setContent { PikaTheme { Root() } }
    }
}

@Composable
private fun Root() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showOnboarding by remember {
        mutableStateOf(com.travisse.pikasync.ui.Onboarding.shouldShow(context))
    }
    if (showOnboarding) {
        com.travisse.pikasync.ui.OnboardingScreen(onDone = { showOnboarding = false })
        return
    }
    var tab by remember { mutableStateOf(Tab.Books) }
    var openBook by remember { mutableStateOf<SavedRun?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    val runsVersion = BookCreator.runsVersion
    val runs = remember(runsVersion) { RunStore.load(context) }

    // Keep the opened book in sync with store updates (share info lands async).
    LaunchedEffect(runsVersion) {
        openBook?.let { open -> openBook = RunStore.load(context).firstOrNull { it.id == open.id } ?: open }
    }

    val book = openBook
    if (book != null) {
        BackHandler { openBook = null }
        BookScreen(
            run = book,
            onDelete = {
                RunStore.delete(context, book.id)
                BookCreator.notifyRunsChanged()
                openBook = null
            },
            onClose = { openBook = null },
        )
        return
    }

    Scaffold(
        containerColor = Pika.Bg,
        bottomBar = {
            NavigationBar(containerColor = Pika.Bg, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = tab == Tab.Books,
                    onClick = { tab = Tab.Books },
                    icon = { Icon(Icons.Outlined.Home, "Books") },
                    label = { Text("Books") },
                    colors = navColors(),
                )
                NavigationBarItem(
                    selected = tab == Tab.People,
                    onClick = { tab = Tab.People },
                    icon = { Icon(Icons.Outlined.Person, "People") },
                    label = { Text("People") },
                    colors = navColors(),
                )
                NavigationBarItem(
                    selected = tab == Tab.Sync,
                    onClick = { tab = Tab.Sync },
                    icon = { Icon(Icons.Outlined.Settings, "Settings") },
                    label = { Text("Settings") },
                    colors = navColors(),
                )
            }
        },
        floatingActionButton = {
            if (tab == Tab.Books) {
                FloatingActionButton(
                    onClick = { showCreate = true },
                    containerColor = Pika.Coral,
                    contentColor = Color.White,
                    shape = androidx.compose.foundation.shape.CircleShape,
                ) { Icon(Icons.Filled.Add, "Create photobook") }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val springSpec = spring<IntOffset>(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                    (slideInVertically(springSpec) { it / 16 } + fadeIn()) togetherWith fadeOut()
                },
                label = "tab",
            ) { t ->
                when (t) {
                    Tab.Books -> BooksScreen(
                        runs = runs,
                        creatorJob = BookCreator.active,
                        onOpenBook = { openBook = it },
                        onCreate = { showCreate = true },
                    )
                    Tab.People -> com.travisse.pikasync.ui.PeopleScreen()
                    Tab.Sync -> SyncScreen(onClose = { tab = Tab.Books })
                }
            }
        }
    }

    if (showCreate) {
        CreateBookSheet(
            onDismiss = { showCreate = false },
            onCreate = { year, month, label ->
                showCreate = false
                BookCreator.start(context, year, month, label)
            },
        )
    }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Pika.Coral,
    selectedTextColor = Pika.Coral,
    indicatorColor = Pika.Coral.copy(alpha = 0.10f),
    unselectedIconColor = Pika.InkSecondary,
    unselectedTextColor = Pika.InkSecondary,
)
