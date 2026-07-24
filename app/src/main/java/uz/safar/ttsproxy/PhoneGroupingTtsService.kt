package uz.safar.ttsproxy

import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TTS proxy engine: groups phone number digits into readable blocks before
 * delegating the actual speech synthesis to RHVoice.
 *
 * RHVoice must be installed on the device; this service performs no synthesis
 * of its own, it only pre-processes text and forwards it.
 */
class PhoneGroupingTtsService : TextToSpeechService() {

    private data class VoiceDef(val name: String, val locale: Locale)

    companion object {
        private const val TAG = "PhoneGroupingTts"
        private const val RHVOICE_PACKAGE = "com.github.olga_yakovleva.rhvoice.android"
        // RHVoice may have been killed by the OS (battery/idle management) since the
        // last utterance. Waking its process + rebinding can take noticeably longer
        // than a warm init, so give it real room instead of failing fast.
        private const val ENGINE_INIT_TIMEOUT_SEC = 12L
        private const val SYNTH_TIMEOUT_SEC = 15L
        private const val MIN_VALID_WAV_SIZE = 44L
        // How many words the very first synthesized chunk is capped at, to get
        // audio playing as fast as possible. Lower = faster start but choppier
        // prosody on the first fragment; higher = smoother but slower start.
        private const val FIRST_CHUNK_MAX_WORDS = 4

        // Must match the <voice android:name="..."/> entries in res/xml/tts_engine.xml
        private val VOICES = listOf(
            VoiceDef("uzb", Locale("uz")),
            VoiceDef("rus", Locale("ru")),
            VoiceDef("eng", Locale.ENGLISH)
        )
        private val DEFAULT_VOICE = VOICES[0]
        private val RUSSIAN_LOCALE = VOICES.first { it.name == "rus" }.locale

        private fun findVoice(code: String?): VoiceDef? {
            if (code.isNullOrBlank()) return null
            val normalized = code.trim().lowercase(Locale.ROOT)
            return VOICES.firstOrNull {
                it.name == normalized ||
                    it.locale.language.equals(normalized, ignoreCase = true) ||
                    runCatching { it.locale.isO3Language }.getOrNull()?.equals(normalized, ignoreCase = true) == true
            }
        }
    }

    private lateinit var engine: TextToSpeech
    @Volatile private var engineReady = false
    @Volatile private var stopRequested = false
    @Volatile private var activeLocale: Locale = DEFAULT_VOICE.locale
    @Volatile private var appliedLocale: Locale? = null
    @Volatile private var initLatch = CountDownLatch(1)

    override fun onCreate() {
        super.onCreate()
        connectEngine()
    }

    /** (Re)binds to the RHVoice engine. Safe to call again if a previous connection went stale. */
    private fun connectEngine() {
        engineReady = false
        appliedLocale = null
        initLatch = CountDownLatch(1)
        engine = TextToSpeech(this, { status ->
            engineReady = status == TextToSpeech.SUCCESS
            if (!engineReady) {
                Log.w(TAG, "Underlying RHVoice engine failed to initialize (status=$status)")
            }
            initLatch.countDown()
        }, RHVOICE_PACKAGE)
    }

