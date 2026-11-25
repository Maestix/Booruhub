package app.booruhub

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The design's settings: groups of rows, a label on the left and the value in
 * mono on the right. Signing in to e621 is the first group.
 */
@Composable
fun AccountScreen(hub: Hub) {
    val ctx = LocalContext.current
    var cache by remember { mutableLongStateOf(-1L) }
    var cacheTick by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(cacheTick) {
        cache = withContext(Dispatchers.IO) {
            runCatching { SingletonImageLoader.get(ctx).diskCache?.size ?: 0L }.getOrDefault(0L)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PageHeader("Настройки", onBack = { hub.page = null })

        val user = hub.user
        Group("E621") {
            if (user != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(C.Glow),
                        contentAlignment = Alignment.Center,
                    ) { Text(user.take(1).uppercase(), style = T.card.copy(color = Color.White)) }
                    Column(Modifier.weight(1f)) {
                        Text(user, style = T.card.copy(fontSize = 14.5.sp))
                        Text("e621 · API-ключ", style = T.mono.copy(fontSize = 11.sp, color = C.N500))
                    }
                }
                SettingRow("Выйти", "", labelColor = C.Bad) { hub.logout() }
            } else {
                LoginForm(hub)
            }
        }

        Group("ДРУГИЕ ИСТОЧНИКИ") {
            listOf(Site.Danbooru, Site.Gelbooru, Site.Rule34).forEachIndexed { i, s -> SiteLogin(hub, s, hairline = i > 0) }
        }

        Group("ЛЕНТА") {
            listOf("s" to "Safe", "q" to "Questionable", "e" to "Explicit").forEachIndexed { i, (r, label) ->
                ToggleRow(label, r in hub.ratings, hairline = i > 0) { hub.setRating(r, it) }
            }
            SettingRow("Столбцов в ленте", "${hub.columns}", valueColor = C.Violet300) {
                hub.chooseColumns(if (hub.columns == 3) 2 else 3)
            }
            SettingRow("Форма плиток", if (hub.squareTiles) "Квадратные" else "По соотношению", valueColor = C.Violet300) {
                hub.chooseSquare(!hub.squareTiles)
            }
            SettingRow("Перемотка двойным тапом", "${hub.seekStep} с", valueColor = C.Violet300) {
                hub.chooseSeekStep(when (hub.seekStep) { 5 -> 10; 10 -> 15; else -> 5 })
            }
        }

        Group("ИЗОБРАЖЕНИЯ И СЕТЬ") {
            ToggleRow("Оригиналы в просмотре", hub.original, hairline = false) { hub.toggleOriginal(it) }
            SettingRow("Кэш изображений", if (cache < 0) "…" else bytes(cache))
            SettingRow("Очистить кэш", if (cache > 0) "${bytes(cache)} занято" else "пусто", valueColor = C.Bad) {
                val loader = SingletonImageLoader.get(ctx)
                loader.memoryCache?.clear()
                // Thousands of files: delete them off the main thread.
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { loader.diskCache?.clear() } }
                    cacheTick++
                    hub.say("Кэш очищен")
                }
            }
            SettingRow("Чёрный список", "${hub.blacklist.size} ${plural(hub.blacklist.size, "правило", "правила", "правил")}", valueColor = C.Violet300) {
                hub.page = Page.Blacklist
            }
            SettingRow(
                "Проверка Cloudflare",
                if (WebCookies.has(E621.BASE, "cf_clearance")) "пропуск есть" else "пройти",
                valueColor = C.Violet300,
            ) { hub.openCloudflare() }
        }

        Group("УВЕДОМЛЕНИЯ") {
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) hub.chooseNotify(true) else hub.say("Без разрешения уведомлений не будет")
            }
            ToggleRow("Новые посты по запросам", hub.notify, hairline = false) { on ->
                if (on && !Watch.allowed(ctx) && android.os.Build.VERSION.SDK_INT >= 33) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else hub.chooseNotify(on)
            }
            Text(
                "Раз в час приложение проверяет сохранённые запросы со слежкой и присылает уведомление, если появилось новое.",
                style = T.small.copy(fontSize = 12.sp, color = C.N500),
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }

        Group("РЕЗЕРВНАЯ КОПИЯ") {
            var keys by remember { mutableStateOf(false) }
            var restoring by remember { mutableStateOf<android.net.Uri?>(null) }
            val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) scope.launch {
                    runCatching { Backup.write(ctx, uri, keys) }
                        .onSuccess { hub.say("Копия сохранена") }
                        .onFailure { hub.say("Не сохранилось: ${it.message}") }
                }
            }
            val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoring = uri }
            ToggleRow("Вместе с API-ключами", keys, hairline = false) { keys = it }
            SettingRow("Сохранить в файл", "", labelColor = C.Violet300) {
                save.launch("booruhub-${java.time.LocalDate.now()}.json")
            }
            SettingRow("Восстановить из файла", "", labelColor = C.Violet300) {
                open.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            }
            restoring?.let { uri ->
                ConfirmDialog(
                    "Восстановить копию?",
                    "Настройки, вкладки, чёрный список, запросы и коллекции заменятся теми, что в файле. Приложение перезапустится.",
                    "Восстановить", { restoring = null },
                ) {
                    restoring = null
                    scope.launch {
                        runCatching { Backup.read(ctx, uri) }
                            .onSuccess { Backup.restart(ctx) }
                            .onFailure { hub.say(it.message ?: "Не вышло") }
                    }
                }
            }
        }

        Group("О ПРОГРАММЕ") {
            SettingRow("Версия", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", hairline = false)
            SettingRow("Источники", Site.entries.joinToString(" · ") { it.title }, valueColor = C.Violet300)
            // GPL-3.0 section 7(b): versions passed on keep this line (see NOTICE).
            SettingRow("Автор", "Maestix", valueColor = C.Violet300)
            SettingRow("Лицензия", "GPL-3.0", valueColor = C.Violet300)
            SettingRow("Исходный код", "GitHub", valueColor = C.Violet300) {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Maestix/Booruhub"))) }
            }
        }

        Spacer(Modifier.height(pillClearance(16.dp)))
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker(title, Modifier.padding(start = 2.dp))
        Column(Modifier.fillMaxWidth().card(), content = content)
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    valueColor: Color = C.N400,
    labelColor: Color = C.Text,
    hairline: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    if (hairline) Hairline()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = T.body.copy(fontSize = 14.sp, color = labelColor), modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, style = T.mono.copy(fontSize = 12.sp, color = valueColor))
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, hairline: Boolean = true, onChange: (Boolean) -> Unit) {
    if (hairline) Hairline()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!on) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = T.body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
        Toggle(on, onChange)
    }
}

@Composable
private fun LoginForm(hub: Hub) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "e621 пускает приложения по имени и API-ключу, не по паролю. Ключ — на сайте: Account → Manage API Access.",
            style = T.small.copy(fontSize = 12.5.sp),
        )
        Field(hub.draftUser, { hub.draftUser = it }, "Имя пользователя", Modifier.fillMaxWidth(), ime = ImeAction.Next, leading = Icons.User)
        Field(
            hub.draftKey, { hub.draftKey = it }, "API-ключ", Modifier.fillMaxWidth(),
            secret = true, ime = ImeAction.Done, onIme = { hub.login() }, leading = Icons.Key,
        )
        hub.loginError?.let { Text(it, style = T.small.copy(color = C.Bad)) }
        if (hub.signingIn) {
            Box(Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = C.Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
            }
        } else {
            PrimaryButton("Войти", { hub.login() }, Modifier.fillMaxWidth())
        }
        GhostButton("Найти ключ на сайте", { hub.openKeyPage() }, Modifier.fillMaxWidth(), color = C.Violet, icon = Icons.Key)
    }
}
