package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.servers.webdav.WebDavMetadata
import com.nuvio.app.features.servers.webdav.WebDavTitleMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.subtitle_extra_ai
import nuvio.composeapp.generated.resources.subtitle_extra_load_file
import nuvio.composeapp.generated.resources.subtitle_extra_save
import nuvio.composeapp.generated.resources.subtitle_save_cancel
import nuvio.composeapp.generated.resources.subtitle_save_original
import nuvio.composeapp.generated.resources.subtitle_save_with_sync
import nuvio.composeapp.generated.resources.subtitle_extra_search_online
import nuvio.composeapp.generated.resources.subtitle_search_episode
import nuvio.composeapp.generated.resources.subtitle_search_failed
import nuvio.composeapp.generated.resources.subtitle_search_find
import nuvio.composeapp.generated.resources.subtitle_search_hint
import nuvio.composeapp.generated.resources.subtitle_search_movie
import nuvio.composeapp.generated.resources.subtitle_search_no_subtitles
import nuvio.composeapp.generated.resources.subtitle_search_no_titles
import nuvio.composeapp.generated.resources.subtitle_search_season
import nuvio.composeapp.generated.resources.subtitle_search_series
import nuvio.composeapp.generated.resources.subtitle_search_title
import org.jetbrains.compose.resources.stringResource

/** The playing title, used to prefill the online subtitle search. */
data class SubtitleSearchContext(
    val title: String,
    /** "movie" or "series". */
    val type: String,
    val season: Int?,
    val episode: Int?,
    /** The catalogue (IMDb) id when known: the search then opens on this title's subtitles. */
    val imdbId: String?,
)

/** Load-a-file and save-to-device actions; null where the platform has no document picker. */
class SubtitleFileActions(
    val load: (() -> Unit)?,
    /** SAMplay 2.0.3: [offsetMs] is the subtitle delay written into the file's times (0: as it is). */
    val save: ((subtitle: AddonSubtitle, fileName: String, offsetMs: Int) -> Unit)?,
)

/**
 * [onLoaded] receives a subtitle file the user picked, copied into the app's cache.
 * [headersFor] gives the request headers a subtitle URL needs (a WebDAV drive's credentials).
 */
@Composable
expect fun rememberSubtitleFileActions(
    onLoaded: (AddonSubtitle) -> Unit,
    headersFor: (String) -> Map<String, String>,
): SubtitleFileActions

private val SUBTITLE_FILE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")

/** "srt", "vtt", "ass"…: from the URL's or file's name, else from the text itself. */
internal fun subtitleExtension(name: String, content: ByteArray? = null): String {
    val fromName = name.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
    if (fromName in SUBTITLE_FILE_EXTENSIONS) return fromName
    val head = content?.take(256)?.toByteArray()?.decodeToString()?.trimStart('﻿', ' ', '\r', '\n').orEmpty()
    return when {
        head.startsWith("WEBVTT") -> "vtt"
        head.startsWith("[Script Info]", ignoreCase = true) -> "ass"
        else -> "srt"
    }
}

internal fun isSubtitleFileName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in SUBTITLE_FILE_EXTENSIONS

private val FILE_NAME_TAGS = setOf("forced", "sdh", "cc", "default", "full")

// Codes trusted at the end of a file name: rarer ones ("uk", "her", "web"…) are usually words.
private val FILE_NAME_LANGUAGES = setOf(
    "ar", "en", "fr", "es", "de", "it", "pt", "ru", "tr", "fa", "ur", "hi", "id", "ms", "nl", "sv",
    "no", "nb", "da", "fi", "pl", "cs", "sk", "hu", "ro", "bg", "el", "he", "ja", "ko", "zh", "th",
    "vi", "hr", "sr", "sl", "bs", "sq", "mk", "et", "lv", "lt", "bn", "ta", "te", "ml", "ku", "ps",
    "tl",
)
private val BRAZILIAN_FILE_NAME = Regex("""(^|[._\s\[(-])(pt[-_ ]?br|pob)([._\s\])-]|$)""", RegexOption.IGNORE_CASE)

/** The language a file name states ("Movie.2019.ar.srt", "Show_S01E02_eng.srt", "Movie.Arabic.srt"), or "und". */
internal fun subtitleLanguageFromFileName(name: String): String {
    val stem = name.substringBeforeLast('.', name)
    if (BRAZILIAN_FILE_NAME.containsMatchIn(stem)) return "pt-br"
    val token = stem.split('.', '_', '-', ' ', '[', ']', '(', ')')
        .filter { it.isNotBlank() }
        .map { it.lowercase() }
        .lastOrNull { it !in FILE_NAME_TAGS }
        .orEmpty()
    when (token) {
        "عربي", "عربية", "العربية" -> return "ar"
        "انجليزي", "إنجليزي", "انجليزية", "الإنجليزية" -> return "en"
    }
    if (token.length < 2 || token.any { !it.isLetter() }) return "und"
    val code = normalizeLanguageCode(token) ?: return "und"
    val primary = code.substringBefore('-')
    val known = (primary in FILE_NAME_LANGUAGES || (primary == "uk" && token.length == 3)) &&
        (token.length <= 3 || code != token)
    return if (known) code else "und"
}

