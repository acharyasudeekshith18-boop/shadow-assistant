package com.sudeekshith.shadow

import com.sudeekshith.shadow.core.ListenPolicy
import com.sudeekshith.shadow.core.ListenPolicy.Next
import com.sudeekshith.shadow.core.SpeechAccumulator
import com.sudeekshith.shadow.system.ShadowVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechAccumulatorTest {
    @Test fun partialIsKeptWhenRecognizerGivesUp() {
        // He said "what is the capital of" and the recognizer ended with NO_MATCH / SPEECH_TIMEOUT.
        val a = SpeechAccumulator()
        a.onPartial("what is")
        a.onPartial("what is the capital of")
        assertTrue(a.commitPartial())
        assertEquals("what is the capital of", a.text())
    }

    @Test fun blankFinalFallsBackToPartial() {
        val a = SpeechAccumulator()
        a.onPartial("explain recursion")
        a.onFinal("")
        assertEquals("explain recursion", a.text())
    }

    @Test fun segmentsAreAppendedAcrossPauses() {
        val a = SpeechAccumulator()
        a.onPartial("tell me about")
        a.onFinal("tell me about")
        a.onPartial("the french")
        assertEquals("tell me about the french", a.live())
        a.onFinal("the french revolution")
        assertEquals("tell me about the french revolution", a.text())
    }

    @Test fun repeatedSegmentIsNotDuplicated() {
        val a = SpeechAccumulator()
        a.onFinal("set an alarm")
        a.onFinal("set an alarm for 6 am")   // some recognizers resend the earlier words
        assertEquals("set an alarm for 6 am", a.text())
        a.onFinal("set an alarm for 6 am")
        assertEquals("set an alarm for 6 am", a.text())
    }

    @Test fun blankPartialDoesNotEraseLiveText() {
        val a = SpeechAccumulator()
        a.onPartial("hello shadow")
        a.onPartial("")
        assertEquals("hello shadow", a.live())
    }

    @Test fun nothingHeard() {
        val a = SpeechAccumulator()
        assertFalse(a.commitPartial())
        assertFalse(a.hasAnything())
        assertEquals("", a.text())
    }

    @Test fun policy() {
        // first result -> listen again briefly in case he keeps talking
        assertEquals(Next.RESTART, ListenPolicy.afterSegment(holding = false, releasing = false, hasText = true, inContinuation = false, gotNewSpeech = true))
        // he kept talking inside the window -> another window
        assertEquals(Next.RESTART, ListenPolicy.afterSegment(false, false, true, inContinuation = true, gotNewSpeech = true))
        // window passed in silence -> send
        assertEquals(Next.FINISH, ListenPolicy.afterSegment(false, false, true, inContinuation = true, gotNewSpeech = false))
        // nothing at all
        assertEquals(Next.NOTHING_HEARD, ListenPolicy.afterSegment(false, false, false, false, false))
        // hold-to-talk never ends on silence
        assertEquals(Next.RESTART, ListenPolicy.afterSegment(holding = true, releasing = false, hasText = false, inContinuation = false, gotNewSpeech = false))
        // release / tap-to-stop sends what we have
        assertEquals(Next.FINISH, ListenPolicy.afterSegment(holding = false, releasing = true, hasText = true, inContinuation = false, gotNewSpeech = false))
        assertTrue(ListenPolicy.silenceMs(1) in 2500L..3000L)
        assertEquals(2000L, ListenPolicy.possiblyCompleteMs(1))
        assertTrue(ListenPolicy.MAX_SILENT_LISTENS > 1)
    }

    @Test fun onlyLastTtsChunkOfCurrentAnswerCountsAsDone() {
        // v1.0 bug: the end of the FIRST sentence re-opened the mic mid-answer.
        val gen = 7
        val ids = (0 until 3).map { ShadowVoice.utteranceId(gen, it, 3) }
        assertFalse(ShadowVoice.isFinalUtterance(ids[0], gen))
        assertFalse(ShadowVoice.isFinalUtterance(ids[1], gen))
        assertTrue(ShadowVoice.isFinalUtterance(ids[2], gen))
        assertFalse("an older answer's last chunk must not end the new one", ShadowVoice.isFinalUtterance(ShadowVoice.utteranceId(6, 0, 1), gen))
    }
}
