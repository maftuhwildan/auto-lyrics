package com.autolyrics.media

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.autolyrics.BuildConfig
import com.autolyrics.lyrics.KaraokeTiming
import com.autolyrics.lyrics.LrcLibClient
import com.autolyrics.lyrics.LrcParser
import com.autolyrics.lyrics.LyricsCache
import com.autolyrics.lyrics.LyricsProviderCandidate
import com.autolyrics.lyrics.LyricsProviderResolver
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.lyrics.MetadataCleaner
import com.autolyrics.lyrics.MusixmatchClient
import com.autolyrics.lyrics.PetitLyricsClient
import com.autolyrics.lyrics.SyncLrcClient
import com.autolyrics.lyrics.TranslationLanguages
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.autolyrics.util.AlbumColorExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaTracker private constructor(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auto_lyrics_prefs", Context.MODE_PRIVATE)
    private val lyricsCache = LyricsCache(context)

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private var activeController: MediaController? = null
    private var lastPositionMs: Long = 0
    private var lastPositionUpdateTime: Long = 0
    private var playbackSpeed: Float = 1.0f
    private var fetchJob: Job? = null
    private var artJob: Job? = null
    private var translationJob: Job? = null
    private var pendingTrack: TrackInfo? = null
    private var pendingArt: Bitmap? = null
    private var lyricsOffsetMs: Long = 0L

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                TRANSLATION_ENABLED_KEY -> {
                    handler.post { handleTranslationPreferenceChanged() }
                }
                TranslationLanguages.TARGET_LANGUAGE_PREF_KEY -> {
                    handler.post { handleTranslationTargetChanged() }
                }
                AA_KARAOKE_ENABLED_KEY -> {
                    handler.post { handleKaraokePreferenceChanged() }
                }
            }
        }

    init {
        lyricsOffsetMs = prefs.getLong("lyrics_offset_ms", 0L)
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    private val positionChecker = object : Runnable {
        override fun run() {
            updateCurrentPosition()
            if (_state.value.isPlaying) {
                handler.postDelayed(this, 150)
            }
        }
    }

    private val trackChangeRunnable = Runnable {
        val track = pendingTrack ?: return@Runnable
        val art = pendingArt
        pendingTrack = null
        pendingArt = null
        val current = _state.value.track
        if (track == current) return@Runnable

        translationJob?.cancel()
        _state.value = _state.value.copy(
            track = track,
            lines = emptyList(),
            currentIndex = -1,
            currentWordIndex = -1,
            status = LyricsStatus.LOADING,
            source = "",
            albumArt = art,
            albumColors = null,
            translatedLines = null,
            detectedLanguage = null
        )
        fetchLyrics(track)
        extractAlbumColors(art)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadataChanged(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateChanged(state)
        }

        override fun onSessionDestroyed() {
            onMediaSessionChanged(null)
        }
    }

    fun adjustOffset(deltaMs: Long) {
        lyricsOffsetMs += deltaMs
        prefs.edit().putLong("lyrics_offset_ms", lyricsOffsetMs).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resetOffset() {
        lyricsOffsetMs = 0L
        prefs.edit().putLong("lyrics_offset_ms", 0L).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resumePlayback() {
        activeController?.transportControls?.play()
    }

    fun setOffset(ms: Long) {
        lyricsOffsetMs = ms
        prefs.edit().putLong("lyrics_offset_ms", ms).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun getCurrentPositionMs(): Long {
        val basePos = if (!_state.value.isPlaying) {
            lastPositionMs
        } else {
            val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
            lastPositionMs + (elapsed * playbackSpeed).toLong()
        }
        return basePos + lyricsOffsetMs
    }

    private fun updateCurrentPosition() {
        val currentState = _state.value
        val lines = currentState.lines
        if (lines.isEmpty() || currentState.status != LyricsStatus.FOUND) return

        val posMs = getCurrentPositionMs()

        var newLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) {
                newLineIndex = i
            } else {
                break
            }
        }

        val newWordIndex = if (newLineIndex >= 0) {
            KaraokeTiming.activeWordIndex(lines[newLineIndex].words, posMs)
        } else {
            -1
        }

        if (newLineIndex != currentState.currentIndex || newWordIndex != currentState.currentWordIndex) {
            _state.value = currentState.copy(
                currentIndex = newLineIndex,
                currentWordIndex = newWordIndex
            )
        }
    }

    fun onMediaSessionChanged(controller: MediaController?) {
        val current = activeController
        if (controller == null && current == null) return
        if (controller != null && current != null &&
            controller.sessionToken == current.sessionToken
        ) {
            // Notification/session-list callbacks are frequent. Keep our callback
            // registered and refresh the snapshot in case metadata changed before
            // the framework delivered its callback.
            handleMetadataChanged(controller.metadata)
            handlePlaybackStateChanged(controller.playbackState)
            return
        }
        // A delayed metadata change belongs to the previous media session and
        // must never be committed after the controller has been replaced.
        handler.removeCallbacks(trackChangeRunnable)
        pendingTrack = null
        pendingArt = null
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller

        if (controller == null) {
            handler.removeCallbacks(positionChecker)
            artJob?.cancel()
            _state.value = LyricsState(offsetMs = lyricsOffsetMs)
            return
        }

        controller.registerCallback(mediaCallback)
        handleMetadataChanged(controller.metadata)
        handlePlaybackStateChanged(controller.playbackState)
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) return

        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val rawAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val title = MetadataCleaner.cleanTitle(rawTitle)
        val artist = MetadataCleaner.cleanArtist(rawArtist)
        val album = MetadataCleaner.cleanAlbum(rawAlbum)

        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val sourcePackage = activeController?.packageName.orEmpty()
        val isSpotify = sourcePackage == SpotifyTrackIdentity.PACKAGE_NAME
        val description = metadata.description
        val sourceMediaId = if (isSpotify) {
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?: description.mediaId
                ?: ""
        } else {
            ""
        }
        val sourceMediaUri = if (isSpotify) {
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                ?: description.mediaUri?.toString()
                ?: ""
        } else {
            ""
        }

        val newTrack = TrackInfo(
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            playbackSourcePackage = if (isSpotify) sourcePackage else "",
            playbackSourceMediaId = sourceMediaId,
            playbackSourceMediaUri = sourceMediaUri
        )
        val current = _state.value.track

        if (current != null && newTrack == current) {
            // Metadata may briefly report an intermediate item while a player is
            // transitioning. If it settles back to the current item, discard the
            // delayed transition rather than switching to stale pending metadata.
            handler.removeCallbacks(trackChangeRunnable)
            pendingTrack = null
            pendingArt = null
            if (art != null && _state.value.albumArt == null) {
                _state.value = _state.value.copy(albumArt = art)
                extractAlbumColors(art)
            }
            return
        }

        if (pendingTrack == newTrack) {
            // Repeated active-session/notification callbacks often carry the
            // same incoming metadata. Preserve the original debounce deadline.
            if (art != null) pendingArt = art
            return
        }

        pendingTrack = newTrack
        pendingArt = art
        handler.removeCallbacks(trackChangeRunnable)
        handler.postDelayed(trackChangeRunnable, 600)
    }

    private fun extractAlbumColors(bitmap: Bitmap?) {
        artJob?.cancel()
        if (bitmap == null) return
        artJob = scope.launch(Dispatchers.Default) {
            val colors = AlbumColorExtractor.extract(bitmap)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(albumColors = colors)
            }
        }
    }

    private fun handlePlaybackStateChanged(pbState: PlaybackState?) {
        if (pbState == null) return

        val isPlaying = pbState.state == PlaybackState.STATE_PLAYING
        lastPositionMs = pbState.position
        lastPositionUpdateTime = pbState.lastPositionUpdateTime
        if (lastPositionUpdateTime == 0L) {
            lastPositionUpdateTime = SystemClock.elapsedRealtime()
        }
        playbackSpeed = if (pbState.playbackSpeed > 0) pbState.playbackSpeed else 1.0f

        _state.value = _state.value.copy(isPlaying = isPlaying)

        handler.removeCallbacks(positionChecker)
        if (isPlaying) {
            handler.post(positionChecker)
        }
    }

    private fun fetchLyrics(track: TrackInfo) {
        fetchJob?.cancel()
        fetchJob = scope.launch(Dispatchers.IO) {
            try {
                val preferWordSync = prefs.getBoolean(AA_KARAOKE_ENABLED_KEY, true)
                val cacheVariant = if (preferWordSync) {
                    LyricsCache.Variant.KARAOKE
                } else {
                    LyricsCache.Variant.STANDARD
                }
                val cached = lyricsCache.get(track, cacheVariant)
                if (cached != null) {
                    val (lines, status, source) = cached
                    withContext(Dispatchers.Main) {
                        if (_state.value.track != track ||
                            prefs.getBoolean(AA_KARAOKE_ENABLED_KEY, true) != preferWordSync
                        ) {
                            return@withContext
                        }
                        _state.value = _state.value.copy(
                            lines = lines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = status,
                            source = "$source (cached)"
                        )
                        if (status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(lines, track)
                    }

                    val cacheAge = lyricsCache.getAge(track, cacheVariant)
                    val refreshAfterMs = lyricsCache.getRefreshAfterMs(track, cacheVariant)
                    if (refreshAfterMs > 0L && cacheAge < refreshAfterMs) {
                        return@launch
                    }
                }

                val decision = fetchBestLyrics(track, preferWordSync)
                val result = decision.candidate

                withContext(Dispatchers.Main) {
                    if (_state.value.track != track ||
                        prefs.getBoolean(AA_KARAOKE_ENABLED_KEY, true) != preferWordSync
                    ) {
                        return@withContext
                    }

                    if (result != null) {
                        lyricsCache.put(
                            track = track,
                            lines = result.lines,
                            status = result.status,
                            source = result.source,
                            refreshAfterMs = decision.refreshAfterMs,
                            variant = cacheVariant
                        )
                        _state.value = _state.value.copy(
                            lines = result.lines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = result.status,
                            source = result.source
                        )
                        if (result.status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(result.lines, track)
                    } else if (cached == null) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.NOT_FOUND,
                            source = ""
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track && _state.value.status == LyricsStatus.LOADING) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.ERROR,
                            source = ""
                        )
                    }
                }
            }
        }
    }

    private data class ProviderAttempt(
        val provider: String,
        val candidate: LyricsProviderCandidate?,
        val timedOut: Boolean,
        val elapsedMs: Long
    )

    private data class FetchDecision(
        val candidate: LyricsProviderCandidate?,
        val refreshAfterMs: Long
    )

    private suspend fun fetchBestLyrics(
        track: TrackInfo,
        preferWordSync: Boolean
    ): FetchDecision = coroutineScope {
        // Five seconds is a hard per-provider budget. Healthy responses observed in
        // practice are normally sub-second; waiting 10-15 seconds for one source
        // makes a track change feel broken. runInterruptible allows timeout/cancel
        // to interrupt the synchronous OkHttp work rather than merely abandoning
        // the Deferred while the blocking call keeps this fetch waiting.
        val lrcLibDeferred = async {
            fetchProviderWithBudget("LRCLIB") { fetchFromLrcLib(track) }
        }
        val petitLyricsDeferred = if (PetitLyricsClient.isConfigured) {
            async {
                fetchProviderWithBudget("PetitLyrics") { fetchFromPetitLyrics(track) }
            }
        } else {
            null
        }
        val musixmatchDeferred = async {
            fetchProviderWithBudget("Musixmatch") { fetchFromMusixmatch(track) }
        }
        val syncLrcDeferred = if (preferWordSync) {
            async {
                fetchProviderWithBudget("SyncLRC") { fetchFromSyncLrc(track) }
            }
        } else {
            null
        }

        val lrcAttempt = lrcLibDeferred.await()
        val petitAttempt = petitLyricsDeferred?.await()
        val musixmatchAttempt = musixmatchDeferred.await()
        val syncLrcAttempt = syncLrcDeferred?.await()
        val attempts = listOfNotNull(lrcAttempt, petitAttempt, musixmatchAttempt, syncLrcAttempt)
        val candidates = attempts.mapNotNull { it.candidate }

        val scored = LyricsProviderResolver.scoreCandidates(track, candidates)
        if (BuildConfig.DEBUG) {
            attempts.forEach { attempt ->
                Log.d(
                    PROVIDER_RESOLVER_TAG,
                    "${attempt.provider} fetch elapsed=${attempt.elapsedMs}ms " +
                        "timedOut=${attempt.timedOut} candidate=${attempt.candidate != null}"
                )
            }
            scored.forEach { score ->
                Log.d(
                    PROVIDER_RESOLVER_TAG,
                    "%s metadata=%.3f quality=%.3f confidence=%.3f final=%.3f kind=%s".format(
                        score.candidate.provider,
                        score.metadataScore,
                        score.qualityScore,
                        score.sourceConfidence,
                        score.finalScore,
                        score.candidate.syncKind
                    )
                )
            }
        }

        val selected = LyricsProviderResolver.selectBest(
            track = track,
            candidates = candidates,
            preferWordSync = preferWordSync
        )
        // Musixmatch and SyncLRC are opportunistic word-sync sources. Keep the
        // ordinary cache-health decision anchored to LRCLIB/PetitLyrics, but use a
        // short-lived Karaoke cache when no usable word-timed result was found so
        // a transient aggregator/provider miss does not pin LINE_SYNC for a week.
        val providerSetComplete = if (PetitLyricsClient.isConfigured) {
            lrcAttempt.candidate != null && petitAttempt?.candidate != null &&
                !lrcAttempt.timedOut && !petitAttempt.timedOut
        } else {
            !lrcAttempt.timedOut
        }
        val selectedHasWordTiming = selected?.candidate?.let {
            LyricsProviderResolver.hasUsableWordTiming(it)
        } == true
        val karaokeNeedsRetry = preferWordSync && !selectedHasWordTiming
        val refreshAfterMs = if (providerSetComplete && !karaokeNeedsRetry) {
            LyricsCache.DEFAULT_REFRESH_AFTER_MS
        } else {
            PROVISIONAL_CACHE_REFRESH_MS
        }

        if (BuildConfig.DEBUG) {
            Log.d(
                PROVIDER_RESOLVER_TAG,
                selected?.let {
                    "selected=${it.candidate.provider} final=${"%.3f".format(it.finalScore)} " +
                        "kind=${it.candidate.syncKind} mode=${if (preferWordSync) "karaoke" else "standard"} " +
                        "cacheRefresh=${refreshAfterMs}ms"
                } ?: "selected=none mode=${if (preferWordSync) "karaoke" else "standard"}"
            )
        }

        FetchDecision(
            candidate = selected?.candidate,
            refreshAfterMs = refreshAfterMs
        )
    }

    private suspend fun fetchProviderWithBudget(
        provider: String,
        block: () -> LyricsProviderCandidate?
    ): ProviderAttempt {
        val started = SystemClock.elapsedRealtime()
        return try {
            val candidate = withTimeout(PROVIDER_BUDGET_MS) {
                runInterruptible(Dispatchers.IO) { block() }
            }
            ProviderAttempt(
                provider = provider,
                candidate = candidate,
                timedOut = false,
                elapsedMs = SystemClock.elapsedRealtime() - started
            )
        } catch (e: TimeoutCancellationException) {
            ProviderAttempt(
                provider = provider,
                candidate = null,
                timedOut = true,
                elapsedMs = SystemClock.elapsedRealtime() - started
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ProviderAttempt(
                provider = provider,
                candidate = null,
                timedOut = false,
                elapsedMs = SystemClock.elapsedRealtime() - started
            )
        }
    }

    private fun fetchFromPetitLyrics(track: TrackInfo): LyricsProviderCandidate? {
        if (!PetitLyricsClient.isConfigured) return null

        val result = try {
            PetitLyricsClient.getSyncedLyrics(
                album = track.album,
                artist = track.artist,
                title = track.title
            )
        } catch (_: Exception) {
            null
        } ?: return null

        val hasRealText = result.lines.any { it.text != "♪" && it.text.isNotBlank() }
        if (!hasRealText) return null

        val syncKind = when {
            result.lyricsType == 3 && result.lines.any { it.words.isNotEmpty() } ->
                LyricsProviderCandidate.SyncKind.WORD_SYNC
            else -> LyricsProviderCandidate.SyncKind.LINE_SYNC
        }

        return LyricsProviderCandidate(
            provider = "PetitLyrics",
            title = result.matchedTitle.ifBlank { track.title },
            artist = result.matchedArtist,
            album = result.matchedAlbum,
            durationSec = result.matchedDurationSec,
            lines = result.lines,
            status = LyricsStatus.FOUND,
            source = "PetitLyrics · Synced",
            syncKind = syncKind,
            artistQueryCorroborated = result.artistQueryCorroborated
        )
    }

    private fun fetchFromMusixmatch(track: TrackInfo): LyricsProviderCandidate? {
        val result = try {
            MusixmatchClient.getLyrics(track, prefs)
        } catch (_: Exception) {
            null
        } ?: return null

        val hasRealText = result.lines.any { it.text != "♪" && it.text.isNotBlank() }
        if (!hasRealText) return null

        val syncKind = when {
            result.isRichSync && result.lines.any { it.words.isNotEmpty() } ->
                LyricsProviderCandidate.SyncKind.WORD_SYNC
            else -> LyricsProviderCandidate.SyncKind.LINE_SYNC
        }

        return LyricsProviderCandidate(
            provider = "Musixmatch",
            title = result.matchedTitle.ifBlank { track.title },
            artist = result.matchedArtist,
            album = result.matchedAlbum,
            durationSec = result.matchedDurationSec,
            lines = result.lines,
            status = LyricsStatus.FOUND,
            source = if (result.isRichSync) "Musixmatch · RichSync" else "Musixmatch · Line",
            syncKind = syncKind,
            artistQueryCorroborated = result.artistQueryCorroborated
        )
    }

    private fun fetchFromSyncLrc(track: TrackInfo): LyricsProviderCandidate? {
        val result = try {
            SyncLrcClient.getKaraokeLyrics(track)
        } catch (_: Exception) {
            null
        } ?: return null

        val hasRealText = result.lines.any { it.text != "♪" && it.text.isNotBlank() }
        val hasWordTiming = result.lines.any { it.words.isNotEmpty() }
        if (!hasRealText || !hasWordTiming) return null

        return LyricsProviderCandidate(
            provider = "SyncLRC",
            title = result.matchedTitle.ifBlank { track.title },
            artist = result.matchedArtist,
            album = result.matchedAlbum,
            durationSec = result.matchedDurationSec,
            lines = result.lines,
            status = LyricsStatus.FOUND,
            source = "SyncLRC · Karaoke",
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC,
            artistQueryCorroborated = result.artistQueryCorroborated
        )
    }

    private fun fetchFromLrcLib(track: TrackInfo): LyricsProviderCandidate? {
        val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0

        val result = try {
            LrcLibClient.getLyrics(
                trackName = track.title,
                artistName = track.artist,
                albumName = track.album,
                durationSec = durationSec
            )
        } catch (_: Exception) {
            null
        } ?: return null

        if (result.syncedLyrics != null) {
            val lines = LrcParser.parse(result.syncedLyrics)
            val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
            if (hasRealText) {
                return LyricsProviderCandidate(
                    provider = "LRCLIB",
                    title = result.trackName.orEmpty().ifBlank { track.title },
                    artist = result.artistName.orEmpty(),
                    album = result.albumName.orEmpty(),
                    durationSec = result.duration,
                    lines = lines,
                    status = LyricsStatus.FOUND,
                    source = "LRCLIB · Synced",
                    syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
                )
            }
        }

        if (result.plainLyrics != null) {
            val lines = result.plainLyrics.lines()
                .filter { it.isNotBlank() }
                .map { text -> LyricLine(0L, text) }
            if (lines.isNotEmpty()) {
                return LyricsProviderCandidate(
                    provider = "LRCLIB",
                    title = result.trackName.orEmpty().ifBlank { track.title },
                    artist = result.artistName.orEmpty(),
                    album = result.albumName.orEmpty(),
                    durationSec = result.duration,
                    lines = lines,
                    status = LyricsStatus.PLAIN_ONLY,
                    source = "LRCLIB · Plain",
                    syncKind = LyricsProviderCandidate.SyncKind.PLAIN
                )
            }
        }

        return null
    }

    private fun handleKaraokePreferenceChanged() {
        val track = _state.value.track ?: return
        translationJob?.cancel()
        LyricsTranslator.resetUiState()
        _state.value = clearTranslationForLyricsVariantSwitch(_state.value)
        fetchLyrics(track)
    }

    private fun handleTranslationPreferenceChanged() {
        translationJob?.cancel()

        if (!prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)) {
            _state.value = _state.value.copy(
                translatedLines = null,
                detectedLanguage = null
            )
            return
        }

        val currentState = _state.value
        val track = currentState.track ?: return
        if (currentState.lines.isEmpty()) return
        if (currentState.status != LyricsStatus.FOUND &&
            currentState.status != LyricsStatus.PLAIN_ONLY
        ) {
            return
        }

        translateIfNeeded(currentState.lines, track)
    }

    private fun handleTranslationTargetChanged() {
        translationJob?.cancel()
        LyricsTranslator.resetUiState()
        _state.value = _state.value.copy(
            translatedLines = null,
            detectedLanguage = null
        )

        if (!prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)) return

        val currentState = _state.value
        val track = currentState.track ?: return
        if (currentState.lines.isEmpty()) return
        if (currentState.status != LyricsStatus.FOUND &&
            currentState.status != LyricsStatus.PLAIN_ONLY
        ) {
            return
        }

        translateIfNeeded(currentState.lines, track)
    }

    private fun selectedTargetLanguage(): String {
        return TranslationLanguages.normalizeTargetLanguage(
            prefs.getString(
                TranslationLanguages.TARGET_LANGUAGE_PREF_KEY,
                TranslationLanguages.DEFAULT_TARGET_LANGUAGE
            )
        )
    }

    private fun translateIfNeeded(lines: List<LyricLine>, track: TrackInfo) {
        if (!prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)) return
        val targetLanguage = selectedTargetLanguage()

        translationJob?.cancel()
        translationJob = scope.launch(Dispatchers.IO) {
            try {
                val result = LyricsTranslator.translateLines(
                    lines = lines,
                    targetLanguage = targetLanguage
                ) ?: return@launch

                withContext(Dispatchers.Main) {
                    if (_state.value.track == track &&
                        prefs.getBoolean(TRANSLATION_ENABLED_KEY, true) &&
                        selectedTargetLanguage() == targetLanguage &&
                        result.targetLanguage == targetLanguage
                    ) {
                        _state.value = _state.value.copy(
                            translatedLines = result.translatedLines,
                            detectedLanguage = result.detectedLanguage
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }

    companion object {
        private const val PROVIDER_BUDGET_MS = 5_000L
        private const val PROVISIONAL_CACHE_REFRESH_MS = 15L * 60 * 1000
        private const val PROVIDER_RESOLVER_TAG = "ProviderResolver"
        private const val TRANSLATION_ENABLED_KEY = "translation_enabled"
        private const val AA_KARAOKE_ENABLED_KEY = "aa_karaoke_enabled"

        @Volatile
        private var instance: MediaTracker? = null

        fun init(context: Context) {
            getInstance(context)
        }

        fun getInstance(context: Context): MediaTracker {
            return instance ?: synchronized(this) {
                instance ?: MediaTracker(context.applicationContext).also { instance = it }
            }
        }
    }
}
