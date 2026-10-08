# Auto Lyrics — Development Reference

This document describes the current implementation. Keep user-facing setup in `README.md`; keep release mechanics in `RELEASE.md`.

## Terminology

| Term | Meaning |
|---|---|
| **WORD_SYNC** | Synchronized lyrics with usable sub-line timing tokens. A token may be a word, syllable, fragment, or character. |
| **LINE_SYNC** | Synchronized lyrics with line timestamps but no usable sub-line timing tokens. |
| **Timing token** | Raw provider unit stored as `LyricWord(timeMs, text, endTimeMs?)`. |
| **Display grouping** | Renderer-only mapping from fine timing tokens to readable lexical ranges. Raw provider timing is not rewritten. |
| **Plain lyrics** | Unsynchronized text represented by `LyricsStatus.PLAIN_ONLY`. |
| **Standard mode** | Accuracy-first provider selection. |
| **Karaoke mode** | May prefer a near-equivalent candidate with real timing-token data. |
| **AA offset** | Android-Auto-specific delay stored as `aa_offset_ms`. |
| **Phone/global offset** | Main lyric offset managed by `lyrics_offset_ms`. |
| **Lyrics demand** | Whether provider resolution should be active. Demand is true while a phone Activity is started or Android Auto projection is connected. |

## Architecture

```text
Phone Activity lifecycle ────────────────┐
Android Auto CarConnection(PROJECTION) ──┤
                                          ▼
                                LyricsDemandController
                                          │
                                          │ active demand
                                          ▼
MediaListenerService (NotificationListenerService)
    │
    ├── continuously observes/selects active media sessions
    └── forwards the selected session only while demand is active
                                          │
                                          ▼
                               MediaTracker (singleton / StateFlow)
                                  ├─ active MediaController state
                                  ├─ LrcLibClient ─────────────┐
                                  ├─ MusixmatchClient ─────────┤ parallel
                                  ├─ PetitLyricsClient ────────┤ when configured
                                  ├─ SyncLrcClient ────────────┤ Karaoke mode only
                                  ├─ LyricsProviderResolver ◀──┘
                                  ├─ LyricsCache (STANDARD / KARAOKE variants)
                                  ├─ MetadataCleaner
                                  ├─ LrcParser
                                  ├─ KaraokeTiming
                                  ├─ LyricsTranslator
                                  └─ AlbumColorExtractor
                                          │
                     ┌────────────────────┴────────────────────┐
                     ▼                                         ▼
                  Phone UI                           LyricsBrowserService
            MainActivity / PerformanceActivity       Android Auto MediaBrowser
                     │                                + MediaSession
                     └─ LyricWordLayout                        └─ LyricWordLayout
```

`MediaListenerService` is a `NotificationListenerService`; notification access allows inspection of active media sessions. The selected playing session remains sticky while it is still playing so controller ordering changes do not steal selection.

## Demand gating

Provider resolution is intentionally not active all the time.

`LyricsDemandController` maintains two process-wide inputs:

- phone demand: at least one Auto Lyrics Activity is in the Android **started** lifecycle state,
- car demand: `CarConnection.CONNECTION_TYPE_PROJECTION` indicates an Android Auto projection session.

The effective state is:

```text
lyricsDemand = phoneActivityStarted || androidAutoProjectionConnected
```

Important behavior:

- The notification listener continues lightweight media-session discovery even when demand is false.
- When demand becomes true, `MediaListenerService` immediately re-reads active sessions and forwards the current best session to `MediaTracker`.
- When demand becomes false, `MediaTracker` is detached from the controller so later metadata changes do not start new provider work.
- An already-issued provider HTTP request is not forcibly cancelled by this gate; the change prevents subsequent track/session updates from starting more work.
- Android Auto demand remains active for the entire projection session, even while another Android Auto app is in the foreground.
- Existing `LyricsBrowserService` startup/discovery behavior is preserved; demand gating only controls lyric-resolution work.

This separation allows the app to know the current media session quickly when the phone UI is opened or Android Auto connects without continuously querying lyric providers while the app is otherwise unused.

## Track position and offsets

`MediaTracker` extracts title, artist, album, duration, artwork, playback state, and position from the active controller. Metadata is normalized by `MetadataCleaner`.

Track changes are debounced by 600 ms. During playback, position is reconstructed from the media session's reported position, last update time, and playback speed. Synced indices are checked every 150 ms.

`MediaTracker.getCurrentPositionMs()` includes the phone/global offset. Android Auto applies the additional `aa_offset_ms` in `LyricsBrowserService`.

