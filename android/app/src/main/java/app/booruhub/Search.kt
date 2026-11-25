package app.booruhub

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** What sits before the typed text in the tag field; see SearchScreen. */
private const val MARK = "\u200B"

/** A filter card's choices: the one taken, and what taking another does. */
class Picker(val label: String, val options: List<String>, val selected: Int, val onPick: (Int) -> Unit) {
    val value get() = options.getOrElse(selected) { options.first() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(card: Picker, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = C.Surface,
        shape = S.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(card.label, style = T.card.copy(fontSize = 18.sp), modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
            card.options.forEachIndexed { i, option ->
                val on = i == card.selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) C.Violet.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { card.onPick(i); onClose() }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(option, style = T.body.copy(fontSize = 15.sp, color = if (on) C.Violet300 else C.Text, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), modifier = Modifier.weight(1f))
                    if (on) Ico(Icons.Check, C.Violet300, size = 18.dp)
                }
            }
        }
    }
}

/** One of the design's filter cards: a mono label, a value, a tap for the next value. */
class Filter(val label: String, val options: List<Pair<String, String>>) {
    var index by mutableStateOf(0)
    val token get() = options[index].first
}

/** Filters that become tags in the query. Rating and order live in [Hub]. */
fun searchFilters() = listOf(
    Filter("Дата", listOf("" to "За всё время", "date:week" to "За неделю", "date:month" to "За месяц", "date:year" to "За год")),
    Filter("Тип", listOf("" to "Любой", "-type:webm -type:mp4 -type:gif" to "Изображения", "type:webm" to "Видео", "type:gif" to "GIF")),
    Filter("Размер", listOf("" to "Любой", "width:>=1000" to "от 1000 px", "width:>=2000" to "от 2000 px")),
    Filter("Соотношение", listOf("" to "Любое", "ratio:<1" to "Вертикальные", "ratio:>1" to "Горизонтальные", "ratio:1" to "Квадратные")),
)

private val RATING_STEPS = listOf(
    setOf("s", "q", "e"), setOf("s"), setOf("q"), setOf("e"), setOf("s", "q"), setOf("s", "e"), setOf("q", "e"),
)

private fun ratingLabel(r: Set<String>) = listOf("s", "q", "e").filter { it in r }.joinToString(" · ") { it.uppercase() }

