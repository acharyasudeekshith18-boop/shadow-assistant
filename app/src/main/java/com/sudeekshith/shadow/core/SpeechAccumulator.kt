package com.sudeekshith.shadow.core

/**
 * Pure (Android-free) bookkeeping for one spoken request that may span several recognizer segments.
 *
 * Android's SpeechRecognizer stops at short pauses. Shadow restarts it and appends the next segment,
 * and when the recognizer gives up with "no match" / "speech timeout" after it already showed partial
 * text, that partial text is kept instead of being thrown away.
 */
class SpeechAccumulator {
    private val segments = mutableListOf<String>()
    var partial: String = ""
        private set

    /** Live text from the recognizer for the current segment. Blank partials never erase what we showed. */
    fun onPartial(text: String) {
        if (text.isNotBlank()) partial = text.trim()
    }

    /** The recognizer's final text for this segment. A blank final falls back to the last partial. */
    fun onFinal(text: String) {
        val t = text.trim().ifBlank { partial }
        if (t.isNotBlank()) add(t)
        partial = ""
    }

    /** Recognizer ended without a final result (silence, no match, error): keep any partial text. Returns true if something was kept. */
    fun commitPartial(): Boolean {
        val had = partial.isNotBlank()
        if (had) add(partial)
        partial = ""
        return had
    }

    private fun add(t: String) {
        // Some recognizers repeat the previous segment at the start of the next one.
        val last = segments.lastOrNull()
        when {
            last != null && t.equals(last, ignoreCase = true) -> return
            last != null && t.startsWith(last, ignoreCase = true) -> segments[segments.lastIndex] = t
            else -> segments += t
        }
    }

    /** Everything heard so far, plus the live partial (for the screen). */
    fun live(): String = (segments + listOfNotNull(partial.ifBlank { null })).joinToString(" ")

    /** Committed text only. */
    fun text(): String = segments.joinToString(" ")

    fun hasCommitted(): Boolean = segments.isNotEmpty()
    fun hasAnything(): Boolean = segments.isNotEmpty() || partial.isNotBlank()

    fun reset() { segments.clear(); partial = "" }
}

/** What the listener should do when a recognizer segment ends. Pure, so it is unit-tested. */
object ListenPolicy {
    enum class Next { RESTART, FINISH, NOTHING_HEARD }

    /**
     * @param holding the user is pressing the orb (push-to-talk): keep listening until release.
     * @param releasing the user released / tapped to stop: deliver whatever we have.
     * @param hasText something was heard in this request (committed or partial already committed).
     * @param inContinuation this segment was the short "are you still talking?" window after a result.
     * @param gotNewSpeech the segment that just ended contained new speech.
     */
    fun afterSegment(holding: Boolean, releasing: Boolean, hasText: Boolean, inContinuation: Boolean, gotNewSpeech: Boolean): Next = when {
        releasing -> if (hasText) Next.FINISH else Next.NOTHING_HEARD
        holding -> Next.RESTART
        !hasText -> Next.NOTHING_HEARD
        gotNewSpeech -> Next.RESTART          // he spoke again: give him another window to continue
        inContinuation -> Next.FINISH         // window passed with no new words: he's done
        else -> Next.RESTART
    }

    /** How many silent listens in a row end continuous conversation mode. */
    const val MAX_SILENT_LISTENS = 3

    /** Pause settings (silence the recognizer waits for, and the "keep talking" window after a result), by patience level 0..2. */
    fun silenceMs(patience: Int): Long = when (patience) { 0 -> 2000L; 2 -> 4000L; else -> 3000L }
    fun possiblyCompleteMs(patience: Int): Long = silenceMs(patience) - 1000L
    fun continuationWindowMs(patience: Int): Long = when (patience) { 0 -> 1000L; 2 -> 2500L; else -> 1600L }
}
