package app.booruhub

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- icons: one stroke width, drawn like bliss's ---------------------------------

private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0"

private fun icon(name: String, vararg paths: String, stroke: Float = 1.7f, fill: Boolean = false): ImageVector {
    val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    for (d in paths) {
        builder.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = if (fill) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

object Icons {
    private const val HEART = "M12 20.4 C10.4 19 4.2 14.9 4.2 10.4 A4.4 4.4 0 0 1 12 7.8 A4.4 4.4 0 0 1 19.8 10.4 C19.8 14.9 13.6 19 12 20.4 Z"
    val Heart = icon("heart", HEART)
    val HeartFilled = icon("heartFilled", HEART, fill = true)
    val Grid = icon("grid", "M4.6 4.6 H10.4 V10.4 H4.6 Z", "M13.6 4.6 H19.4 V10.4 H13.6 Z", "M4.6 13.6 H10.4 V19.4 H4.6 Z", "M13.6 13.6 H19.4 V19.4 H13.6 Z")
    val Search = icon("search", circle(10.8f, 10.8f, 6.2f), "M15.4 15.4 20 20", stroke = 1.8f)
    val Gear = icon("gear", circle(12f, 12f, 3.2f), circle(12f, 12f, 8.4f), "M12 3.6 V6.2 M12 17.8 V20.4 M3.6 12 H6.2 M17.8 12 H20.4")
    val User = icon("user", circle(12f, 8.6f, 3.8f), "M4.8 20.2 c0.8-4 3.8-6 7.2-6 s6.4 2 7.2 6")
    val Down = icon("down", "M6.6 9.2 12 14.6 17.4 9.2")
    val Right = icon("right", "M9.2 6.6 14.6 12 9.2 17.4")
    val Check = icon("check", "M5.4 12.6 9.8 17 18.6 7.6", stroke = 1.8f)
    val Folder = icon("folder", "M3.8 7.2 a1.6 1.6 0 0 1 1.6-1.6 H9.6 L11.6 7.8 H18.6 a1.6 1.6 0 0 1 1.6 1.6 V17.2 a1.6 1.6 0 0 1-1.6 1.6 H5.4 a1.6 1.6 0 0 1-1.6-1.6 Z", "M12 10.6 V16 M9.3 13.3 H14.7")
    val Back = icon("back", "M14.8 6.6 9.4 12 14.8 17.4")
    val Close = icon("close", "M6.4 6.4 L17.6 17.6 M17.6 6.4 L6.4 17.6")
    val Download = icon("download", "M12 4.4 V15", "M7.6 10.8 12 15.2 16.4 10.8", "M5 19.6 H19")
    val External = icon("external", "M13.6 4.4 H19.6 V10.4", "M19.6 4.4 11.6 12.4", "M17.6 13.6 V18.4 a1.2 1.2 0 0 1-1.2 1.2 H5.6 a1.2 1.2 0 0 1-1.2-1.2 V7.6 a1.2 1.2 0 0 1 1.2-1.2 H10.4")
    val Refresh = icon("refresh", "M20 12 A8 8 0 1 1 17.4 6.1", "M20.4 3.4 V7.4 H16.4")
    val Play = icon("play", "M8.4 5.6 18.4 12 8.4 18.4 Z", fill = true)
    val Shield = icon("shield", "M12 2.6 20 5.6 V12 c0 5-3.6 8-8 9.4 C8 20 4.4 17 4.4 12 V5.6 Z", "M8.6 11.8 11.2 14.4 15.6 9.6")
    val Key = icon("key", circle(8f, 15.6f, 3.8f), "M10.8 12.8 19.6 4", "M16.4 7.2 18.8 9.6", "M14.2 9.4 16 11.2")
    val Share = icon("share", circle(17.4f, 5.8f, 2.4f), circle(6.6f, 12f, 2.4f), circle(17.4f, 18.2f, 2.4f), "M8.7 10.8 15.3 7 M8.7 13.2 15.3 17")
    val Comment = icon("comment", "M5.2 5.4 H18.8 a1.6 1.6 0 0 1 1.6 1.6 V14.6 a1.6 1.6 0 0 1-1.6 1.6 H10.4 L6.4 19.6 V16.2 H5.2 a1.6 1.6 0 0 1-1.6-1.6 V7 a1.6 1.6 0 0 1 1.6-1.6 Z")
    val Plus = icon("plus", "M12 5.4 V18.6 M5.4 12 H18.6", stroke = 1.8f)
}

@Composable
fun Ico(icon: ImageVector, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))

// --- surfaces ----------------------------------------------------------------------

fun Modifier.card(): Modifier = this
    .clip(S.card)
    .background(C.Surface)
    .border(1.dp, C.Tint, S.card)

/** "Booru" white, "Hub" in the pink-to-violet glow. */
@Composable
fun Wordmark(size: Int = 22) {
    Row {
        Text("Booru", style = T.title.copy(fontSize = size.sp))
        Text("Hub", style = T.title.copy(fontSize = size.sp, brush = C.Glow))
    }
}

// --- controls -------------------------------------------------------------------------

@Composable
fun RoundButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = C.Text,
    bg: Color = Color(0xCC251C31),
    size: Dp = 48.dp,
) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressable(src, 0.86f)
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, C.Hair, CircleShape)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Ico(icon, tint, size = size * 0.46f) }
}

