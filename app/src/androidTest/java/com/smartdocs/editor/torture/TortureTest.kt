package com.smartdocs.editor.torture

import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.smartdocs.editor.MainActivity
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.pdf.ParseGate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Torture" tests: the app is asked to open files far heavier than normal, is scrolled and zoomed the way a person
 * would, and must survive. The app runs inside the test process, so a crash on any thread (an OutOfMemoryError on a
 * background thread included) fails the test. The test orchestrator gives every test its own process, so one crash
 * does not hide the others.
 *
 * Each test logs one line under the tag TortureReport: the file, the time to open, the Java and native heap afterwards
 * and what each whole-file PDF parse cost ([ParseGate.stats]). CI uploads that log.
 */
@RunWith(AndroidJUnit4::class)
class TortureTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val app get() = ctx.applicationContext as SmartDocsApp
    private val device get() = UiDevice.getInstance(instr)
    private val dir = File(ctx.cacheDir, "torture")

    /** The size (MB) above which the Office editors refuse a package: the same rule as OPackage.open. */
    private val officeLimitMb: Int get() = ((Runtime.getRuntime().maxMemory() / 8).coerceAtLeast(16L shl 20) shr 20).toInt()

    @Before fun setUp() {
        dir.deleteRecursively(); dir.mkdirs()
        // no welcome / consent screen in front of the document (MainActivity shows it until AcctPrefs says it is done,
        // and opens nothing meanwhile), no tour, no permission prompt over it
        com.smartdocs.editor.core.review.AcctPrefs.completeOnboarding(ctx, analyticsOptIn = false)
        runBlocking { app.settings.update { it.copy(onboardingDone = true) } }
        runCatching { instr.uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS") }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    // ---- PDF ----

    @Test fun pdf_1000_pages() {
        val f = File(dir, "book.pdf").also { TorturePdf.textPages(it, 1000) }
        torture("pdf_1000_pages", f, Expect.OPENS, openSeconds = 90)
    }

    @Test fun pdf_scan_500mb() {
        val f = File(dir, "scan.pdf")
        val pages = TortureFiles.scanPdf(f, 500)
        torture("pdf_scan_500mb ($pages pages)", f, Expect.OPENS, openSeconds = 180)
    }

    // ---- image ----

    @Test fun jpeg_50_megapixel() {
        val f = File(dir, "photo.jpg").also { TortureFiles.jpeg50mp(it) }
        torture("jpeg_50_megapixel", f, Expect.EITHER, openSeconds = 60)
    }

    // ---- Office ----

    /** A Word file full of photos, inside the editor's size limit: it must open. */
    @Test fun docx_photos_within_limit() {
        val f = File(dir, "photos.docx")
        val n = TortureFiles.docxWithPhotos(f, officeLimitMb * 6 / 10)
        torture("docx_photos_within_limit ($n photos)", f, Expect.OPENS, openSeconds = 120)
    }

    /** The same kind of file at twice the limit: the app must refuse it with a message, not die trying. */
    @Test fun docx_photos_over_limit_is_refused() {
        val f = File(dir, "huge.docx")
        val n = TortureFiles.docxWithPhotos(f, officeLimitMb * 2)
        torture("docx_photos_over_limit ($n photos)", f, Expect.REFUSED, openSeconds = 45)
    }

    @Test fun xlsx_100k_rows() {
        val f = File(dir, "ledger.xlsx").also { TortureFiles.xlsxRows(it, 100_000) }
        torture("xlsx_100k_rows", f, Expect.EITHER, openSeconds = 120)
    }

    @Test fun csv_25mb() {
        val f = File(dir, "export.csv").also { TortureFiles.csv(it, 25) }
        torture("csv_25mb", f, Expect.EITHER, openSeconds = 120)
    }

    // ------------------------------------------------------------------------------------------------------

    private enum class Expect {
        /** The reader or editor must really open the file. */
        OPENS,
        /** Opening it, or a clear "too large" message, are both fine; only a crash or a full heap fails. */
        EITHER,
        /** The file is over a limit on purpose: it must not open, and the app must survive the attempt. */
        REFUSED,
    }

    private fun torture(name: String, file: File, expect: Expect, openSeconds: Int) {
        val before = runBlocking { app.settings.settings.first().lastOpenedUri }
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val t0 = System.nanoTime()
        ActivityScenario.launch<MainActivity>(intent).use {
            // wait until the app itself says the document is open
            var opened = false
            val deadline = System.currentTimeMillis() + openSeconds * 1000L
            while (!opened && System.currentTimeMillis() < deadline) { Thread.sleep(500); opened = isOpen(file, before) }
            val openMs = (System.nanoTime() - t0) / 1_000_000
            Thread.sleep(2_000)
            userGestures()
            Thread.sleep(3_000)   // background reads after opening (annotations, signatures…) get their turn

            val rt = Runtime.getRuntime()
            System.gc(); Thread.sleep(500)
            val usedMb = (rt.totalMemory() - rt.freeMemory()) shr 20
            val maxMb = rt.maxMemory() shr 20
            val parses = ParseGate.stats().joinToString("; ").ifEmpty { "-" }
            Log.i("TortureReport", "$name | file ${file.length() shr 20} MB | opened=$opened after $openMs ms | java heap $usedMb of $maxMb MB | native heap ${Debug.getNativeHeapAllocatedSize() shr 20} MB | parses: $parses")

            assertTrue("$name: the Java heap is nearly full after opening ($usedMb of $maxMb MB)", usedMb < maxMb * 0.85)
            when (expect) {
                Expect.OPENS -> assertTrue("$name: did not open within $openSeconds s", opened)
                Expect.REFUSED -> assertFalse("$name: should have been refused as too large", opened)
                Expect.EITHER -> {}
            }
        }
    }

    /** PDFs: the reader records the last opened document. Office files: the editor adds the file to the recent list once it is loaded. */
    private fun isOpen(file: File, lastOpenedBefore: String): Boolean = runBlocking {
        when (file.extension.lowercase()) {
            "pdf" -> app.settings.settings.first().lastOpenedUri.let { it.isNotEmpty() && it != lastOpenedBefore }
            "docx", "xlsx", "pptx", "csv" -> app.db.docs().get(Uri.fromFile(file).toString()) != null
            else -> false
        }
    }

    /** Scroll down, flick back, pinch in and out: what a person does with a big document in the first minute. */
    private fun userGestures() {
        val w = device.displayWidth; val h = device.displayHeight
        runCatching {
            repeat(10) { device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 10); Thread.sleep(250) }
            repeat(4) { device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 8); Thread.sleep(250) }
            device.findObject(By.pkg(ctx.packageName).scrollable(true))?.pinchOpen(0.6f, 600)
            Thread.sleep(800)
            device.findObject(By.pkg(ctx.packageName).scrollable(true))?.pinchClose(0.6f, 600)
        }
    }
}