Do **not** add a provider-wide pre-offset merely because lyrics appear early on one playback path. Media-session logical position can lead audible output because of Bluetooth, codec, Android Auto, DAC, or head-unit buffering. Stable output-path latency belongs in the existing offset controls unless a provider timestamp bias is demonstrated across multiple playback paths.

## Provider execution

Provider work starts only while lyrics demand is active.

Standard mode runs:

```text
LRCLIB ───────┐
Musixmatch ───┼─ parallel
PetitLyrics ──┘  when configured
```

Karaoke mode additionally runs:

```text
SyncLRC ──────┐
LRCLIB ───────┤
Musixmatch ───┼─ parallel
PetitLyrics ──┘  when configured
```

Each provider gets a hard **5 second total budget** at the `MediaTracker` boundary. Provider response time is not part of the score; a provider loses due to speed only when it misses its budget and produces no candidate.

Debug logging:

```powershell
adb logcat -s Musixmatch:D SyncLRC:D ProviderResolver:D PetitLyrics:D
```

## Provider resolution

Provider clients return normalized `LyricsProviderCandidate` objects. The base score is:

```text
final = metadata match    × 0.82
      + lyric quality     × 0.10
      + source confidence × 0.08
```

A synchronized candidate (`LyricsStatus.FOUND`) outranks a plain candidate. LRCLIB plain text remains the last-resort fallback.

### Standard vs Karaoke selection

Standard mode returns the synchronized candidate with the highest ordinary final score.

Karaoke mode first computes that same standard winner, then considers only candidates that:

- declare `WORD_SYNC`,
- actually contain `LyricWord` timing data,
- have metadata score no more than **0.03** below the standard winner,
- have lyric payload quality no more than **0.05** below the standard winner.

The highest-scoring candidate inside that near-equivalent WORD_SYNC set wins. This lets an equivalent Musixmatch, PetitLyrics, or SyncLRC result replace LRCLIB LINE_SYNC in Karaoke mode without allowing a materially weaker live/remix/mismatched recording to win solely because it has richer timing.

### Metadata scoring

Approximate metadata weighting:

```text
title     55%
artist    30%
duration  12% when reliable
album      3%
```

Weights are renormalized when a field is unavailable. PetitLyrics duration is not trusted in common resolver scoring; LRCLIB, Musixmatch, and SyncLRC duration may be used when present.

Romanized player metadata can be compared against native Japanese provider metadata. For a near-exact title, a cross-script artist mismatch can be neutralized only with independent evidence such as an artist-constrained query, strong album similarity, or strong reliable-duration similarity.

Multi-contributor player artist strings are preserved. For near-exact titles, contributor-aware comparison can match one comma/semicolon-delimited contributor rather than truncating the media metadata globally.

### Payload quality and source confidence

Payload quality checks include suspicious Japanese/Latin alternation, near-duplicate transliteration timestamps, timing far beyond track duration, unusually short timing coverage, and very late first timestamps.

Source confidence is intentionally only 8% of the final score. Current policy strongly favors PetitLyrics Type 3/2 for Japanese and LRCLIB synchronized data for non-Japanese tracks. Musixmatch and SyncLRC use generic synchronized-provider confidence unless a more specific policy is justified later.

## Timing tokens vs display grouping

Provider granularity is preserved independently from display granularity.

Example:

```text
raw timing:  Pro @1000 / vi @1100 / der @1200
source text: Provider
highlight:   Provider
```

Japanese data can be character-granular:

```text
raw timing: 君 / を / 忘 / れ / な / い
```

`LyricWordLayout` aligns timing tokens back to `LyricLine.text`, uses Java/Android word boundaries, and applies a conservative Japanese display heuristic that merges adjacent hiragana suffixes into a preceding kanji-containing range. The intended display is closer to `君を / 忘れない` than six separate character highlights.

This is a display heuristic, not a morphological analyzer. The original timing tokens remain intact in state and cache. If tokens cannot be aligned to the source line, the previous separator reconstruction is used as a compatibility fallback.

## LRCLIB

LRCLIB starts with constrained metadata and relaxes discovery when necessary. Returned records are re-ranked locally using title, contributor-aware artist matching, album, duration, and version qualifiers.

Synced LRC becomes `LINE_SYNC`. Plain lyrics can be returned as `LyricsStatus.PLAIN_ONLY` only when no acceptable synchronized result exists.

## Musixmatch

Musixmatch uses the anonymous mobile API rather than the desktop endpoint, because runtime testing found the desktop path could return unrelated fixed tracks despite successful status.

Flow:

