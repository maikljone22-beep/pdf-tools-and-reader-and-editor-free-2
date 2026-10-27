package com.smartdocs.editor.core.voice

import com.smartdocs.editor.core.ai5.StructuredSummary
import com.smartdocs.editor.core.ai5.SummarySection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PodcastScriptTest {
    private val lines = listOf(ScriptLine(0, "مرحباً", "المقدمة"), ScriptLine(1, "أهلاً"), ScriptLine(0, "سؤال"))

    @Test fun promptsCarryFocusAndLength() {
        val p = PodcastScript.podcastPrompt(PodcastOptions("النتائج", PodcastLength.LONG))
        assertTrue(p.contains("24 إلى 32")); assertTrue(p.contains("النتائج")); assertTrue(p.contains("chapter"))
        val s = PodcastScript.summaryPrompt(PodcastOptions("", PodcastLength.SHORT))
        assertTrue(s.contains("80 إلى 120")); assertFalse(s.contains("ركّز"))
    }

    @Test fun editingOperations() {
        assertEquals(1, PodcastScript.swapSpeaker(lines, 0)[0].speaker)
        assertEquals("x", PodcastScript.editText(lines, 2, "x")[2].text)
        val ins = PodcastScript.insertAfter(lines, 0, "new")
        assertEquals(4, ins.size); assertEquals("new", ins[1].text); assertEquals(1, ins[1].speaker)
        assertEquals(0, PodcastScript.insertAfter(lines, -1)[0].speaker)
        assertEquals(listOf("أهلاً", "مرحباً", "سؤال"), PodcastScript.move(lines, 0, 1).map { it.text })
        assertEquals(lines, PodcastScript.move(lines, 0, -1))
        assertEquals(2, PodcastScript.delete(lines, 1).size)
        assertEquals(lines, PodcastScript.delete(lines, 9))
        assertEquals(2, PodcastScript.clean(lines + ScriptLine(0, "  ")).size - 1)
    }

    @Test fun changedCountOnlyCountsNewAudio() {
        val edited = PodcastScript.swapSpeaker(PodcastScript.editText(lines, 2, "سؤال جديد"), 1)
        assertEquals(2, PodcastScript.changedCount(lines, edited))
        assertEquals(0, PodcastScript.changedCount(lines, PodcastScript.move(lines, 0, 1)))
    }

    @Test fun jsonRoundTripAndChapters() {
        val back = PodcastScript.fromJson(PodcastScript.toJson(lines))
        assertEquals(lines, back)
        val timed = listOf(TimedLine(0, "a", 0, 10, "المقدمة"), TimedLine(1, "b", 10, 20), TimedLine(0, "c", 20, 30, "الخلاصة"))
        assertEquals(listOf("المقدمة" to 0L, "الخلاصة" to 20L), PodcastScript.chapters(timed))
    }

    @Test fun podcastJsonKeepsChapters() {
        val l = PodcastGenerator.podcastLines("[{\"speaker\":\"host\",\"chapter\":\"البداية\",\"text\":\"مرحباً\"},{\"speaker\":\"guest\",\"text\":\"شكراً\"}]")
        assertEquals("البداية", l[0].chapter); assertEquals(null, l[1].chapter); assertEquals(1, l[1].speaker)
    }

    @Test fun duplicateCopiesSidecars() {
        val dir = Files.createTempDirectory("pod").toFile()
        val a = File(dir, "doc_podcast.mp3").apply { writeText("audio") }
        File(dir, "doc_podcast.txt").writeText("t")
        PodcastScript.saveScript(a, lines)
        val c = PodcastScript.duplicate(a)
        assertEquals("doc_podcast_copy1.mp3", c.name)
        assertTrue(File(dir, "doc_podcast_copy1.txt").exists())
        assertEquals(lines, PodcastScript.loadScript(c, emptyList()))
        assertEquals("doc_podcast_copy2.mp3", PodcastScript.duplicate(a).name)
        dir.deleteRecursively()
    }

    @Test fun summarySpokenTextHasNoMarkup() {
        val s = StructuredSummary("نظرة عامة", listOf("**نقطة** أولى"), listOf(SummarySection("القسم", listOf(3), "نص القسم؟")), listOf("سؤال؟"))
        val t = s.spokenText()
        assertFalse(t.contains("*")); assertTrue(t.startsWith("نظرة عامة.")); assertTrue(t.contains("نص القسم؟")); assertFalse(t.contains("سؤال"))
    }
}

class VadGateTest {
    @Test fun ignoresSteadyPlaybackLeakAndDetectsSpeechOnset() {
        val g = VadGate(calibrationFrames = 5, onsetFrames = 3, marginDb = 10.0, minDb = 40.0)
        repeat(5) { assertFalse(g.feed(50.0)) }          // calibration: playback leak at 50 dB
        repeat(20) { assertFalse(g.feed(52.0)) }         // within margin: no barge-in
        assertFalse(g.feed(65.0)); assertFalse(g.feed(66.0))
        assertTrue(g.feed(64.0))                          // third loud frame in a row
    }

    @Test fun shortBurstsDoNotTrigger() {
        val g = VadGate(calibrationFrames = 2, onsetFrames = 3, marginDb = 10.0, minDb = 30.0)
        g.feed(20.0); g.feed(20.0)
        repeat(10) { assertFalse(g.feed(if (it % 2 == 0) 60.0 else 20.0)) }
    }

    @Test fun quietRoomNeedsMinimumLevel() {
        val g = VadGate(calibrationFrames = 2, onsetFrames = 2, marginDb = 6.0, minDb = 45.0)
        g.feed(0.0); g.feed(0.0)
        repeat(5) { assertFalse(g.feed(30.0)) }
        assertFalse(g.feed(50.0)); assertTrue(g.feed(50.0))
    }

    @Test fun levelDbOfPcm() {
        assertEquals(0.0, VadGate.levelDb(ShortArray(100)), 1e-9)
        assertEquals(60.0, VadGate.levelDb(ShortArray(100) { 1000 }), 1e-6)
    }
}