    override fun onDestroy() {
        if (::engine.isInitialized) {
            runCatching { engine.shutdown() }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Legacy language-based API (still required, always invoked on API 21+
    // for clients that haven't migrated to the Voice API).
    // ---------------------------------------------------------------------

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (findVoice(lang) != null) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val voice = findVoice(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        activeLocale = voice.locale
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> = try {
        val locale = activeLocale
        arrayOf(locale.isO3Language, locale.isO3Country, "")
    } catch (e: Exception) {
        Log.w(TAG, "Failed to resolve ISO3 locale, falling back to English", e)
        arrayOf("eng", "", "")
    }

    // ---------------------------------------------------------------------
    // Modern Voice-based API.
    // ---------------------------------------------------------------------

    override fun onGetVoices(): MutableList<Voice> =
        VOICES.map { v ->
            Voice(
                v.name,
                v.locale,
                Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL,
                /* requiresNetworkConnection = */ false,
                emptySet()
            )
        }.toMutableList()

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String =
        findVoice(lang)?.name ?: DEFAULT_VOICE.name

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (VOICES.any { it.name == voiceName }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int {
        val voice = VOICES.firstOrNull { it.name == voiceName } ?: return TextToSpeech.ERROR
        activeLocale = voice.locale
        return TextToSpeech.SUCCESS
    }

    override fun onStop() {
        stopRequested = true
        if (::engine.isInitialized) {
            runCatching { engine.stop() }
        }
    }

    // ---------------------------------------------------------------------
    // Text pre-processing.
    // ---------------------------------------------------------------------

    /** Apostrophe-like characters used to write the Uzbek digraph letters
     *  gʻ/oʻ - different keyboards/fonts use different code points for these. */
    private val UZBEK_DIGRAPH_MARKS = setOf('\'', '\u2018', '\u2019', '\u02BB', '\u02BC', '`')

    /** True if [word] is a single Uzbek "letter" for keyboard-announcement purposes:
     *  either one plain letter, or a letter immediately followed by an apostrophe-like
     *  mark (the gʻ/oʻ digraphs, which are two Unicode code points but one letter). */
    private fun isKeyboardLetterUnit(word: String): Boolean {
        if (word.length == 1) return word[0].isLetter()
        if (word.length == 2) return word[0].isLetter() && word[1] in UZBEK_DIGRAPH_MARKS
        return false
    }

    /**
     * True if [text] is a screen reader's "you're touching key X" keyboard
     * announcement: either just one letter ("A", "gʻ"), or TalkBack's capital-letter
     * form "Katta <letter>" ("Katta A", "Katta Gʻ"). Requires the literal word
     * "Katta" so it doesn't misfire on ordinary two-word text.
     */
    private fun isSingleLetterAnnouncement(text: String): Boolean {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (words.size) {
            1 -> isKeyboardLetterUnit(words[0])
            2 -> words[0].equals("Katta", ignoreCase = true) && isKeyboardLetterUnit(words[1])
            else -> false
        }
    }

    /**
     * True if [text] is a screen reader's "you're touching key X" keyboard
     * BUTTON announcement, like "Bo'shliq tugmasi" or "Backspace tugmasi" (some
     * keyboards, e.g. Samsung's, describe non-letter keys this way instead of
     * just naming them). This pattern essentially never occurs in ordinary
     * sentences, so matching on it is safe.
     */
    private fun isKeyboardButtonAnnouncement(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length > "tugmasi".length && trimmed.endsWith("tugmasi", ignoreCase = true)
    }

    /**
     * Uzbek Latin -> RUSSIAN Cyrillic single-letter map, used only for keyboard-
     * letter announcements. RHVoice apparently pronounces a bare Latin ASCII
     * letter the same way regardless of the active voice/locale (it's treated
     * as a generic symbol name, not language-specific text) - but genuine
     * Cyrillic text is confirmed to trigger the real Russian voice. So instead
     * of sending "a" to the Russian voice and hoping it sounds different, we
     * send its Cyrillic counterpart "а", which the Russian voice reliably
     * renders.
     *
     * Only real Russian alphabet letters are used here (а-я, ё) - Uzbek Cyrillic
     * has a few extra letters (ў, ғ, қ, ҳ) that don't exist in Russian, so those
     * map to their closest Russian sound instead (q/h -> к/х, the oʻ/gʻ digraphs
     * -> о/г) rather than to a letter the Russian voice wouldn't recognize.
     */
    private val LATIN_TO_CYRILLIC_LETTER = mapOf(
        'a' to 'а', 'b' to 'б', 'd' to 'д', 'e' to 'е', 'f' to 'ф',
        'g' to 'г', 'h' to 'х', 'i' to 'и', 'j' to 'ж', 'k' to 'к',
        'l' to 'л', 'm' to 'м', 'n' to 'н', 'o' to 'о', 'p' to 'п',
        'q' to 'к', 'r' to 'р', 's' to 'с', 't' to 'т', 'u' to 'у',
        'v' to 'в', 'x' to 'х', 'y' to 'й', 'z' to 'з', 'c' to 'к'
    )

    /** Transliterates one keyboard "letter unit" (a bare letter, or a letter +
     *  Uzbek digraph mark like "gʻ") into Cyrillic, preserving case. Falls back
     *  to the original text unchanged if there's no mapping for it. */
    private fun toCyrillicLetterForVoice(letterUnit: String): String {
        if (letterUnit.length == 2 && letterUnit[1] in UZBEK_DIGRAPH_MARKS) {
            // oʻ/gʻ have no Russian equivalent - drop the digraph mark and use
            // the closest plain Russian letter instead of an Uzbek-only one.
            val cyrBase = when (letterUnit[0].lowercaseChar()) {
                'o' -> 'о'
                'g' -> 'г'
                else -> return letterUnit
            }
            return if (letterUnit[0].isUpperCase()) cyrBase.uppercaseChar().toString() else cyrBase.toString()
        }
        if (letterUnit.length == 1) {
            val c = letterUnit[0]
            val cyr = LATIN_TO_CYRILLIC_LETTER[c.lowercaseChar()] ?: return letterUnit
            return if (c.isUpperCase()) cyr.uppercaseChar().toString() else cyr.toString()
        }
        return letterUnit
    }

    /** Applies [toCyrillicLetterForVoice] to the letter word inside a detected
     *  keyboard-letter announcement (leaving a "Katta" prefix, if present, as is). */
    private fun transliterateKeyboardAnnouncement(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (words.size) {
            1 -> toCyrillicLetterForVoice(words[0])
            2 -> "${words[0]} ${toCyrillicLetterForVoice(words[1])}"
            else -> text
        }
    }

    /** True if [text] contains any Cyrillic-script character (U+0400-U+04FF), used
     *  to detect Russian text embedded in an otherwise Uzbek/Latin utterance. */
    private fun containsCyrillic(text: String): Boolean = text.any { it.code in 0x0400..0x04FF }

    private fun groupPhoneNumbers(text: String): String {
        var result = text
        // +998 followed by 9 digits -> +998 XX XXX XX XX
        result = Regex("(?<!\\d)\\+998(\\d{2})(\\d{3})(\\d{2})(\\d{2})(?!\\d)").replace(result) { match ->
            withLetterBoundarySpacing(result, match, "+998 ${match.groupValues[1]} ${match.groupValues[2]} ${match.groupValues[3]} ${match.groupValues[4]}")
        }
        // bare 9-digit local numbers -> XX XXX XX XX
        result = Regex("(?<!\\d)(\\d{2})(\\d{3})(\\d{2})(\\d{2})(?!\\d)").replace(result) { match ->
            withLetterBoundarySpacing(result, match, "${match.groupValues[1]} ${match.groupValues[2]} ${match.groupValues[3]} ${match.groupValues[4]}")
        }
        return result
    }

    /**
     * If a matched phone-number run is glued directly to a letter on either side
     * (no whitespace/punctuation separator, e.g. "tel901234567gacha"), inserts a
     * space at that edge. Otherwise the grouped digits stay fused to the letter
     * (e.g. "tel90" or "67ga") and RHVoice mispronounces them as one token.
     */
    private fun withLetterBoundarySpacing(source: String, match: MatchResult, core: String): String {
        val precededByLetter = match.range.first > 0 && source[match.range.first - 1].isLetter()
        val followedByLetter = match.range.last + 1 < source.length && source[match.range.last + 1].isLetter()
        return buildString {
            if (precededByLetter) append(' ')
            append(core)
            if (followedByLetter) append(' ')
        }
    }

    /**
     * Splits text at sentence/clause punctuation so the first chunk can be
     * synthesized and streamed quickly instead of waiting on the whole utterance.
     *
     * The very first chunk is further capped to a few words (FIRST_CHUNK_MAX_WORDS).
     * Without this, a long first sentence with no early punctuation would force the
     * user to wait for the *entire* sentence to be synthesized before any audio
     * plays. Shrinking only the first piece gets sound out almost immediately,
     * while later chunks stay at normal (punctuation-based) size so we don't pay
     * per-call RHVoice overhead many times over for the rest of the text.
     */
    private fun splitIntoChunks(text: String): List<String> {
        val parts = text.split(Regex("(?<=[.!?;:,])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return listOf(text).filter { it.isNotEmpty() }

        val first = parts[0]
        val firstWords = first.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (firstWords.size <= FIRST_CHUNK_MAX_WORDS) return parts

        val head = firstWords.take(FIRST_CHUNK_MAX_WORDS).joinToString(" ")
        val tail = firstWords.drop(FIRST_CHUNK_MAX_WORDS).joinToString(" ")
        return buildList {
            add(head)
            if (tail.isNotEmpty()) add(tail)
            addAll(parts.drop(1))
        }
    }

    /** Renders [this] as a quoted string plus each character's hex code point,
     *  so invisible/whitespace characters are visible in logs (e.g. a trailing
     *  space or zero-width character that would otherwise look identical to a
     *  bare letter but breaks the single-letter detection). Debug-only. */
    private fun String.toCodePointDebugString(): String {
        val codes = this.map { "U+%04X".format(it.code) }.joinToString(" ")
        return "\"$this\" [$codes]"
    }

    // ---------------------------------------------------------------------
    // Synthesis.
    // ---------------------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false

        if (!engineReady) {
            initLatch.await(ENGINE_INIT_TIMEOUT_SEC, TimeUnit.SECONDS)
        }
        if (!engineReady) {
            // RHVoice likely got killed/frozen by the OS while idle and the first
            // bind attempt above hung or failed. Rebuild the connection and give
            // it one more real chance before giving up on this utterance.
            Log.w(TAG, "RHVoice not ready after ${ENGINE_INIT_TIMEOUT_SEC}s, retrying connection")
            runCatching { engine.shutdown() }
            connectEngine()
            initLatch.await(ENGINE_INIT_TIMEOUT_SEC, TimeUnit.SECONDS)
        }
        if (!engineReady) {
            Log.e(TAG, "RHVoice engine is not ready, cannot synthesize")
            callback.error()
            return
        }

        val rawText = request.charSequenceText.toString()
        val isKeyboardLetter = isSingleLetterAnnouncement(rawText)
        val isKeyboardButton = isKeyboardButtonAnnouncement(rawText)
        Log.d(TAG, "onSynthesizeText raw=${rawText.toCodePointDebugString()} singleLetter=$isKeyboardLetter button=$isKeyboardButton")

        val requestedBaseLocale = applyRequestSettings(request)
        // A screen reader announces individual keys while the user explores a
        // keyboard by touch (e.g. "A", "B", "V"... or "Bo'shliq tugmasi") as
        // short, distinctly-shaped utterances. Route those straight to the
        // Russian voice regardless of whether the text itself is Latin or
        // Cyrillic, or which keyboard layout is active - this is specifically
        // for that key-by-key case, not for normal words in running text.
        val baseLocale = if (isKeyboardLetter || isKeyboardButton) {
            ensureLocale(RUSSIAN_LOCALE)
            RUSSIAN_LOCALE
        } else {
            requestedBaseLocale
        }

        // Chunk the RAW text first: an ungrouped phone number has no internal
        // whitespace, so it's treated as a single "word" and can never be cut
        // in half by the word-count cap below. Only after chunking do we insert
        // the spaces that group its digits - otherwise those spaces themselves
        // could fall right at a chunk boundary and split the number apart.
        // For a keyboard-letter announcement, transliterate to Cyrillic first:
        // RHVoice pronounces a bare Latin ASCII letter the same way regardless
        // of active voice (treated as a generic symbol name), but real Cyrillic
        // text reliably triggers the actual Russian voice.
        val textToChunk = if (isKeyboardLetter) transliterateKeyboardAnnouncement(rawText) else rawText
        val chunks = splitIntoChunks(textToChunk).map { groupPhoneNumbers(it) }
        if (chunks.isEmpty()) {
            callback.error()
            return
        }

        var started = false
        var segmentCounter = 0

        for ((index, chunk) in chunks.withIndex()) {
            if (stopRequested) break

            // Within one punctuation chunk, Latin and Cyrillic words can still be
            // mixed (e.g. "Salom, привет do'stim"). Split further into runs of
            // consecutive same-script words so each run gets its own voice.
            // Digit-only "words" (like an already-grouped phone number's "90",
            // "123"...) carry no script of their own, so they merge into whatever
            // run they're adjacent to instead of forcing a break - this keeps a
            // phone number intact as one synthesis call, same as before.
            for (segment in splitByScript(chunk)) {
                if (stopRequested) break
                val text = segment.text
                if (text.isBlank()) continue

                ensureLocale(if (segment.hasCyrillic) RUSSIAN_LOCALE else baseLocale)

                val outFile = File(cacheDir, "tts_${System.nanoTime()}_${index}_${segmentCounter++}.wav")
                try {
                    if (synthesizeChunkToFile(text, outFile)) {
                        if (stopRequested) break
                        if (outFile.exists() && outFile.length() > MIN_VALID_WAV_SIZE) {
                            if (streamWavToCallback(outFile, callback, isFirstChunk = !started)) {
                                started = true
                            }
                        } else {
                            Log.w(TAG, "Chunk $index produced no usable audio, skipping")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to synthesize/stream chunk $index", e)
                } finally {
                    outFile.delete()
                }
            }
        }

        if (!started) {
            callback.error()
        } else {
            callback.done()
        }
    }

    private data class ScriptSegment(val text: String, val hasCyrillic: Boolean)

    /**
     * Splits [chunk] into runs of consecutive whitespace-separated words that
     * share the same script (Cyrillic vs. not), so mixed-script text gets routed
     * to the right voice word-by-word rather than one voice for the whole chunk.
     * A digit-only word has no script of its own and simply extends the current
     * run, so a formatted phone number's digit groups never get split apart.
     */
    private fun splitByScript(chunk: String): List<ScriptSegment> {
        val words = chunk.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val segments = mutableListOf<ScriptSegment>()
        val current = StringBuilder()
        var currentIsCyrillic: Boolean? = null

        fun flush() {
            if (current.isNotEmpty()) {
                segments.add(ScriptSegment(current.toString(), currentIsCyrillic ?: false))
                current.clear()
            }
        }

        for (word in words) {
            val wordHasCyrillic = containsCyrillic(word)
            val wordHasLetters = word.any { it.isLetter() }
            // Digit/punctuation-only words don't belong to a script - keep them
            // attached to whatever run is already open instead of starting a new one.
            val belongsToCurrentRun = !wordHasLetters || currentIsCyrillic == null || currentIsCyrillic == wordHasCyrillic
            if (!belongsToCurrentRun) {
                flush()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
            if (wordHasLetters) currentIsCyrillic = wordHasCyrillic
        }
        flush()
        return segments
    }

    /** Applies the requested voice/language, speech rate, and pitch to the delegate engine.
     *  Returns the resolved base locale, used as the fallback for non-Cyrillic chunks. */
    private fun applyRequestSettings(request: SynthesisRequest): Locale {
        val requestedVoice = findVoice(request.voiceName) ?: findVoice(request.language)
        val targetLocale = requestedVoice?.locale ?: activeLocale
        ensureLocale(targetLocale)

        if (request.speechRate > 0) {
            engine.setSpeechRate(request.speechRate / 100f)
        }
        if (request.pitch > 0) {
            engine.setPitch(request.pitch / 100f)
        }
        return targetLocale
    }

    /** Switches the delegate engine's active locale, but only if it isn't already applied. */
    private fun ensureLocale(locale: Locale) {
        if (appliedLocale == locale) return
        val result = engine.setLanguage(locale)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            appliedLocale = locale
            activeLocale = locale
        } else {
            Log.w(TAG, "Delegate engine rejected locale $locale (result=$result)")
        }
    }

    /**
     * Synthesizes [chunk] into [outFile] and blocks until the delegate engine
     * reports completion, an error, or the timeout elapses.
     * Returns false if the request could not even be enqueued.
     */
    private fun synthesizeChunkToFile(chunk: String, outFile: File): Boolean {
        val latch = CountDownLatch(1)
        val utteranceId = "u${System.nanoTime()}"

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { latch.countDown() }
            @Deprecated("Deprecated in Java", ReplaceWith(""))
            override fun onError(utteranceId: String?) { latch.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) { latch.countDown() }
        })

        val params = Bundle()
        val enqueueResult = engine.synthesizeToFile(chunk, params, outFile, utteranceId)
        if (enqueueResult != TextToSpeech.SUCCESS) {
            Log.w(TAG, "synthesizeToFile failed to enqueue (result=$enqueueResult)")
            return false
        }

        var waitedMs = 0L
        val stepMs = 50L
        val timeoutMs = TimeUnit.SECONDS.toMillis(SYNTH_TIMEOUT_SEC)
        while (latch.count > 0 && !stopRequested && waitedMs < timeoutMs) {
            latch.await(stepMs, TimeUnit.MILLISECONDS)
            waitedMs += stepMs
        }
        return true
    }

    /**
     * Parses the WAV header of [file] (robust to extra/odd-sized chunks such as
     * "LIST" or "fact", unlike a fixed 44-byte offset assumption), then streams
     * the raw PCM data to [callback].
     */
    private fun streamWavToCallback(file: File, callback: SynthesisCallback, isFirstChunk: Boolean): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) return false
            val riffHeader = ByteArray(12)
            raf.readFully(riffHeader)
            val isRiffWave = riffHeader[0] == 'R'.code.toByte() && riffHeader[1] == 'I'.code.toByte() &&
                riffHeader[8] == 'W'.code.toByte() && riffHeader[9] == 'A'.code.toByte()
            if (!isRiffWave) {
                Log.w(TAG, "File ${file.name} is not a valid RIFF/WAVE file")
                return false
            }

            var sampleRate = 22050
            var channels = 1
            var bitsPerSample = 16
            var dataChunkFound = false

            while (!dataChunkFound && raf.filePointer + 8 <= raf.length()) {
                val chunkIdBytes = ByteArray(4)
                raf.readFully(chunkIdBytes)
                val chunkId = String(chunkIdBytes, Charsets.US_ASCII)
                val chunkSize = readLeInt(raf)
                if (chunkSize < 0) break

                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize)
                        raf.readFully(fmt)
                        if (fmt.size >= 16) {
                            channels = leShort(fmt, 2)
                            sampleRate = leInt(fmt, 4)
                            bitsPerSample = leShort(fmt, 14)
                        }
                    }
                    "data" -> {
                        dataChunkFound = true
                    }
                    else -> {
                        raf.skipBytes(chunkSize)
                    }
                }
                // RIFF chunks are word-aligned; skip the pad byte for odd sizes.
                if (chunkId != "data" && chunkSize % 2 != 0 && raf.filePointer < raf.length()) {
                    raf.skipBytes(1)
                }
            }

            if (!dataChunkFound) {
                Log.w(TAG, "No 'data' chunk found in ${file.name}")
                return false
            }

            if (isFirstChunk) {
                val audioFormat = if (bitsPerSample == 8) {
                    AudioFormat.ENCODING_PCM_8BIT
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                callback.start(sampleRate, audioFormat, channels)
            }

            val buffer = ByteArray(callback.maxBufferSize)
            var bytesRead = 0
            while (!stopRequested && raf.read(buffer).also { bytesRead = it } > 0) {
                callback.audioAvailable(buffer, 0, bytesRead)
            }
        }
        return true
    }

    private fun readLeInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return leInt(b, 0)
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