```text
token.get
    ↓
macro.subtitles.get
    ├── matcher.track.get
    ├── track.richsync.get
    └── track.subtitles.get
```

The provider reads the explicit `matcher.track.get` result and validates it locally before accepting lyric payloads. One token refresh/retry is allowed after authentication rejection.

RichSync timing is:

```text
token timestamp = line ts + chunk o
```

RichSync chunks remain raw `LyricWord` timing tokens. If RichSync is absent or unusable, line-synced subtitle LRC becomes `LINE_SYNC`.

Musixmatch uses an unofficial/internal endpoint and can change independently of Auto Lyrics.

## PetitLyrics

Supported formats:

- **lyricsType=3 / WSY** — each `<word>` contributes `starttime`, optional `endtime`, and raw `wordstring` to `LyricWord`.
- **lyricsType=2 / LSY** — binary line timing combined with a **lyricsType=1** plain-text companion, preferably resolved by the same `lyricsId`.

Type 3 does not guarantee linguistic words. Japanese may be character-granular and English may contain fragments. Those raw elements are retained and grouped only at render time.

`endTimeMs` persists through `LyricsCache` and is honored by `KaraokeTiming`. If one token ends before the next starts, the gap has no active highlight.

Search progressively relaxes:

```text
title + artist + album
        ↓
title + artist
        ↓
title only
```

The search stage is preserved through `artistQueryCorroborated`, so a title-only fallback does not receive the same cross-script evidence as an artist-constrained query.

PetitLyrics uses an internal/unofficial endpoint and requires local build configuration.

## SyncLRC

`SyncLrcClient` is Karaoke-mode-only. It consumes the current public API:

```text
GET https://api.synclrc.dev/lyrics
    ?track=<title>
    &artist=<artist>
    &type=karaoke
    [&album=<album>]
    [&duration=<rounded seconds>]
```

The current public response shape exposes canonical metadata plus separate lyric fields:

```json
{
  "id": "...",
  "track": "Song",
  "artist": "Artist",
  "album": "Album",
  "duration": 215,
  "instrumental": false,
  "karaoke": "[00:00.00]<00:00.05>...",
  "synced": "[00:00.00]...",
  "plain": "..."
}
```

Auto Lyrics accepts only a non-empty `karaoke` payload. For compatibility with older SyncLRC deployments, `SyncLrcClient` can also read the former type-specific shape:

```json
{
  "type": "karaoke",
  "lyrics": "[00:00.00]<00:00.05>..."
}
```

Legacy `type=synced/plain` bodies and current responses without a Karaoke field are rejected for this provider. Direct LRCLIB already covers LINE_SYNC/plain and should remain the canonical path for those formats.

SyncLRC documents Karaoke as Enhanced LRC and uses LRCLIB plus an LDDC/syncedlyrics-backed stack for sources including NetEase, QQ Music, Kugou, and Musixmatch. Auto Lyrics calls the public JSON service only; it does not copy or embed SyncLRC/LDDC source code.

SyncLRC uses the resolver's generic synchronized-provider confidence. It is not granted an unconditional preference over direct providers.

### Enhanced LRC parser

`LrcParser.parseKaraoke()` accepts line timestamps such as `[00:01.00]` and timing-token timestamps such as `<00:01.25>`. Dot or colon fractional separators are tolerated.

Timing tags are removed to recover exact `LyricLine.text`. Every raw substring between timing tags becomes a `LyricWord`; attached whitespace is retained so the original line can be reconstructed exactly. Line-only LRC produces no fake timing tokens.

## PetitLyrics local configuration

Copy the tracked template:

```powershell
Copy-Item .env.example .env
```

Fill in:

```dotenv
PETITLYRICS_USER_ID=your-user-id
PETITLYRICS_APP_NAME=your-app-name
PETITLYRICS_PKG_NAME=your-package-name
PETITLYRICS_CLIENT_APP_ID=your-client-app-id
```

Configuration order:

```text
process environment
    ↓ if missing/blank
.env
    ↓ only when .env itself does not exist
.env.example
```

If a required value is blank, PetitLyrics is skipped. These values are compiled into Android `BuildConfig`; keeping them out of Git does not make them secret in a distributed APK.

## Cache policy

Cache identity includes:

```text
namespace v13
STANDARD or KARAOKE variant
normalized title
normalized artist
normalized album
rounded duration seconds
```

`LyricsCache` persists `LyricWord(timeMs, text, endTimeMs)`, so RichSync, PetitLyrics Type 3, and Enhanced-LRC timing survive restart/reload.

