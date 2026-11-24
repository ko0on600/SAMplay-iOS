package com.nuvio.app.features.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SAMplay iOS: the AI subtitle helpers ported from Android. */
class SamplayAiIosTest {
    @Test
    fun srtRoundTrip() {
        val cues = listOf(AiCue(1_000, 2_500, "Hello"), AiCue(61_005, 3_723_456, "Two\nlines"))
        val srt = AiSrt.toSrt(cues)
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:02,500\nHello\n\n2\n00:01:01,005 --> 01:02:03,456\n"))
        assertEquals(cues, AiSrt.parse(srt))
    }

    @Test
    fun srtWritesTranslationsAndBoth() {
        val cues = listOf(AiCue(0, 1_000, "Yes", translated = "نعم"))
        assertTrue(AiSrt.toSrt(cues, translated = true).contains("\nنعم\n"))
        assertTrue(AiSrt.toSrt(cues, translated = true, both = true).contains("\nYes\nنعم\n"))
    }

    @Test
    fun vttIsRead() {
        val cues = AiSrt.parse("WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n")
        assertEquals(listOf(AiCue(1_000, 2_000, "Hi")), cues)
    }

    @Test
    fun googleReplyIsParsed() {
        val (text, lang) = GoogleTranslate.parse("""[[["مرحبا ","Hello ",null,null,10],["بالعالم","world",null,null,10]],null,"en"]""")
        assertEquals("مرحبا بالعالم", text)
        assertEquals("en", lang)
    }

    @Test
    fun batchesRespectLimits() {
        val lines = List(10) { "x".repeat(40) }
        val batches = GoogleTranslate.batches(lines, maxChars = 100, maxCues = 50)
        assertEquals((0 until 10).toList(), batches.flatMap { it.toList() })
        assertTrue(batches.all { it.count() <= 2 })
        assertEquals(listOf(0 until 3, 3 until 5), GoogleTranslate.batches(List(5) { "a" }, maxChars = 10_000, maxCues = 3))
    }

    @Test
    fun markupIsCleanedAndDialogueRestored() {
        assertEquals("Hi there", GoogleTranslate.clean("{\\an8}<i>Hi</i>\\Nthere"))
        assertEquals("- نعم.\n- إنها تعرف.", GoogleTranslate.restoreDialogue("- Yeah.\n- She knows.", "- نعم. - إنها تعرف."))
        assertEquals("نعم", GoogleTranslate.restoreDialogue("Yeah.", "نعم"))
    }

    @Test
    fun languageCodes() {
        assertEquals("he", MlTranslate.mlCode("iw"))
        assertEquals("zh", MlTranslate.mlCode("zh-Hant"))
        assertEquals("ar", MlTranslate.mlCode("ar-SA"))
        assertNull(MlTranslate.mlCode("xx"))
        assertTrue(MlTranslate.languages().containsAll(listOf("ar", "en", "fr")))
    }

    @Test
    fun providersHaveDefaults() {
        ProProvider.entries.forEach {
            assertTrue(it.defaultModel.isNotBlank(), it.id)
            assertEquals(it, ProProvider.byId(it.id))
        }
    }
}
