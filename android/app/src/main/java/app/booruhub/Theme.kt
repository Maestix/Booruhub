package app.booruhub

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * bliss's palette, with the original design's division of roles: violet for
 * structure (tabs, selection, navigation), pink for affection (likes, the main
 * button, the wordmark).
 */
object C {
    val Bg = Color(0xFF140F1D)
    val Surface = Color(0xFF1E1629)
    val Raised = Color(0xFF251C31)
    val Sunk = Color(0xFF0F0A16)
    val Text = Color(0xFFEFE8F5)
    val Accent = Color(0xFFEE82C4)
    val Violet = Color(0xFFA98CE8)
    val Violet300 = Color(0xFFCBBAF4)
    val Violet600 = Color(0xFF8A6DCA)
    val Good = Color(0xFF72CF9E)
    val Bad = Color(0xFFE56B6F)
    val Sky = Color(0xFF7EB3EE)
    val Amber = Color(0xFFE8C07A)
    val N300 = Color(0xFFD3C9DD)
    val N400 = Color(0xFFB5A9C4)
    val N500 = Color(0xFF9588A6)
    val N600 = Color(0xFF766A88)
    val N700 = Color(0xFF5A506A)
    val Hair = Color(0x1AEFE8F5)
    val Tint = Color(0x0FEFE8F5)

    val Glow = Brush.linearGradient(listOf(Accent, Violet))
}

/** The cute voice: the wordmark and screen titles only. */
val Comfortaa = FontFamily(
    Font(R.font.comfortaa_semibold, FontWeight.SemiBold),
    Font(R.font.comfortaa_bold, FontWeight.Bold),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w ->
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** The original design's text face: plain and legible at small sizes. */
val Manrope = variable(R.font.manrope, 400, 500, 600, 700, 800)

/** Numbers and file facts, as the original design sets them. */
val Mono = variable(R.font.jetbrains_mono, 400, 500)

object T {
    val body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = C.Text)
    val small = body.copy(fontSize = 13.sp, color = C.N400)
    val tiny = body.copy(fontSize = 11.5.sp, color = C.N500)
    val title = TextStyle(fontFamily = Comfortaa, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = C.Text)
    val card = body.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp)
    val mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = C.N400)
}

object S {
    val card = RoundedCornerShape(16.dp)
    val tile = RoundedCornerShape(10.dp)
    val pill = RoundedCornerShape(50)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

fun ratingColor(r: String) = when (r) {
    "s" -> C.Good
    "q" -> C.Violet
    else -> C.Accent
}

fun tagColor(category: String) = when (category) {
    "artist" -> C.Accent
    "copyright" -> C.Violet
    "character" -> C.Good
    "species" -> C.Sky
    "lore" -> C.Amber
    "meta" -> C.N500
    "invalid" -> C.Bad
    else -> C.N300
}

/** e621's autocomplete gives categories as numbers. */
fun tagCategory(n: Int) = when (n) {
    1 -> "artist"; 3 -> "copyright"; 4 -> "character"; 5 -> "species"
    6 -> "invalid"; 7 -> "meta"; 8 -> "lore"; else -> "general"
}

fun tagCategoryTitle(category: String) = when (category) {
    "artist" -> "АВТОРЫ"
    "copyright" -> "ФРАНШИЗЫ"
    "character" -> "ПЕРСОНАЖИ"
    "species" -> "ВИДЫ"
    "general" -> "ОБЩИЕ"
    "lore" -> "ЛОР"
    "meta" -> "МЕТА"
    else -> "ПРОЧЕЕ"
}

@Composable
fun HubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = C.Bg, surface = C.Surface, primary = C.Accent, secondary = C.Violet,
            onSurface = C.Text, onBackground = C.Text, onPrimary = C.Bg,
            surfaceContainerLow = C.Surface, surfaceContainer = C.Surface, surfaceContainerHigh = C.Raised,
        ),
        content = content,
    )
}
