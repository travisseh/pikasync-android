package com.travisse.pikasync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import com.travisse.pikasync.pipeline.RunStore
import com.travisse.pikasync.pipeline.SavedRun
import com.travisse.pikasync.ui.BookScreen
import com.travisse.pikasync.ui.BooksScreen
import com.travisse.pikasync.ui.PikaTheme
import com.travisse.pikasync.ui.PipelineScreen
import com.travisse.pikasync.ui.SyncScreen

private sealed interface Screen {
    data object Books : Screen
    data object Create : Screen
    data class Book(val run: SavedRun) : Screen
    data object Sync : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // arm wake paths on every foreground launch
        SyncEngine.scheduleContentJob(this)
        SyncEngine.schedulePeriodicWork(this)
        setContent { PikaTheme { Root() } }
    }
}

@Composable
private fun Root() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Books) }
    var runs by remember { mutableStateOf(RunStore.load(context)) }

    fun home() {
        runs = RunStore.load(context)
        screen = Screen.Books
    }

    BackHandler(enabled = screen != Screen.Books) { home() }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val springSpec = spring<IntOffset>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
            (slideInVertically(springSpec) { it / 12 } + fadeIn()) togetherWith fadeOut()
        },
        label = "nav",
    ) { s ->
        when (s) {
            Screen.Books -> BooksScreen(
                runs = runs,
                onOpenBook = { screen = Screen.Book(it) },
                onCreate = { screen = Screen.Create },
                onOpenSync = { screen = Screen.Sync },
            )
            Screen.Create -> PipelineScreen(
                onOpenBook = { screen = Screen.Book(it) },
                onClose = { home() },
            )
            is Screen.Book -> BookScreen(
                run = s.run,
                onDelete = {
                    RunStore.delete(context, s.run.id)
                    home()
                },
                onClose = { home() },
            )
            Screen.Sync -> SyncScreen(onClose = { home() })
        }
    }
}