/** The language a subtitle's text is written in when its script tells (Arabic, Hebrew, Cyrillic…). */
internal fun subtitleLanguageFromText(text: String): String? {
    val counts = HashMap<String, Int>()
    var letters = 0
    for (char in text.take(20_000)) {
        if (!char.isLetter()) continue
        letters++
        val language = when (char) {
            in '\u0600'..'\u06FF', in '\u0750'..'\u077F', in '\uFB50'..'\uFDFF', in '\uFE70'..'\uFEFC' -> "ar"
            in '\u0590'..'\u05FF' -> "he"
            in '\u0400'..'\u04FF' -> "ru"
            in '\u0370'..'\u03FF' -> "el"
            in '\u0E00'..'\u0E7F' -> "th"
            in '\uAC00'..'\uD7AF', in '\u1100'..'\u11FF' -> "ko"
            in '\u3040'..'\u30FF' -> "ja"
            in '\u4E00'..'\u9FFF' -> "zh"
            else -> null
        } ?: continue
        counts[language] = (counts[language] ?: 0) + 1
    }
    if (letters == 0) return null
    if ((counts["ja"] ?: 0) > 0) counts["ja"] = (counts["ja"] ?: 0) + (counts.remove("zh") ?: 0)
    val (language, count) = counts.maxByOrNull { it.value } ?: return null
    return language.takeIf { count * 10 >= letters * 4 }
}

