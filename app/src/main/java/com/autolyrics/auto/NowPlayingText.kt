package com.autolyrics.auto

import com.autolyrics.model.LyricWord

/**
 * Pure (Android-free) text helpers for the Android Auto now-playing card.
 *
 * The AA host renders DISPLAY_TITLE and DISPLAY_SUBTITLE as one line each and ellipsizes
 * anything longer; there is no API for wrapping. So the text is sized here instead: a long
 * lyric line is split at word boundaries into chunks that fit a width budget, and the chunk
 * being sung is picked from the playback position.
 *
 * Widths are in "units": 1 per narrow (Latin) character, 2 per fullwidth / CJK / emoji glyph.
 * The karaoke brackets 【 】 are fullwidth, so they count 2 each.
 */
internal object NowPlayingText {

    /** Width of the karaoke bracket pair 【 + 】. */
    const val KARAOKE_BRACKETS_WIDTH = 4

    /** Shown in the subtitle when nothing follows (last line of the song). */
    const val END_MARK = "♪"

    private val WHITESPACE = Regex("\\s+")

    fun charWidth(codePoint: Int): Int {
        val type = Character.getType(codePoint)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.FORMAT.toInt()
        ) return 0
        return if (isWide(codePoint)) 2 else 1
    }

    private fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F ||   // Hangul Jamo initials
        cp in 0x2E80..0x303E ||   // CJK radicals, Kangxi, CJK symbols & punctuation (incl. 【】)
        cp in 0x3041..0x33FF ||   // Hiragana, Katakana, Bopomofo, CJK compatibility
        cp in 0x3400..0x4DBF ||   // CJK Extension A
        cp in 0x4E00..0x9FFF ||   // CJK Unified Ideographs
        cp in 0xA000..0xA4CF ||   // Yi
        cp in 0xAC00..0xD7A3 ||   // Hangul syllables
        cp in 0xF900..0xFAFF ||   // CJK compatibility ideographs
        cp in 0xFE30..0xFE4F ||   // CJK compatibility forms
        cp in 0xFF00..0xFF60 ||   // Fullwidth forms
        cp in 0xFFE0..0xFFE6 ||   // Fullwidth signs
        cp in 0x1F300..0x1F64F || // Emoji
        cp in 0x1F900..0x1F9FF || // Supplemental emoji
        cp in 0x20000..0x3FFFD    // CJK Extension B and later

    fun displayWidth(text: String): Int {
        var width = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            width += charWidth(cp)
            i += Character.charCount(cp)
        }
        return width
    }

    /**
     * Greedy word-boundary chunking. Returns index ranges into [words]; words inside a chunk
     * are joined with one space (1 unit). A single word wider than [budget] gets its own chunk
     * (the host ellipsizes it), so word indices stay aligned with ELRC timestamps.
     */
    fun chunkWords(words: List<String>, budget: Int): List<IntRange> {
        if (words.isEmpty()) return emptyList()
        val chunks = mutableListOf<IntRange>()
        var start = 0
        var width = displayWidth(words[0])
        for (i in 1 until words.size) {
            val w = displayWidth(words[i])
            if (width + 1 + w <= budget) {
                width += 1 + w
            } else {
                chunks.add(start until i)
                start = i
                width = w
            }
        }
        chunks.add(start until words.size)
        return chunks
    }

    /**
     * Splits free text (no word timestamps) into tokens: whitespace-separated words, with any
     * word wider than [budget] (e.g. a CJK line without spaces) hard-split by character.
     */
    fun tokenize(text: String, budget: Int): List<String> =
        text.trim().split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .flatMap { if (displayWidth(it) > budget) hardSplit(it, budget) else listOf(it) }

    private fun hardSplit(token: String, budget: Int): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var width = 0
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            val w = charWidth(cp)
            if (width + w > budget && sb.isNotEmpty()) {
                parts.add(sb.toString())
                sb.setLength(0)
                width = 0
            }
            sb.appendCodePoint(cp)
            width += w
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        return parts
    }

    /** Free text split into chunk strings that each fit [budget]. */
    fun chunkText(text: String, budget: Int): List<String> {
        val tokens = tokenize(text, budget)
        return chunkWords(tokens, budget).map { joinRange(tokens, it) }
    }

    /** Split a display line at whitespace where possible without rejoining its text. */
    fun chunkDisplayText(text: String, budget: Int): List<String> {
        if (text.isBlank() || budget <= 0) return emptyList()
        val matches = Regex("\\S+").findAll(text).toList()
        if (matches.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        var start = -1
        var end = -1
        var width = 0

        fun flush() {
            if (start >= 0 && end > start) chunks.add(text.substring(start, end))
            start = -1
            end = -1
            width = 0
        }

        for (match in matches) {
            val token = match.value
            val tokenWidth = displayWidth(token)
            if (tokenWidth > budget) {
                flush()
                val part = StringBuilder()
                var partWidth = 0
                var offset = 0
                while (offset < token.length) {
                    val cp = token.codePointAt(offset)
                    val cpWidth = charWidth(cp)
                    if (partWidth + cpWidth > budget && part.isNotEmpty()) {
                        chunks.add(part.toString())
                        part.setLength(0)
                        partWidth = 0
                    }
                    part.appendCodePoint(cp)
                    partWidth += cpWidth
                    offset += Character.charCount(cp)
                }
                if (part.isNotEmpty()) chunks.add(part.toString())
                continue
            }

            val gapWidth = if (end >= 0) displayWidth(text.substring(end, match.range.first)) else 0
            if (start >= 0 && width + gapWidth + tokenWidth > budget) flush()
            if (start < 0) {
                start = match.range.first
                width = tokenWidth
            } else {
                width += gapWidth + tokenWidth
            }
            end = match.range.last + 1
        }
        flush()
        return chunks
    }

    fun joinRange(tokens: List<String>, range: IntRange): String =
        tokens.subList(range.first, range.last + 1).joinToString(" ")

    /** Index of the last word whose timestamp is <= [posMs], or -1 before the first word. */
    fun currentWordIndex(words: List<LyricWord>, posMs: Long): Int {
        var idx = -1
        for (i in words.indices) {
            if (words[i].timeMs <= posMs) idx = i
            else break
        }
        return idx
    }

    /** ELRC: the chunk containing the word being sung (chunk 0 before the first word starts). */
    fun activeChunkByWordTime(chunks: List<IntRange>, wordIdx: Int): Int {
        if (chunks.isEmpty() || wordIdx < 0) return 0
        val i = chunks.indexOfFirst { wordIdx in it }
        return if (i >= 0) i else chunks.lastIndex
    }

    /**
     * No word timestamps: the line's time span [startMs, endMs) is divided across chunks
     * proportionally to their width, and the chunk covering [posMs] is returned.
     */
    fun activeChunkByProportion(widths: List<Int>, startMs: Long, endMs: Long, posMs: Long): Int {
        if (widths.size <= 1 || endMs <= startMs) return 0
        val total = widths.sum().coerceAtLeast(1)
        val elapsed = (posMs - startMs).coerceIn(0L, endMs - startMs)
        val progress = elapsed.toDouble() / (endMs - startMs) * total
        var acc = 0
        for (i in widths.indices) {
            acc += widths[i]
            if (progress < acc) return i
        }
        return widths.lastIndex
    }

    /**
     * Subtitle for the card while a lyric line is in the title. Priority:
     * 1. [translationChunk] — a translation of the current line replaces the "next" preview
     *    (it used to be appended after "\n", which a single-line field never showed);
     * 2. the next chunk of the same (long) line, so the continuation is readable;
     * 3. the next lyric line;
     * 4. [END_MARK] on the last line.
     */
    fun subtitleFor(
        titleChunks: List<String>,
        activeChunk: Int,
        translationChunk: String?,
        nextLineText: String?
    ): String {
        if (!translationChunk.isNullOrBlank()) return translationChunk
        titleChunks.getOrNull(activeChunk + 1)?.let { return it }
        if (nextLineText == null) return END_MARK
        return nextLineText.ifBlank { END_MARK }
    }
}

