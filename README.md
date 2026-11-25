# BooruHub

An Android client for image boorus: **e621**, **Danbooru**, **Gelbooru** and **Rule34** in one app, alone or mixed into a single feed.

The interface is in Russian. *Интерфейс на русском — описание на русском ниже.*

> **18+.** These sites host adult content. BooruHub shows only what you ask the sites for, and a rating filter (Safe / Questionable / Explicit) is in the settings, but it is meant for adults.

## Features

- **Four sources, one feed.** Pick a site, or mix several: requests go out in parallel and the results are interleaved. Each search tab keeps its own source and mix.
- **One search language.** Type e621-style queries (`rating:s`, `order:score`, `-tag`, `~a ~b`, `type:webm`…); BooruHub translates them for each site.
- **Search tabs**, saved queries with **new-post notifications**, and search history.
- **Viewer** with pinch and double-tap zoom, videos with **double-tap seeking** on the sides (5/10/15 s), translation **notes**, **pools**, parent/child posts and comments.
- **Tag wiki**: long-press a tag to read what it means and see related tags.
- **Favourites** from your e621 and Danbooru accounts; likes kept in the app for Gelbooru and Rule34, whose APIs take none.
- **Collections** stored on the phone, **e621 sets**, and **batch actions**: long-press a tile and drag to pick many posts, then download, collect or favourite them.
- **Downloads** in the background, **blacklist** (synced with e621 if you like), **best of day/week/month** on e621 and Danbooru.
- **Backup** of settings, tabs, queries, collections and likes to a JSON file — API keys only if you ask.

## Install

Download the APK from [Releases](../../releases) and open it on the phone. Android 10 or newer, arm64.

Gelbooru and Rule34 answer only with an API key (free, from your account page on each site); enter it in **Настройки → Другие источники**. e621 and Danbooru work without one; signing in unlocks favourites and longer searches.

## Building

The app is Jetpack Compose over a Rust core (`crates/core`: the sites' APIs, query translation, blacklist, storage) loaded through JNI (`crates/android`).

You need:

- JDK 17
- Android SDK with platform 37 and NDK `29.0.14206865`
- Rust 1.88+ with the `aarch64-linux-android` target (`x86_64-linux-android` too for the emulator)

```sh
cd android
./gradlew assembleRelease              # phone (arm64)
./gradlew assembleRelease -Pemulator   # also x86_64, for the emulator
```

The APK lands in `android/app/build/outputs/apk/release/`. Without `android/keystore.properties` it is signed with the SDK's debug key — it runs, but will not install over the official releases.

Core tests: `cargo test -p booruhub-core`.

## License

[GPL-3.0-or-later](LICENSE), with one additional term under section 7(b): versions you distribute must keep the author attribution in the app's “О программе” (About) section. See [NOTICE](NOTICE).

BooruHub is not affiliated with e621, Danbooru, Gelbooru or Rule34.

---

## По-русски

Android-клиент для e621, Danbooru, Gelbooru и Rule34: каждый сайт отдельно или все в одной смешанной ленте.

- Один язык запросов в стиле e621 — приложение переводит его под каждый сайт.
- Вкладки поиска (у каждой свой источник), сохранённые запросы с уведомлениями о новых постах.
- Просмотр с зумом, видео с перемоткой двойным тапом по краям, ноты, пулы, родственные посты, комментарии, вики тегов по долгому нажатию.
- Избранное e621 и Danbooru из аккаунта, лайки для Gelbooru и Rule34 в приложении, коллекции, сеты e621.
- Массовый выбор долгим нажатием, загрузки, чёрный список, лучшее за день/неделю/месяц, резервная копия.

**Установка:** APK со страницы [Releases](../../releases). Для Gelbooru и Rule34 нужен API-ключ с сайта — вводится в Настройки → Другие источники.

**Лицензия:** GPL-3.0 с дополнительным условием: в распространяемых версиях должно сохраняться указание автора в разделе «О программе».