/** "Title.ar.srt": the name a saved subtitle gets. */
internal fun subtitleSaveName(title: String, subtitle: AddonSubtitle): String {
    val base = title
        .replace(Regex("""[\\/:*?"<>|]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .ifBlank { "subtitle" }
    val language = subtitle.language.takeIf { it.isNotBlank() && it != "und" && it != "unknown" }
    return listOfNotNull(base, language).joinToString(".") + "." + subtitleExtension(subtitle.url)
}

/** Search online / load a file / save the selected subtitle: shown beside the subtitle panel's title. */
@Composable
internal fun SubtitleExtrasBar(
    canSave: Boolean,
    onSearchOnline: () -> Unit,
    onLoadFile: (() -> Unit)?,
    onSave: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onAi: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubtitleExtraChip(Icons.Rounded.Search, stringResource(Res.string.subtitle_extra_search_online), onClick = onSearchOnline)
        if (onLoadFile != null) {
            SubtitleExtraChip(Icons.Rounded.FolderOpen, stringResource(Res.string.subtitle_extra_load_file), onClick = onLoadFile)
        }
        if (onAi != null) {
            SubtitleExtraChip(Icons.Rounded.AutoAwesome, stringResource(Res.string.subtitle_extra_ai), onClick = onAi)
        }
        if (onSave != null) {
            SubtitleExtraChip(
                icon = Icons.Rounded.Download,
                label = stringResource(Res.string.subtitle_extra_save),
                enabled = canSave,
                onClick = onSave,
            )
        }
    }
}

/**
 * SAMplay 2.0.3: "Save subtitle" with a subtitle delay set: the file with the delay written into
 * its times, or the file as it came.
 */
@Composable
internal fun SubtitleSaveChoiceBar(
    delayLabel: String,
    onSynced: () -> Unit,
    onOriginal: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubtitleExtraChip(Icons.Rounded.Download, stringResource(Res.string.subtitle_save_with_sync, delayLabel), onClick = onSynced)
        SubtitleExtraChip(Icons.Rounded.Download, stringResource(Res.string.subtitle_save_original), onClick = onOriginal)
        SubtitleExtraChip(Icons.Rounded.Close, stringResource(Res.string.subtitle_save_cancel), onClick = onCancel)
    }
}

@Composable
private fun SubtitleExtraChip(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.12f * alpha))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = alpha), modifier = Modifier.size(18.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = alpha),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

/**
 * Online subtitle search: type a film or show name, pick it (and the episode), pick a subtitle.
 * Titles come from Cinemeta, subtitles from the installed subtitle add-ons plus OpenSubtitles.
 */
@Composable
internal fun SubtitleOnlineSearchPanel(
    context: SubtitleSearchContext?,
    preferredLanguage: String,
    maxHeight: Dp,
    onSubtitleChosen: (AddonSubtitle) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val failedText = stringResource(Res.string.subtitle_search_failed)
    var query by remember { mutableStateOf(context?.title.orEmpty()) }
    var titles by remember { mutableStateOf<List<WebDavTitleMeta>?>(null) }
    var chosen by remember {
        mutableStateOf(
            context?.imdbId?.let { id ->
                WebDavTitleMeta(
                    imdbId = id,
                    type = if (context.type.equals("movie", ignoreCase = true)) "movie" else "series",
                    name = context.title,
                    poster = null,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = null,
                    genres = emptyList(),
                    episodes = emptyList(),
                )
            },
        )
    }
    var season by remember { mutableIntStateOf(context?.season ?: 1) }
    var episode by remember { mutableIntStateOf(context?.episode ?: 1) }
    var subtitles by remember { mutableStateOf<List<AddonSubtitle>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun runSearch() {
        val text = query.trim()
        if (text.length < 2) return
        job?.cancel()
        chosen = null
        subtitles = null
        error = null
        loading = true
        job = scope.launch {
            try {
                titles = WebDavMetadata.searchAll(text)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                error = failedText
            }
            loading = false
        }
    }

    fun loadSubtitles(title: WebDavTitleMeta) {
        val imdbId = title.imdbId ?: return
        job?.cancel()
        subtitles = null
        error = null
        loading = true
        val isSeries = title.type == "series"
        val videoId = if (isSeries) "$imdbId:$season:$episode" else imdbId
        job = scope.launch {
            try {
                val found = SubtitleRepository.search(title.type, videoId)
                subtitles = found.sortedByDescending { subtitle ->
                    preferredLanguage.isNotBlank() &&
                        SubtitleLanguageMatching.matchesLanguageCode(subtitle.language, preferredLanguage)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                error = failedText
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        val current = chosen
        if (current != null) {
            if (current.type != "series" || (context?.season != null && context.episode != null)) loadSubtitles(current)
        } else if (query.isNotBlank()) {
            runSearch()
        }
    }

    PlatformBackHandler(enabled = true) {
        if (chosen != null && titles != null) {
            chosen = null
            subtitles = null
        } else {
            onClose()
        }
    }

    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (chosen != null && titles != null) {
                    chosen = null
                    subtitles = null
                } else {
                    onClose()
                }
            }) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Text(
                text = chosen?.name ?: stringResource(Res.string.subtitle_search_title),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (chosen == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.subtitle_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = tokens.colors.accent,
                        focusedBorderColor = tokens.colors.accent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedPlaceholderColor = tokens.colors.textMuted,
                        unfocusedPlaceholderColor = tokens.colors.textMuted,
                    ),
                    modifier = Modifier.weight(1f),
                )
                SubtitleExtraChip(Icons.Rounded.Search, stringResource(Res.string.subtitle_extra_search_online), onClick = { runSearch() })
            }
        }

        val current = chosen
        if (current != null && current.type == "series") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                NumberStepper(stringResource(Res.string.subtitle_search_season), season, min = 0) { season = it }
                NumberStepper(stringResource(Res.string.subtitle_search_episode), episode, min = 1) { episode = it }
                SubtitleExtraChip(Icons.Rounded.Search, stringResource(Res.string.subtitle_search_find)) { loadSubtitles(current) }
            }
        }

        Box(modifier = Modifier.heightIn(min = 60.dp, max = (maxHeight - 150.dp).coerceAtLeast(160.dp))) {
            when {
                loading -> PlayerModalLoading(modifier = Modifier.padding(vertical = 24.dp))
                error != null -> MutedLine(error.orEmpty())
                current == null -> {
                    val list = titles
                    if (list != null && list.isEmpty()) {
                        MutedLine(stringResource(Res.string.subtitle_search_no_titles))
                    } else if (list != null) {
                        val movieLabel = stringResource(Res.string.subtitle_search_movie)
                        val seriesLabel = stringResource(Res.string.subtitle_search_series)
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(list, key = { "${it.type}:${it.imdbId}" }) { title ->
                                TitleRow(
                                    title = title,
                                    typeLabel = if (title.type == "series") seriesLabel else movieLabel,
                                    onClick = {
                                        chosen = title
                                        if (title.type != "series") loadSubtitles(title)
                                    },
                                )
                            }
                        }
                    }
                }
                else -> {
                    val list = subtitles
                    if (list != null && list.isEmpty()) {
                        MutedLine(stringResource(Res.string.subtitle_search_no_subtitles))
                    } else if (list != null) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(list.size) { index ->
                                val subtitle = list[index]
                                OnlineSubtitleRow(subtitle = subtitle, onClick = { onSubtitleChosen(subtitle) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MutedLine(text: String) {
    Text(
        text = text,
        color = MaterialTheme.nuvio.colors.textMuted,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(vertical = 16.dp, horizontal = 6.dp),
    )
}

@Composable
private fun TitleRow(title: WebDavTitleMeta, typeLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = title.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(40.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(typeLabel, title.releaseInfo).joinToString(" • "),
                color = MaterialTheme.nuvio.colors.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun OnlineSubtitleRow(subtitle: AddonSubtitle, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = languageLabelForCode(subtitle.language),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
        Text(
            text = subtitle.addonName ?: subtitle.display,
            color = MaterialTheme.nuvio.colors.textMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NumberStepper(label: String, value: Int, min: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 6.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.nuvio.colors.textMuted,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 6.dp),
        )
        IconButton(onClick = { if (value > min) onChange(value - 1) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Remove, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(text = value.toString(), color = Color.White, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange(value + 1) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(2.dp))
    }
}
