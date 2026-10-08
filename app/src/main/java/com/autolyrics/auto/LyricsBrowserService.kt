package com.autolyrics.auto

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.autolyrics.lyrics.KaraokeTiming
import com.autolyrics.lyrics.TranslationLanguages
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.util.LyricWordLayout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class LyricsBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaTracker: MediaTracker
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var lastNotifyTime = 0L
    private var pendingNotify = false
    private var displayedWindowStart = -1
    private var displayedWindowEnd = -1
    private var displayedCurrentIdx = -1
    private var displayedStatus: LyricsStatus? = null
    private var displayedTrackTitle: String? = null
    private var displayedSource: String? = null
    private var displayedDetectedLanguage: String? = null
    private var displayedHasTranslation = false
    private var lastSubtitleText: String? = null
    private var lastAlbumArt: Bitmap? = null

    private var aaKaraokeEnabled = true
    private var aaOffsetMs = 0L

    // Separate karaoke caches: browse tree (600ms window) vs now-playing title (300ms window).
    private val browseKaraoke = KaraokeBracketState()
    private val nowPlayingKaraoke = KaraokeBracketState()
    private val titleChunkHold = ChunkHold(MIN_CHUNK_DISPLAY_MS)
    private val translationChunkHold = ChunkHold(MIN_CHUNK_DISPLAY_MS)
    private var lastLyricTitle: String? = null

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                "aa_karaoke_enabled" -> {
                    aaKaraokeEnabled = sp.getBoolean(key, true)
                    forceRefresh()
                }
                "aa_offset_ms" -> {
                    aaOffsetMs = sp.getLong(key, 0L)
                    forceRefresh()
                }
                TranslationLanguages.TARGET_LANGUAGE_PREF_KEY -> {
                    forceRefresh()
                }
            }
        }

    companion object {
        private const val ROOT_ID = "root"
        private const val LYRICS_MENU_ID = "lyrics_menu"
        private const val SYNC_MENU_ID = "sync_menu"
        private const val MORE_MENU_ID = "more_menu"
        private const val SYNC_MINUS_ID = "sync_minus"
        private const val SYNC_PLUS_ID = "sync_plus"
        private const val SYNC_STEP_MS = 50L
        private const val SYNC_WINDOW_SIZE = 3
        private const val DEFAULT_WINDOW_SIZE = 5
        private const val CURRENT_LINE_PREFIX = "▶  "
        // Em + en spacing approximates the rendered width of the current-line marker
        // so lyric text starts at the same x-position on surrounding rows.
        private const val IDLE_LINE_PREFIX = "\u2003\u2002"
        // Browse subtitles render smaller than titles; em + en + three-per-em spacing
        // gives an effective indent of about 1.8em without changing Now Playing.
        private const val TRANSLATION_SUBTITLE_PREFIX = "\u2003\u2002\u2004"
        private const val PAD_WIDTH = 60
        private const val NOTIFY_THROTTLE_MS = 500L
        private const val SESSION_REFRESH_MS = 1500L
        private const val PLAIN_LOOP_DELAY_MS = 2000L

        // Now-playing card width budgets, in display units (1 per Latin char, 2 per
        // fullwidth/CJK char incl. ??). These are estimates: calibrate on the head unit.
        // Title uses a larger font than subtitle, so it fits less.
        private const val TITLE_MAX_WIDTH = 28
        private const val SUBTITLE_MAX_WIDTH = 32
        // Minimum time an estimated (non-ELRC) chunk stays up before the next one, to avoid flicker.
        private const val MIN_CHUNK_DISPLAY_MS = 1000L
        // Assumed duration of the last synced line when the track duration is unknown.
        private const val DEFAULT_LAST_LINE_MS = 5000L
        // Karaoke bracket look-ahead window for browse tree (larger) vs now-playing title (smaller).
        private const val BROWSE_KARAOKE_WINDOW_MS = 600L
        private const val TITLE_KARAOKE_WINDOW_MS = 300L
    }

    override fun onCreate() {
        super.onCreate()
        mediaTracker = MediaTracker.getInstance(this)

        val prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
        aaKaraokeEnabled = prefs.getBoolean("aa_karaoke_enabled", true)
        aaOffsetMs = prefs.getLong("aa_offset_ms", 0L)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        mediaSession = MediaSessionCompat(this, "AutoLyrics").apply {
            setCallback(SessionCallback())
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        scope.launch {
            mediaTracker.state.collectLatest { state ->
                updateMediaSession(state)
                throttledNotifyChildren(state)
            }
        }

        scope.launch {
            while (isActive) {
                delay(200)
                updateSubtitleKaraoke()
            }
        }

        scope.launch {
            while (isActive) {
                delay(PLAIN_LOOP_DELAY_MS)
                val state = mediaTracker.state.value
                if (state.isPlaying && state.status == LyricsStatus.PLAIN_ONLY && state.lines.isNotEmpty()) {
                    throttledNotifyChildren(state)
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(SESSION_REFRESH_MS)
                val state = mediaTracker.state.value
                if (state.isPlaying && state.track != null) {
                    mediaSession.isActive = true
                    mediaSession.setPlaybackState(buildPlaybackState(state))
                }
            }
        }
    }

    override fun onDestroy() {
        getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val state = mediaTracker.state.value
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        when (parentId) {
            ROOT_ID -> {
                // Keep the primary lyrics view first so Android Auto selects it as
                // the default browse tab. Sync is a secondary utility and More is
                // reserved for detailed track/provider information.
                items.add(buildBrowsableItem(LYRICS_MENU_ID, "Lyrics", "Current lyrics"))
                items.add(buildBrowsableItem(SYNC_MENU_ID, "Sync", "Adjust offset"))
                items.add(buildBrowsableItem(MORE_MENU_ID, "More", "Track and lyrics details"))
            }
            LYRICS_MENU_ID -> buildLyricsMenu(state, items)
            SYNC_MENU_ID -> buildSyncMenu(state, items)
            MORE_MENU_ID -> buildMoreMenu(state, items)
            SYNC_MINUS_ID, SYNC_PLUS_ID -> Unit
        }

        result.sendResult(items)
    }

    private fun buildLyricsMenu(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        when (state.status) {
            LyricsStatus.NO_MEDIA -> {
                items.add(buildTextItem("no_media", "Play a song to see lyrics"))
            }
            LyricsStatus.LOADING -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("loading", "Loading lyrics…"))
            }
            LyricsStatus.NOT_FOUND -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("not_found", "No lyrics found for this track"))
            }
            LyricsStatus.ERROR -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("error", "Error loading lyrics"))
            }
            LyricsStatus.FOUND -> {
                addTrackHeader(state, items)
                buildWindowedLyrics(state, items)
            }
            LyricsStatus.PLAIN_ONLY -> {
                addTrackHeader(state, items)
                if (state.lines.isEmpty()) {
                    items.add(buildTextItem("empty", "♪"))
                } else {
                    val durationMs = state.track?.durationMs ?: 0
                    val posMs = try {
                        mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
                    } catch (_: Exception) { 0L }

                    val estimatedIdx = if (durationMs > 0) {
                        ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                            .coerceIn(0, state.lines.size - 1)
                    } else { 0 }

                    val windowSize = DEFAULT_WINDOW_SIZE
                    val half = windowSize / 2
                    val winStart = maxOf(0, estimatedIdx - half)
                    val winEnd = minOf(state.lines.size, winStart + windowSize)
                    val adjStart = maxOf(0, winEnd - windowSize)

                    for (i in adjStart until winEnd) {
                        val text = state.lines[i].text.ifBlank { "♪" }
                        val prefix = linePrefix(i == estimatedIdx)
                        val trans = state.translatedLines?.getOrNull(i)?.takeIf { it.isNotBlank() }
                        items.add(buildTextItem("line_$i", "$prefix$text", pad = true, subtitle = trans))
                    }
                }
            }
        }
    }

    private fun buildSyncMenu(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val sign = if (aaOffsetMs >= 0) "+" else ""
        items.add(buildTextItem("sync_offset", "AA Offset: ${sign}${aaOffsetMs}ms"))
        items.add(
            buildTextItem(
                SYNC_MINUS_ID,
                "◀◀  Delay lyrics",
                subtitle = "−${SYNC_STEP_MS} ms"
            )
        )

        if (state.lines.isNotEmpty()) {
            val posMs = getAaPositionMs()
            val idx = if (state.status == LyricsStatus.FOUND) {
                findLineIndex(state.lines, posMs).coerceAtLeast(0)
            } else {
                val durationMs = state.track?.durationMs ?: 0
                if (durationMs > 0) {
                    ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                        .coerceIn(0, state.lines.size - 1)
                } else 0
            }

            val half = SYNC_WINDOW_SIZE / 2
            val windowStart = maxOf(0, idx - half)
            val windowEnd = minOf(state.lines.size, windowStart + SYNC_WINDOW_SIZE)
            val adjustedStart = maxOf(0, windowEnd - SYNC_WINDOW_SIZE)

            for (i in adjustedStart until windowEnd) {
                val line = state.lines[i]
                val isCurrent = i == idx
                val text = if (isCurrent && aaKaraokeEnabled && line.words.isNotEmpty()) {
                    buildKaraokeText(line, i, posMs)
                } else {
                    line.text.ifBlank { "♪" }
                }
                val trans = state.translatedLines?.getOrNull(i)?.takeIf { it.isNotBlank() }
                items.add(
                    buildTextItem(
                        "sync_line_$i",
                        "${linePrefix(isCurrent)}$text",
                        pad = true,
                        subtitle = trans
                    )
                )
            }
        }

        items.add(
            buildTextItem(
                SYNC_PLUS_ID,
                "▶▶  Advance lyrics",
                subtitle = "+${SYNC_STEP_MS} ms"
            )
        )
    }

    private fun buildMoreMenu(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val track = state.track
        if (track == null) {
            items.add(buildTextItem("more_no_media", "Play a song to see details"))
            return
        }

        items.add(buildTextItem("more_title", "Title", subtitle = track.title))
        if (track.artist.isNotBlank()) {
            items.add(buildTextItem("more_artist", "Artist", subtitle = track.artist))
        }
        if (track.album.isNotBlank()) {
            items.add(buildTextItem("more_album", "Album", subtitle = track.album))
        }

        val provider = state.source
            .substringBefore("·")
            .substringBefore("(")
            .trim()
            .ifBlank { "—" }
        items.add(buildTextItem("more_provider", "Provider", subtitle = provider))

        val syncStatus = when (state.status) {
            LyricsStatus.FOUND -> "Synced"
            LyricsStatus.PLAIN_ONLY -> "Not synced"
            LyricsStatus.LOADING -> "Loading"
            LyricsStatus.NOT_FOUND -> "Not found"
            LyricsStatus.ERROR -> "Error"
            LyricsStatus.NO_MEDIA -> "—"
        }
        items.add(buildTextItem("more_sync", "Lyrics", subtitle = syncStatus))

        val sourceLanguage = TranslationLanguages.normalizeLanguageTag(state.detectedLanguage)
        if (sourceLanguage != null) {
            if (state.translatedLines != null) {
                val targetLanguage = selectedTranslationTarget()
                items.add(
                    buildTextItem(
                        "more_translation",
                        "Translation",
                        subtitle = "${sourceLanguage.uppercase()} → ${targetLanguage.uppercase()}"
                    )
                )
            } else {
                items.add(
                    buildTextItem(
                        "more_language",
                        "Language",
                        subtitle = sourceLanguage.uppercase()
                    )
                )
            }
        }
        if (track.durationMs > 0) {
            items.add(buildTextItem("more_duration", "Duration", subtitle = formatTime(track.durationMs)))
        }

        val sign = if (aaOffsetMs >= 0) "+" else ""
        items.add(buildTextItem("more_offset", "AA Offset", subtitle = "${sign}${aaOffsetMs}ms"))
    }

    private fun addTrackHeader(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val track = state.track ?: return

        val subtitle = buildString {
            append(track.artist)
            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { -1L }
            if (posMs >= 0 && track.durationMs > 0) {
                append("  ·  ")
                append(formatTime(posMs))
                append(" / ")
                append(formatTime(track.durationMs))
            }
            val typeLabel = lyricsTypeLabel(state)
            if (typeLabel.isNotBlank()) {
                append("  ·  ")
                append(typeLabel)
            }
        }

        val descBuilder = MediaDescriptionCompat.Builder()
            .setMediaId("header")
            .setTitle(track.title)
            .setSubtitle(subtitle)

        val headerArt = state.albumArt ?: lastAlbumArt
        headerArt?.let { art ->
            descBuilder.setIconBitmap(art)
        }

        items.add(
            MediaBrowserCompat.MediaItem(
                descBuilder.build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        )
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    private fun linePrefix(isCurrent: Boolean): String {
        return if (isCurrent) CURRENT_LINE_PREFIX else IDLE_LINE_PREFIX
    }

    private fun buildWindowedLyrics(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val lines = state.lines
        if (lines.isEmpty()) {
            items.add(buildTextItem("empty", "♪"))
            return
        }

        val posMs = getAaPositionMs()
        val aaCurrentIdx = findLineIndex(lines, posMs).coerceAtLeast(0)
        val windowSize = DEFAULT_WINDOW_SIZE
        val half = windowSize / 2

        val windowStart = maxOf(0, aaCurrentIdx - half)
        val windowEnd = minOf(lines.size, windowStart + windowSize)
        val adjustedStart = maxOf(0, windowEnd - windowSize)

        for (i in adjustedStart until windowEnd) {
            val line = lines[i]
            val isCurrent = i == aaCurrentIdx
            val prefix = linePrefix(isCurrent)

            val text = if (isCurrent && aaKaraokeEnabled && line.words.isNotEmpty()) {
                buildKaraokeText(line, i, posMs)
            } else {
                line.text.ifBlank { "♪" }
            }

            val trans = state.translatedLines?.getOrNull(i)?.takeIf { it.isNotBlank() }
            items.add(buildTextItem("line_$i", "$prefix$text", pad = true, subtitle = trans))
        }
    }

    private fun buildTextItem(id: String, text: String, pad: Boolean = false, subtitle: String? = null): MediaBrowserCompat.MediaItem {
        val title = if (pad) text.padEnd(PAD_WIDTH) else text
        val alignedSubtitle = if (
            !subtitle.isNullOrBlank() &&
            (id.startsWith("line_") || id.startsWith("sync_line_"))
        ) {
            "$TRANSLATION_SUBTITLE_PREFIX$subtitle"
        } else {
            subtitle
        }
        val builder = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
        if (!alignedSubtitle.isNullOrBlank()) builder.setSubtitle(alignedSubtitle)
        return MediaBrowserCompat.MediaItem(
            builder.build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun buildBrowsableItem(id: String, title: String, subtitle: String): MediaBrowserCompat.MediaItem {
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }

    private fun lyricsTypeLabel(state: LyricsState): String {
        val provider = state.source
            .substringBefore("·")
            .substringBefore("(")
            .trim()
        val providerSuffix = if (provider.isNotBlank()) " · $provider" else ""
        val sourceLanguage = TranslationLanguages.normalizeLanguageTag(state.detectedLanguage)
        val languageSuffix = if (sourceLanguage != null) {
            if (state.translatedLines != null) {
                " · ${sourceLanguage.uppercase()}→${selectedTranslationTarget().uppercase()}"
            } else {
                " · ${sourceLanguage.uppercase()}"
            }
        } else {
            ""
        }

        return when (state.status) {
            LyricsStatus.FOUND -> "⟳ Synced$providerSuffix$languageSuffix"
            LyricsStatus.PLAIN_ONLY -> "⟳ Not synced$providerSuffix$languageSuffix"
            else -> "⟳ Sync"
        }
    }

    private fun selectedTranslationTarget(): String {
        val prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
        return TranslationLanguages.normalizeTargetLanguage(
            prefs.getString(
                TranslationLanguages.TARGET_LANGUAGE_PREF_KEY,
                TranslationLanguages.DEFAULT_TARGET_LANGUAGE
            )
        )
    }

    // --- Karaoke helpers ---

    private fun getAaPositionMs(): Long {
        return try {
            mediaTracker.getCurrentPositionMs() + aaOffsetMs
        } catch (_: Exception) {
            0L
        }
    }

    private fun findLineIndex(lines: List<LyricLine>, posMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) idx = i
            else break
        }
        return idx
    }

    /** Whole-line karaoke text for the browse tree (uses its own cache, 600ms window). */
    private fun buildKaraokeText(line: LyricLine, lineIdx: Int, posMs: Long): String =
        browseKaraoke.build(
            line.words, lineIdx, line.words.indices, posMs, BROWSE_KARAOKE_WINDOW_MS, line.text
        )

    /** Browse-tree karaoke cache; reset on track change and forced refresh. */
    private fun resetKaraokeState() {
        browseKaraoke.reset()
    }

    /** Now-playing card karaoke/chunk state; reset on track change and forced refresh. */
    private fun resetNowPlayingState() {
        nowPlayingKaraoke.reset()
        titleChunkHold.reset()
        translationChunkHold.reset()
        lastLyricTitle = null
    }

    private class CardText(val lyricTitle: String?, val subtitle: String)

    /**
     * Text for the now-playing card.
     *
     * While a lyric line is active: DISPLAY_TITLE = the line being sung (karaoke 【】 only
     * here), DISPLAY_SUBTITLE = what comes next (see [NowPlayingText.subtitleFor]).
     * Otherwise (intro before the first timed line, loading, not found, error)
     * [CardText.lyricTitle] is null so the title stays "Title — Artist", and the subtitle
     * is the status text.
     */
    private fun getCardText(state: LyricsState): CardText {
        getLyricCardText(state)?.let { return it }

        val subtitle = when (state.status) {
            // Intro: preview the first line while the title still shows the track.
            LyricsStatus.FOUND -> state.lines.firstOrNull()?.text.orEmpty()
            LyricsStatus.LOADING -> "Loading lyrics…"
            LyricsStatus.NOT_FOUND -> "No lyrics found"
            LyricsStatus.ERROR -> "Error loading lyrics"
            else -> ""
        }
        return CardText(null, subtitle)
    }

    private fun getLyricCardText(state: LyricsState): CardText? {
        val lines = state.lines
        if (lines.isEmpty()) return null
        val posMs = getAaPositionMs()
        val durationMs = state.track?.durationMs ?: 0L

        val idx: Int
        val startMs: Long
        val endMs: Long
        when (state.status) {
            LyricsStatus.FOUND -> {
                idx = findLineIndex(lines, posMs)
                if (idx < 0) return null
                startMs = lines[idx].timeMs
                val next = lines.getOrNull(idx + 1)?.timeMs
                endMs = when {
                    next != null && next > startMs -> next
                    next == null && durationMs > startMs -> durationMs
                    else -> startMs + DEFAULT_LAST_LINE_MS
                }
            }
            LyricsStatus.PLAIN_ONLY -> {
                // Unsynced: same estimated index as before, each line gets an equal time slice.
                idx = if (durationMs > 0) {
                    ((posMs.toFloat() / durationMs) * lines.size).toInt()
                        .coerceIn(0, lines.size - 1)
                } else 0
                if (durationMs > 0) {
                    startMs = durationMs * idx / lines.size
                    endMs = durationMs * (idx + 1) / lines.size
                } else {
                    startMs = 0L
                    endMs = DEFAULT_LAST_LINE_MS
                }
            }
            else -> return null
        }

        val line = lines[idx]
        val nextText = lines.getOrNull(idx + 1)?.text
        val nowMs = SystemClock.elapsedRealtime()

        // A translation of the current line takes the subtitle instead of the next line.
        val translation = state.translatedLines?.getOrNull(idx)?.takeIf { it.isNotBlank() }
        val translationChunk = translation?.let {
            val chunks = NowPlayingText.chunkText(it, SUBTITLE_MAX_WIDTH)
            if (chunks.isEmpty()) return@let null
            val candidate = NowPlayingText.activeChunkByProportion(
                chunks.map(NowPlayingText::displayWidth), startMs, endMs, posMs
            )
            chunks[translationChunkHold.select(idx, candidate, nowMs).coerceIn(0, chunks.lastIndex)]
        }

        val hasWords = line.words.isNotEmpty()
        val useKaraoke = aaKaraokeEnabled && hasWords
        // Reserve room for 【】 so the active chunk still fits with brackets.
        val budget = if (useKaraoke) TITLE_MAX_WIDTH - NowPlayingText.KARAOKE_BRACKETS_WIDTH else TITLE_MAX_WIDTH
        val tokens = if (hasWords) line.words.map { it.text } else NowPlayingText.tokenize(line.text, budget)
        if (tokens.isEmpty()) {
            return CardText(
                NowPlayingText.END_MARK,
                NowPlayingText.subtitleFor(emptyList(), 0, translationChunk, nextText)
            )
        }

        val chunks = NowPlayingText.chunkWords(tokens, budget)
        val chunkTexts = chunks.map { NowPlayingText.joinRange(tokens, it) }
        val selectedChunk = if (hasWords) {
            // ELRC: follow the sung word directly (no hold) so it is always in the title.
            NowPlayingText.activeChunkByWordTime(chunks, NowPlayingText.currentWordIndex(line.words, posMs))
        } else {
            val candidate = NowPlayingText.activeChunkByProportion(
                chunkTexts.map(NowPlayingText::displayWidth), startMs, endMs, posMs
            )
            titleChunkHold.select(idx, candidate, nowMs)
        }
        val chunkIdx = selectedChunk.coerceIn(0, chunks.lastIndex)

        val title = if (useKaraoke) {
            nowPlayingKaraoke.build(
                line.words, idx, chunks[chunkIdx], posMs, TITLE_KARAOKE_WINDOW_MS, chunkTexts[chunkIdx]
            )
        } else {
            chunkTexts[chunkIdx]
        }
        return CardText(title, NowPlayingText.subtitleFor(chunkTexts, chunkIdx, translationChunk, nextText))
    }

    private fun getSubtitleText(state: LyricsState): String {
        val posMs = getAaPositionMs()

        if (state.status == LyricsStatus.FOUND) {
            val lineIdx = findLineIndex(state.lines, posMs)
            val line = state.lines.getOrNull(lineIdx)
            if (line != null) {
                val original = if (aaKaraokeEnabled && line.words.isNotEmpty()) {
                    buildKaraokeText(line, lineIdx, posMs)
                } else {
                    line.text
                }
                val markedOriginal = "$CURRENT_LINE_PREFIX$original"
                val trans = state.translatedLines?.getOrNull(lineIdx)?.takeIf { it.isNotBlank() }
                return if (trans != null) "$markedOriginal\n$IDLE_LINE_PREFIX$trans" else markedOriginal
            }
        }

        if (state.status == LyricsStatus.PLAIN_ONLY && state.lines.isNotEmpty()) {
            val durationMs = state.track?.durationMs ?: 0
            val idx = if (durationMs > 0) {
                ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                    .coerceIn(0, state.lines.size - 1)
            } else { 0 }
            val original = state.lines[idx].text
            val markedOriginal = "$CURRENT_LINE_PREFIX$original"
            val trans = state.translatedLines?.getOrNull(idx)?.takeIf { it.isNotBlank() }
            return if (trans != null) "$markedOriginal\n$IDLE_LINE_PREFIX$trans" else markedOriginal
        }

        return when (state.status) {
            LyricsStatus.LOADING -> "Loading lyrics…"
            LyricsStatus.NOT_FOUND -> "No lyrics found"
            LyricsStatus.ERROR -> "Error loading lyrics"
            else -> ""
        }
    }

    // --- MediaSession management ---

    private fun buildBaseMetadata(state: LyricsState, lyricTitle: String? = null): MediaMetadataCompat.Builder {
        val metaBuilder = MediaMetadataCompat.Builder()

        state.track?.let { track ->
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            if (track.durationMs > 0) {
                metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
            }

            val displayTitle = lyricTitle ?: if (track.artist.isNotBlank()) {
                "${track.title} — ${track.artist}"
            } else {
                track.title
            }
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
        }

        val art = state.albumArt ?: lastAlbumArt
        if (art != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            lastAlbumArt = art
        }

        return metaBuilder
    }

    private fun updateMediaSession(state: LyricsState) {
        val trackChanged = state.track?.title != displayedTrackTitle
        if (trackChanged) {
            mediaSession.isActive = true
            lastSubtitleText = null
            lastAlbumArt = null
            resetKaraokeState()
        }

        val metaBuilder = buildBaseMetadata(state)

        val subtitleText = getSubtitleText(state)
        if (subtitleText.isNotBlank()) {
            metaBuilder.putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                subtitleText
            )
        }
        lastSubtitleText = subtitleText

        mediaSession.setMetadata(metaBuilder.build())
        mediaSession.setPlaybackState(buildPlaybackState(state))
    }

    private fun updateSubtitleKaraoke() {
        val state = mediaTracker.state.value
        if (!state.isPlaying) return
        if (state.status != LyricsStatus.FOUND && state.status != LyricsStatus.PLAIN_ONLY) return

        // Push only when the title or subtitle text actually changed.
        val card = getCardText(state)
        val subtitleText = card.subtitle
        if (subtitleText == lastSubtitleText && card.lyricTitle == lastLyricTitle) return
        lastSubtitleText = subtitleText
        lastLyricTitle = card.lyricTitle

        val metaBuilder = buildBaseMetadata(state, card.lyricTitle)
        if (subtitleText.isNotBlank()) {
            metaBuilder.putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                subtitleText
            )
        }
        mediaSession.setMetadata(metaBuilder.build())
        mediaSession.setPlaybackState(buildPlaybackState(state))
    }

    private fun buildPlaybackState(state: LyricsState): PlaybackStateCompat {
        val pbState = if (state.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else if (state.track != null) {
            PlaybackStateCompat.STATE_PAUSED
        } else {
            PlaybackStateCompat.STATE_NONE
        }

        val position = try {
            mediaTracker.getCurrentPositionMs()
        } catch (_: Exception) {
            0L
        }

        return PlaybackStateCompat.Builder()
            .setState(pbState, position, if (state.isPlaying) 1.0f else 0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .build()
    }

    // --- Throttled browse tree updates ---

    private data class WindowInfo(val start: Int, val end: Int, val currentIdx: Int)

    private fun computeWindow(state: LyricsState): WindowInfo {
        val lines = state.lines
        if (lines.isEmpty()) return WindowInfo(-1, -1, -1)

        val isPlain = state.status == LyricsStatus.PLAIN_ONLY
        val currentIdx = if (isPlain) {
            val durationMs = state.track?.durationMs ?: 0
            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { 0L }
            if (durationMs > 0) {
                ((posMs.toFloat() / durationMs) * lines.size).toInt()
                    .coerceIn(0, lines.size - 1)
            } else 0
        } else {
            val posMs = getAaPositionMs()
            findLineIndex(lines, posMs).coerceAtLeast(0)
        }

        val winSize = DEFAULT_WINDOW_SIZE
        val half = winSize / 2
        val windowStart = maxOf(0, currentIdx - half)
        val windowEnd = minOf(lines.size, windowStart + winSize)
        val adjustedStart = maxOf(0, windowEnd - winSize)
        return WindowInfo(adjustedStart, windowEnd, currentIdx)
    }

    private fun notifyBrowseSections() {
        notifyChildrenChanged(LYRICS_MENU_ID)
        notifyChildrenChanged(SYNC_MENU_ID)
        notifyChildrenChanged(MORE_MENU_ID)
    }

    private fun forceRefresh() {
        displayedWindowStart = -1
        displayedWindowEnd = -1
        displayedCurrentIdx = -1
        displayedSource = null
        displayedDetectedLanguage = null
        displayedHasTranslation = false
        lastNotifyTime = 0L
        resetKaraokeState()
        resetNowPlayingState()
        notifyBrowseSections()
    }

    private fun throttledNotifyChildren(state: LyricsState) {
        val statusChanged = state.status != displayedStatus
        val trackChanged = state.track?.title != displayedTrackTitle
        val sourceChanged = state.source != displayedSource
        val detectedLanguageChanged = state.detectedLanguage != displayedDetectedLanguage
        val hasTranslation = state.translatedLines != null
        val translationAvailabilityChanged = hasTranslation != displayedHasTranslation

        if (trackChanged) {
            displayedWindowStart = -1
            displayedWindowEnd = -1
            displayedCurrentIdx = -1
            displayedStatus = state.status
            displayedTrackTitle = state.track?.title
            displayedSource = state.source
            displayedDetectedLanguage = state.detectedLanguage
            displayedHasTranslation = hasTranslation
            lastNotifyTime = System.currentTimeMillis()
            handler.removeCallbacksAndMessages(null)
            pendingNotify = false
            resetKaraokeState()
            notifyBrowseSections()
            return
        }

        val win = computeWindow(state)
        val windowChanged = win.start != displayedWindowStart || win.end != displayedWindowEnd
        val lineChanged = win.currentIdx != displayedCurrentIdx
        val karaokeActive = aaKaraokeEnabled && state.status == LyricsStatus.FOUND
            && state.lines.getOrNull(win.currentIdx)?.words?.isNotEmpty() == true

        if (!windowChanged && !lineChanged && !statusChanged && !sourceChanged &&
            !detectedLanguageChanged && !translationAvailabilityChanged && !karaokeActive
        ) {
            return
        }

        displayedWindowStart = win.start
        displayedWindowEnd = win.end
        displayedCurrentIdx = win.currentIdx
        displayedStatus = state.status
        displayedSource = state.source
        displayedDetectedLanguage = state.detectedLanguage
        displayedHasTranslation = hasTranslation

        val now = System.currentTimeMillis()
        val elapsed = now - lastNotifyTime

        if (elapsed >= NOTIFY_THROTTLE_MS) {
            lastNotifyTime = now
            notifyBrowseSections()
        } else if (!pendingNotify) {
            pendingNotify = true
            handler.postDelayed({
                pendingNotify = false
                lastNotifyTime = System.currentTimeMillis()
                notifyBrowseSections()
            }, NOTIFY_THROTTLE_MS - elapsed)
        }
    }

    // --- Transport controls ---

    private fun getActiveMediaController(): MediaController? {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE)
            as? android.media.session.MediaSessionManager ?: return null
        return try {
            val component = android.content.ComponentName(
                this, com.autolyrics.media.MediaListenerService::class.java
            )
            sessionManager.getActiveSessions(component)
                .firstOrNull { it.packageName != packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessionManager.getActiveSessions(component)
                    .firstOrNull { it.packageName != packageName }
        } catch (_: SecurityException) {
            null
        }
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            getActiveMediaController()?.transportControls?.play()
        }

        override fun onPause() {
            getActiveMediaController()?.transportControls?.pause()
        }

        override fun onSkipToNext() {
            getActiveMediaController()?.transportControls?.skipToNext()
        }

        override fun onSkipToPrevious() {
            getActiveMediaController()?.transportControls?.skipToPrevious()
        }

        override fun onStop() {
            getActiveMediaController()?.transportControls?.stop()
        }

        override fun onSeekTo(pos: Long) {
            getActiveMediaController()?.transportControls?.seekTo(pos)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId == null) return

            if (mediaId == SYNC_MINUS_ID || mediaId == SYNC_PLUS_ID) {
                if (mediaId == SYNC_MINUS_ID) aaOffsetMs -= SYNC_STEP_MS
                else aaOffsetMs += SYNC_STEP_MS
                getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
                    .edit().putLong("aa_offset_ms", aaOffsetMs).apply()
                notifyBrowseSections()
                return
            }

            if (!mediaId.startsWith("line_")) return
            val index = mediaId.removePrefix("line_").toIntOrNull() ?: return
            val state = mediaTracker.state.value
            if (state.status != LyricsStatus.FOUND) return
            val line = state.lines.getOrNull(index) ?: return
            if (line.timeMs > 0) {
                getActiveMediaController()?.transportControls?.seekTo(line.timeMs)
            }
        }
    }
}
