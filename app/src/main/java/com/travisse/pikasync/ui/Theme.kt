package com.travisse.pikasync.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pika design tokens — kept in sync with pikasync-poc/DESIGN.md (cross-platform
 * source of truth shared with the iOS app). Airbnb-style: white, image-forward,
 * ink text, one warm coral accent, 12-16dp radii, soft shadows, springs.
 */
object Pika {
    val Bg = Color(0xFFFFFFFF)
    val Section = Color(0xFFF7F7F7)
    val Ink = Color(0xFF222222)
    val InkSecondary = Color(0xFF717171)
    val Coral = Color(0xFFFF385C)      // DESIGN.md `accent`
    val CoralPressed = Color(0xFFE31C5F)
    val Hairline = Color(0xFFEBEBEB)
    val Scrim = Color(0x8C000000)      // 55% black, gradient end

    val CardRadius = 16.dp
    val SheetRadius = 20.dp
    val PillRadius = 24.dp
    val ChipRadius = 12.dp

    val CardShape = RoundedCornerShape(CardRadius)
    val SheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val PillShape = RoundedCornerShape(PillRadius)

    val Headline = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink, letterSpacing = (-0.5).sp)
    val Title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    val Body = TextStyle(fontSize = 16.sp, color = Ink)
    val Caption = TextStyle(fontSize = 13.sp, color = InkSecondary)
}

private val PikaColors = lightColorScheme(
    primary = Pika.Coral,
    onPrimary = Color.White,
    background = Pika.Bg,
    onBackground = Pika.Ink,
    surface = Pika.Bg,
    onSurface = Pika.Ink,
    surfaceVariant = Pika.Section,
    onSurfaceVariant = Pika.InkSecondary,
    secondary = Pika.InkSecondary,
    outline = Pika.Hairline,
    error = Color(0xFFC13515),
)

@Composable
fun PikaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PikaColors, content = content)
}

/** Airbnb-style press feedback: card scales down slightly on touch with a spring. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Primary pill button: coral, white text, generous padding. */
@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Pika.PillShape,
        colors = ButtonDefaults.buttonColors(containerColor = Pika.Coral, contentColor = Color.White),
        interactionSource = interaction,
        modifier = modifier.pressScale(interaction),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