Mode-specific cache variants prevent a Karaoke WORD_SYNC winner from pinning standard mode and prevent a standard LRCLIB LINE_SYNC winner from hiding a richer Karaoke result.

Refresh policy:

- normal fully corroborated LRCLIB/PetitLyrics comparison: **7 days**,
- incomplete/provisional comparison: **15 minutes**,
- Karaoke with a usable selected WORD_SYNC result and otherwise complete normal comparison: **7 days**,
- Karaoke with no usable selected WORD_SYNC result: **15 minutes**.

The last rule is important for SyncLRC/LDDC-style aggregation: a temporary upstream miss should not pin LINE_SYNC in the Karaoke cache for a week.

Provider clients still collapse some `NOT_FOUND` and transient-error outcomes to `null`; this can cause legitimate one-provider songs to refresh more often, but it avoids long-lived stale fallbacks.

## Karaoke active-token timing

`KaraokeTiming.activeWordIndex()` is shared by phone/Performance and Android Auto.

- Without explicit end times, the latest token whose `timeMs <= position` is active.
- With `endTimeMs`, a token stops at that explicit end.
- A gap between explicit end and the next token has no active highlight.
- An ended newer token never reactivates an older open-ended token.
- Backward seeking within a line can select an earlier token again.

Timing selection and display grouping are independent: `KaraokeTiming` chooses the raw token index, then `LyricWordLayout` chooses the visible lexical range.

## Android Auto

`LyricsBrowserService` exposes:

```text
Lyrics | Sync | More
```

Lyrics is first/default. The main browse window shows 5 rows without translations and 3 with translations. Sync is a fixed 3-row timing preview with ±50 ms AA offset controls. More shows provider and metadata details.

Android Auto projection activates `LyricsDemandController` for the entire projection session. Provider resolution therefore remains available even if the user navigates from Auto Lyrics to another Android Auto app; it stops only after projection disconnects unless the phone UI is still active.

Future-word look-ahead has been removed. The browse tree remains throttled (`NOTIFY_THROTTLE_MS = 500 ms`), so AA visual transitions can be coarser or slightly late compared with phone/Performance; they are no longer intentionally advanced. The now-playing subtitle is checked every 200 ms.

Other relevant constants:

| Constant | Value |
|---|---:|
| `SYNC_WINDOW_SIZE` | 3 |
| `DEFAULT_WINDOW_SIZE` | 5 |
| `CURRENT_LINE_PREFIX` | `▶  ` |
| `PAD_WIDTH` | 60 |
| `NOTIFY_THROTTLE_MS` | 500 ms |
| `SESSION_REFRESH_MS` | 1500 ms |
| `PLAIN_LOOP_DELAY_MS` | 2000 ms |

## Now-playing Card Text

The Android Auto now-playing card shows the current lyric in DISPLAY_TITLE and what follows in DISPLAY_SUBTITLE. Long lines are chunked at word boundaries to fit width budgets.

| Constant | Value | Purpose |
|---|---:|---|
| TITLE_MAX_WIDTH | 28 | Max display width for DISPLAY_TITLE (Title font is larger) |
| SUBTITLE_MAX_WIDTH | 32 | Max display width for DISPLAY_SUBTITLE |
| MIN_CHUNK_DISPLAY_MS | 1000 ms | Minimum display time for non-ELRC chunks |
| DEFAULT_LAST_LINE_MS | 5000 ms | Assumed duration of last synced line when track duration unknown |
| BROWSE_KARAOKE_WINDOW_MS | 600 ms | Karaoke bracket look-ahead for browse tree |
| TITLE_KARAOKE_WINDOW_MS | 300 ms | Karaoke bracket look-ahead for now-playing title |

Width calculation: 1 unit per Latin character, 2 per fullwidth/CJK character. Karaoke brackets 【】 count as 2 each.

Architecture:
- CardText(lyricTitle, subtitle) holds the computed text for the now-playing card
- getCardText(state) delegates to getLyricCardText() for active lyrics, or returns status text for intro/loading/error states
- getLyricCardText(state) handles chunking logic: find active line, chunk to TITLE_MAX_WIDTH with karaoke brackets if ELRC, select chunk by word time or proportion
- uildBaseMetadata(state, lyricTitle) sets DISPLAY_TITLE to lyricTitle when provided, else "Title — Artist"
- Separate karaoke caches: rowseKaraoke (600ms window) for browse tree, 
owPlayingKaraoke (300ms window) for now-playing
- ChunkHold prevents flicker on estimated (non-ELRC) chunk changes

## GitHub Actions

`.github/workflows/build.yml` runs on pull requests to `main`, pushes to `main`, `v*` tags, and manual `workflow_dispatch`.

