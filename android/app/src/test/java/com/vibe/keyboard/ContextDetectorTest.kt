package com.vibe.keyboard

import com.vibe.keyboard.engine.ContextDetector
import com.vibe.keyboard.engine.VibeSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextDetectorTest {

    private fun detect(text: String, hour: Int = 15, known: Set<String> = emptySet()) =
        ContextDetector.detect(text, hour, known)

    @Test fun `asks who Randy is instead of guessing`() {
        val signal = detect("Ulienda town na Randy?")
        assertTrue(signal is VibeSignal.NeedsContext)
        signal as VibeSignal.NeedsContext
        assertEquals("person:randy", signal.key)
        assertEquals("They mentioned Randy.", signal.headline)
    }

    @Test fun `a verb that opens a sentence is not a person`() {
        // "Ulienda" is capitalised only because it starts the sentence.
        val signal = detect("Sawa. Ulienda town?")
        assertTrue(signal !is VibeSignal.NeedsContext)
    }

    @Test fun `known places are not people`() {
        val signal = detect("Nimefika Naivas sasa")
        assertTrue(signal !is VibeSignal.NeedsContext)
    }

    @Test fun `asks about relatives and people only the user knows`() {
        assertEquals("relation:sister", (detect("How did your sister take it?") as VibeSignal.NeedsContext).key)
        assertEquals("person:brian", (detect("Did you tell Brian?") as VibeSignal.NeedsContext).key)
        assertTrue(detect("Remember what happened at Naivas? 😂") is VibeSignal.NeedsContext)
        assertTrue(detect("So did she finally reply?") is VibeSignal.NeedsContext)
    }

    @Test fun `does not ask twice once the user has explained`() {
        val signal = detect("Ulienda town na Randy?", known = setOf("person:randy"))
        assertTrue(signal !is VibeSignal.NeedsContext)
    }

    @Test fun `picture requests are flagged, never answered with a photo`() {
        assertTrue(detect("Send me a pic 😂") is VibeSignal.PictureRequest)
        assertTrue(detect("nitumie picha basi") is VibeSignal.PictureRequest)
    }

    @Test fun `a boundary outranks everything else in the message`() {
        assertTrue(detect("I'm not interested, please stop texting me Brian") is VibeSignal.Boundary)
        assertTrue(detect("niko na boyfriend") is VibeSignal.Boundary)
    }

    @Test fun `goodnight is an ending, and knows when it is late`() {
        assertEquals(VibeSignal.Ending(isNight = true), detect("Okay nalala sasa, goodnight 😴", hour = 23))
        assertEquals(VibeSignal.Ending(isNight = false), detect("gtg, talk tomorrow", hour = 14))
    }

    @Test fun `stays quiet for messages that need no help`() {
        assertEquals(VibeSignal.Quiet, detect("lol"))
        assertEquals(VibeSignal.Quiet, detect("ok"))
        assertEquals(VibeSignal.Quiet, detect("😂"))
    }

    @Test fun `offers a reply to an ordinary message`() {
        assertEquals(VibeSignal.Reply, detect("Just got home, today was so long 😩"))
        assertEquals(VibeSignal.Reply, detect("what are you doing?"))
    }
}