/**
 * The design's search: tags as chips in one box at the top, hints under it,
 * filter cards, and the big "Найти" in the bottom third.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(hub: Hub) {
    val tokens = hub.searchTokens
    // The field always starts with an invisible mark, so a backspace in an
    // empty field still arrives as an edit: it takes the last chip back into
    // the field, to be erased or changed like text.
    var field by remember { mutableStateOf(TextFieldValue(MARK, TextRange(1))) }
    val input = field.text.removePrefix(MARK)
    fun setInput(text: String) { field = TextFieldValue(MARK + text, TextRange(1 + text.length)) }
    var hints by remember { mutableStateOf<List<TagHint>>(emptyList()) }
    var picking by remember { mutableStateOf<Picker?>(null) }

    // Hints for what is being typed, after a short pause.
    val word = input.trim().removePrefix("-").removePrefix("~")
    LaunchedEffect(word) {
        hints = emptyList()
        if (word.length < 3 || ':' in word) return@LaunchedEffect
        delay(280)
        hints = runCatching { E621.autocomplete(hub.hintSite, word) }.getOrDefault(emptyList())
    }

    fun commit(text: String = input) {
        val t = text.trim().replace(' ', '_')
        if (t.isNotEmpty() && t.trim('-', '~').isNotEmpty() && t !in tokens) tokens.add(t)
        setInput("")
    }

    fun edit(v: TextFieldValue) {
        if (!v.text.startsWith(MARK)) {
            // The mark itself was erased: a backspace at the very start.
            if (v.text.isEmpty() && input.isEmpty() && tokens.isNotEmpty()) setInput(tokens.removeAt(tokens.lastIndex))
            else field = TextFieldValue(MARK + v.text, TextRange(1))
            return
        }
        val text = v.text.removePrefix(MARK)
        // A space ends a tag, as on the site.
        if (text.endsWith(" ")) { commit(text); return }
        field = v.copy(selection = TextRange(v.selection.start.coerceAtLeast(1), v.selection.end.coerceAtLeast(1)))
    }

    fun pick(name: String) {
        val prefix = input.trim().let { if (it.startsWith("-")) "-" else if (it.startsWith("~")) "~" else "" }
        commit(prefix + name)
    }

    fun query() = (tokens + hub.filters.map { it.token }).filter { it.isNotBlank() }.joinToString(" ")

    fun go() {
        commit()
        hub.search(query())
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Поиск", style = T.title.copy(fontSize = 22.sp), modifier = Modifier.weight(1f).padding(start = 2.dp))
                // The saved queries live under search, as in the design.
                Row(
                    Modifier
                        .clip(S.pill)
                        .background(C.Violet.copy(alpha = 0.14f))
                        .clickable { hub.page = Page.Saved }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Запросы", style = T.small.copy(fontSize = 12.5.sp, color = C.Violet300, fontWeight = FontWeight.SemiBold))
                    val fresh = hub.saved.filter { it.watch }.sumOf { it.fresh }
                    Text(if (fresh > 0) "+$fresh" else "${hub.saved.size}", style = T.mono.copy(fontSize = 11.sp, color = if (fresh > 0) C.Good else C.N500))
                }
            }

            // The tag box: chips for what is entered, a field for the next one.
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .clip(S.card)
                    .background(C.Surface)
                    .border(1.dp, C.Violet.copy(alpha = 0.28f), S.card)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (t in tokens.toList()) {
                    val minus = t.startsWith("-")
                    // "Any of" tags in the sources' blue, apart from the must-haves.
                    val color = if (minus) C.Bad else if (t.startsWith("~")) Color(0xFF7FC8E8) else C.Violet300
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background((if (minus) C.Bad else if (t.startsWith("~")) Color(0xFF7FC8E8) else C.Violet).copy(alpha = 0.16f))
                            .clickable { tokens.remove(t) }
                            .padding(start = 9.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(t.replace('_', ' '), style = T.small.copy(color = color, fontSize = 12.5.sp))
                        Ico(Icons.Close, color, size = 11.dp)
                    }
                }
                BasicTextField(
                    value = field,
                    onValueChange = ::edit,
                    singleLine = true,
                    textStyle = T.mono.copy(fontSize = 13.sp, color = C.Text),
                    cursorBrush = SolidColor(C.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { go() }),
                    modifier = Modifier.widthIn(min = 120.dp).padding(vertical = 5.dp),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) Text(
                                if (tokens.isEmpty()) "тег, автор, персонаж…" else "ещё тег…",
                                style = T.mono.copy(fontSize = 13.sp, color = C.N600),
                            )
                            inner()
                        }
                    },
                )
            }

            if (hints.isNotEmpty()) {
                Kicker("ПОДСКАЗКИ")
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (h in hints.take(6)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(C.Surface)
                                .clickable { pick(h.name) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(6.dp).clip(S.pill).background(tagColor(tagCategory(h.category))))
                            Text(h.name.replace('_', ' '), style = T.body.copy(fontSize = 13.5.sp), modifier = Modifier.weight(1f))
                            Text(count(h.count), style = T.mono.copy(fontSize = 11.sp, color = C.N500))
                        }
                    }
                }
            } else if (hub.recent.isNotEmpty() && input.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Kicker("НЕДАВНИЕ", Modifier.weight(1f))
                    Text("очистить", style = T.mono.copy(fontSize = 11.sp, color = C.N500), modifier = Modifier.clickable { hub.forgetRecent() }.padding(4.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (r in hub.recent.take(5)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(C.Surface)
                                .clickable { hub.search(r) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Ico(Icons.Search, C.N600, size = 14.dp)
                            Text(r, style = T.mono.copy(fontSize = 12.5.sp, color = C.N300), maxLines = 1, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Kicker("ФИЛЬТРЫ")
            // A tap opens the card's choices; nothing changes by an extra tap.
            val cards = buildList<Picker> {
                add(Picker("Рейтинг", RATING_STEPS.map(::ratingLabel), RATING_STEPS.indexOf(hub.ratings)) { hub.chooseRatings(RATING_STEPS[it]) })
                add(Picker("Порядок", ORDERS.map { it.second }, ORDERS.indexOfFirst { it.first == hub.main.order }) { hub.setOrder(hub.main, ORDERS[it].first) })
                for (f in hub.filters) add(Picker(f.label, f.options.map { it.second }, f.index) { f.index = it })
            }
            for (pair in cards.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (card in pair) {
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(C.Surface)
                                .clickable { picking = card }
                                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(card.label, style = T.mono.copy(fontSize = 10.5.sp, color = C.N500))
                                Text(card.value, style = T.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                            }
                            Ico(Icons.Down, C.N600, size = 16.dp)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // The bottom third: the button, then "Сбросить".
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, C.Bg)))
                .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                .then(
                    if (WindowInsets.isImeVisible) Modifier.padding(bottom = 10.dp)
                    else Modifier.abovePill(),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val n = tokens.size + if (input.isNotBlank()) 1 else 0
            val active = hub.filters.count { it.token.isNotEmpty() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(C.Glow)
                    .clickable { go() }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Найти", style = T.card.copy(fontSize = 15.sp, color = Color.White), modifier = Modifier.weight(1f))
                Text(
                    listOf(
                        if (n == 0) "все посты" else "$n ${plural(n, "тег", "тега", "тегов")}",
                        if (active > 0) "$active ${plural(active, "фильтр", "фильтра", "фильтров")}" else null,
                    ).filterNotNull().joinToString(" · "),
                    style = T.mono.copy(fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.8f)),
                )
            }
            var saving by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Сохранить запрос",
                    style = T.small.copy(fontSize = 12.sp, color = C.N500),
                    modifier = Modifier.clip(S.pill).clickable { commit(); saving = true }.padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text("·", style = T.small.copy(fontSize = 12.sp, color = C.N700))
                Text(
                    "Сбросить",
                    style = T.small.copy(fontSize = 12.sp, color = C.N500),
                    modifier = Modifier
                        .clip(S.pill)
                        .clickable {
                            tokens.clear(); setInput("")
                            hub.filters.forEach { it.index = 0 }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (saving) SaveQueryDialog(hub, query()) { saving = false }
            picking?.let { card -> PickerSheet(card) { picking = null } }
        }
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

private fun count(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(" ").reversed()