Linux `build` job behavior:

- pull request / ordinary main push: unit tests, lint, debug APK assembly,
- release trigger: unit tests, lint, release APK assembly, artifact upload, GitHub Release creation.

A release trigger is either:

- a `v*` tag push, or
- a `main` commit whose message starts with `Release v`.

The Windows regression job runs only for `pull_request` and `workflow_dispatch`. It runs `gradlew.bat` unit tests, debug assembly, and lint. It is intentionally skipped on ordinary main pushes and release pushes.

Release workflow details are in `RELEASE.md`.

## Regression checklist

Run before merging provider/resolver changes:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

Automated coverage should retain:

- lyrics-demand phone Activity counting and Android Auto projection behavior,
- cross-script artist corroboration and same-title mismatch rejection,
- multi-contributor artist matching,
- Japanese/Latin interleaved payload quality penalty,
- standard accuracy-first selection,
- Karaoke near-equivalent real-WORD_SYNC preference,
- PetitLyrics Type 3 start/end/token parsing,
- PetitLyrics Type 2 + Type 1 companion handling,
- explicit word-end gaps,
- Musixmatch token/matcher/RichSync regressions,
- Enhanced-LRC exact spacing and absolute timestamps,
- current SyncLRC `karaoke` field parsing,
- legacy SyncLRC type-specific compatibility,
- rejection of SyncLRC synced/plain-only responses,
- fine English fragment → source-word display grouping,
- Japanese character timing → word-like display grouping without mutating raw timing,
- synchronized candidate preference over plain lyrics.

Manual Phone / DHU checks:

1. With Android Auto disconnected and Auto Lyrics closed, changing tracks in another media app does not start new provider resolution.
2. Opening Auto Lyrics on the phone immediately resolves the currently selected session.
3. Connecting Android Auto activates provider resolution without requiring the phone UI to remain open.
4. Lyrics / Sync / More remain ordered correctly.
5. Window sizes and translation layout remain correct.
6. `▶` gutter alignment remains stable.
7. Sync stays at 3 rows and ±50 ms changes AA offset.
8. More displays provider/details without changing timing.
9. Plain lyrics still advance.
10. Karaoke source text retains correct spacing for English, Japanese, and mixed scripts.
11. No future timing token is highlighted solely to hide browse latency.
12. Karaoke ON can select an equivalent word-timed provider while Karaoke OFF independently retains the standard winner.

## Key files

| File | Purpose |
|---|---|
| `app/src/main/java/com/autolyrics/AutoLyricsApp.kt` | Process initialization, phone Activity demand tracking, Android Auto `CarConnection` tracking. |
| `app/src/main/java/com/autolyrics/media/LyricsDemandController.kt` | Process-wide phone/Android-Auto demand state. |
| `app/src/main/java/com/autolyrics/media/MediaListenerService.kt` | Media-session discovery/selection and demand-gated forwarding to `MediaTracker`. |
| `app/src/main/java/com/autolyrics/media/MediaTracker.kt` | Media tracking, provider concurrency, resolver orchestration, cache refresh. |
| `app/src/main/java/com/autolyrics/lyrics/LrcLibClient.kt` | LRCLIB lookup and local matching. |
| `app/src/main/java/com/autolyrics/lyrics/MusixmatchClient.kt` | Musixmatch mobile token/macro/RichSync/subtitle flow. |
| `app/src/main/java/com/autolyrics/lyrics/PetitLyricsClient.kt` | PetitLyrics Type 3 token timing and Type 2 line timing. |
| `app/src/main/java/com/autolyrics/lyrics/SyncLrcClient.kt` | Karaoke-only SyncLRC public API integration. |
| `app/src/main/java/com/autolyrics/lyrics/LrcParser.kt` | Standard and Enhanced-LRC parsing. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsProviderResolver.kt` | Metadata/quality/source scoring and mode-aware selection. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsCache.kt` | STANDARD/KARAOKE persistent cache. |
| `app/src/main/java/com/autolyrics/lyrics/KaraokeTiming.kt` | Shared raw-token active-index selection. |
| `app/src/main/java/com/autolyrics/util/LyricWordLayout.kt` | Exact source layout and timing-token → display-range grouping. |
| `app/src/main/java/com/autolyrics/auto/LyricsBrowserService.kt` | Android Auto MediaBrowser, MediaSession, Sync controls, Karaoke rendering. |
| `.github/workflows/build.yml` | CI and release build. |
| `RELEASE.md` | Release checklist, version/tag invariants, and release triggers. |