/** The big button: the glow, white text. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(18.dp)
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressable(src, 0.96f)
            .height(52.dp)
            .clip(shape)
            .background(if (enabled) C.Glow else SolidColor(C.N700))
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = T.card.copy(color = Color.White, fontSize = 15.sp)) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = C.Accent, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(16.dp)
    val src = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressable(src, 0.95f)
            .height(46.dp)
            .clip(shape)
            .border(1.dp, color.copy(alpha = 0.4f), shape)
            .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Ico(icon, color, size = 18.dp)
        Text(text, style = T.body.copy(color = color, fontSize = 14.sp))
    }
}

@Composable
fun Chip(text: String, on: Boolean, color: Color = C.Violet, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) color.copy(alpha = 0.16f) else C.Tint, tween(200), label = "chip")
    Box(
        Modifier
            .height(36.dp)
            .clip(S.pill)
            .background(bg)
            .border(1.dp, if (on) color.copy(alpha = 0.4f) else Color.Transparent, S.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = T.body.copy(fontSize = 13.5.sp, color = if (on) color else C.N400), maxLines = 1, softWrap = false) }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    // One switch for the whole app: a solid violet track and a white knob when
    // on, so the state reads at a glance; a muted track and a grey knob when off.
    val knob by animateDpAsState(if (on) 22.dp else 4.dp, spring(dampingRatio = 0.7f), label = "knob")
    val track by animateColorAsState(if (on) C.Violet600 else C.N700, tween(180), label = "track")
    val dot by animateColorAsState(if (on) Color.White else C.N400, tween(180), label = "dot")
    Box(
        Modifier
            .width(48.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(track)
            .clickable(role = Role.Switch) { onChange(!on) },
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .align(Alignment.CenterStart)
                .size(22.dp)
                .clip(CircleShape)
                .background(dot),
        )
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    ime: ImeAction = ImeAction.Done,
    onIme: () -> Unit = {},
    leading: ImageVector? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = T.body.copy(fontSize = 15.sp),
        cursorBrush = SolidColor(C.Accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            imeAction = ime,
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(52.dp)
                    .clip(shape)
                    .background(C.Sunk)
                    .border(1.dp, C.Hair, shape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (leading != null) Ico(leading, C.N500, size = 20.dp)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = T.body.copy(color = C.N600))
                    inner()
                }
            }
        },
    )
}

/** A small coloured badge on a tile: rating, kind. */
@Composable
fun Badge(text: String?, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    // One height for every badge, whatever is inside: a letter, a word or an icon.
    Box(
        modifier
            .height(18.dp)
            .widthIn(min = 18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xB3140F1D))
            .padding(horizontal = if (icon != null) 0.dp else 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Ico(icon, color, size = 9.dp)
        else Text(text.orEmpty(), style = T.mono.copy(color = color, fontSize = 9.5.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold))
    }
}


/** A thin line between rows. */
@Composable
fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0AFFFFFF)))

/** The small mono section label above a group. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) =
    Text(text, style = T.mono.copy(fontSize = 11.sp, color = C.N500, letterSpacing = 0.5.sp, fontWeight = FontWeight.SemiBold), modifier = modifier)

/** The back arrow every inner screen starts with. */
@Composable
fun BackButton(onBack: () -> Unit) {
    Box(Modifier.size(36.dp).clip(S.pill).springClick(0.85f, onClick = onBack), contentAlignment = Alignment.Center) {
        Ico(Icons.Back, C.N400, size = 20.dp)
    }
}

/** Gives way under the finger: a little smaller while pressed, springing back after. */
@Composable
fun Modifier.pressable(source: MutableInteractionSource, by: Float = 0.9f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) by else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** A tap that springs: [pressable] and the ripple on one source. */
@Composable
fun Modifier.springClick(by: Float = 0.9f, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return pressable(src, by).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
}

/** The last non-null value, so content can keep drawing while it animates out. */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { androidx.compose.runtime.mutableStateOf(value) }
    if (value != null) last.value = value
    return last.value
}

fun bytes(n: Long): String = when {
    n >= 1024L * 1024 * 1024 -> "%.1f GB".format(n / 1073741824f)
    n >= 1024 * 1024 -> "%.1f MB".format(n / 1048576f)
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n B"
}