/**
 * Keeps a chunk on screen for at least [minDisplayMs] before advancing to a later one, so
 * estimated (proportional) chunk changes don't flicker. Going backwards (seek) and changing
 * line take effect immediately.
 */
internal class ChunkHold(private val minDisplayMs: Long) {
    private var lineIdx = -1
    private var shown = -1
    private var shownSinceMs = 0L

    fun select(lineIdx: Int, candidate: Int, nowMs: Long): Int {
        if (lineIdx != this.lineIdx || candidate < shown) {
            this.lineIdx = lineIdx
            shown = candidate
            shownSinceMs = nowMs
        } else if (candidate > shown && nowMs - shownSinceMs >= minDisplayMs) {
            shown = candidate
            shownSinceMs = nowMs
        }
        return shown
    }

    fun reset() {
        lineIdx = -1
        shown = -1
        shownSinceMs = 0L
    }
}

/**
 * Karaoke bracket builder with its own monotonic cache. The browse tree and the now-playing
 * card each own an instance so their different windows don't overwrite each other's state.
 *
 * Within the same line + chunk, the opening 【 and closing 】 only move forward (see
 * "Karaoke Bracket Logic" in DEVELOPMENT.md). A new line or chunk starts fresh.
 */
internal class KaraokeBracketState {
    private var lineIdx = -1
    private var chunkStart = -1
    private var wordIdx = -1
    private var text: String? = null

    fun reset() {
        lineIdx = -1
        chunkStart = -1
        wordIdx = -1
        text = null
    }

    /**
     * Words in [range] joined by spaces, with 【 before the word being sung and 】 after the
     * last word starting within [windowMs]. Brackets never leave [range]. Returns [fallback]
     * when no word of the chunk has started yet.
     */
    fun build(
        words: List<LyricWord>,
        lineIdx: Int,
        range: IntRange,
        posMs: Long,
        windowMs: Long,
        fallback: String
    ): String {
        if (words.isEmpty() || range.isEmpty()) return fallback
        val first = range.first
        val last = range.last.coerceAtMost(words.lastIndex)

        var currentIdx = NowPlayingText.currentWordIndex(words, posMs)
        if (currentIdx < first) return fallback
        currentIdx = minOf(currentIdx, last)

        val same = lineIdx == this.lineIdx && first == chunkStart
        val cached = text
        if (same && currentIdx <= wordIdx && cached != null) return cached

        var endIdx = currentIdx
        for (i in (currentIdx + 1)..last) {
            if (words[i].timeMs <= posMs + windowMs) endIdx = i
            else break
        }
        if (same) endIdx = maxOf(endIdx, wordIdx)

        this.lineIdx = lineIdx
        chunkStart = first
        wordIdx = currentIdx
        val sb = StringBuilder()
        for (i in first..last) {
            if (i == currentIdx) sb.append("【")
            sb.append(words[i].text)
            if (i == endIdx) sb.append("】")
            if (i < last) sb.append(" ")
        }
        return sb.toString().also { text = it }
    }
}
