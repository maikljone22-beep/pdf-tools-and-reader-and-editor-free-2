package com.smartdocs.editor.torture

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
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
 * The file reaches the app the way it does for a user: it sits in the device's Downloads and the app gets a
 * "view this" request for it, so the app imports it (a full copy into its own storage) and then opens it.
 * (A file inside the app's own private folders is refused at the door by IncomingGuard.)
 *
 * Each test logs one line under the tag TortureReport: the file, the time from the request to the document being
 * open, the Java and native heap afterwards and what each whole-file PDF parse cost ([ParseGate.stats]). When a file
 * did not open, the texts on the screen are logged too. CI uploads that log and a screenshot per test.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)   // MediaStore.Downloads
class TortureTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val app get() = ctx.applicationContext as SmartDocsApp
    private val device get() = UiDevice.getInstance(instr)
    private val dir = File(ctx.cacheDir, "torture")
    private val published = ArrayList<Uri>()

    /** The size (MB) above which the Office editors refuse a package: the same rule as OPackage.open. */
    private val officeLimitMb: Int get() = ((Runtime.getRuntime().maxMemory() / 8).coerceAtLeast(16L shl 20) shr 20).toInt()

    @Before fun setUp() {
        dir.deleteRecursively(); dir.mkdirs()
        // no welcome / consent screen in front of the document (MainActivity shows it until AcctPrefs says it is done,
        // and opens nothing meanwhile), no tour, no permission prompt over it
        com.smartdocs.editor.core.review.AcctPrefs.completeOnboarding(ctx, analyticsOptIn = false)
        runBlocking { app.settings.update { it.copy(onboardingDone = true) }; app.db.docs().clear() }
        runCatching { instr.uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS") }
        deleteImports()
    }

    @After fun tearDown() {
        published.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        deleteImports()
        dir.deleteRecursively()
    }

    /** The app's working copies of the test files (it imports every document it is handed). */
    private fun deleteImports() {
        runCatching { app.documents.docsDir.listFiles()?.filter { it.name.contains(PREFIX) }?.forEach { it.delete() } }
    }

    // ---- PDF ----

    @Test fun pdf_1000_pages() {
        val f = File(dir, "${PREFIX}book.pdf").also { TorturePdf.textPages(it, 1000) }
        torture("pdf_1000_pages", f, "application/pdf", Expect.OPENS, openSeconds = 90)
    }

    @Test fun pdf_scan_500mb() {
        val f = File(dir, "${PREFIX}scan.pdf")
        val pages = TortureFiles.scanPdf(f, 500)
        torture("pdf_scan_500mb ($pages pages)", f, "application/pdf", Expect.OPENS, openSeconds = 300)
    }

    // ---- image ----

    @Test fun jpeg_50_megapixel() {
        val f = File(dir, "${PREFIX}photo.jpg").also { TortureFiles.jpeg50mp(it) }
        torture("jpeg_50_megapixel", f, "image/jpeg", Expect.EITHER, openSeconds = 60)
    }

    // ---- Office ----

    /** A Word file full of photos, inside the editor's size limit: it must open. */
    @Test fun docx_photos_within_limit() {
        val f = File(dir, "${PREFIX}photos.docx")
        val n = TortureFiles.docxWithPhotos(f, officeLimitMb * 6 / 10)
        torture("docx_photos_within_limit ($n photos)", f, DOCX, Expect.OPENS, openSeconds = 150)
    }

    /** The same kind of file at twice the limit: the app must refuse it with a message, not die trying. */
    @Test fun docx_photos_over_limit_is_refused() {
        val f = File(dir, "${PREFIX}huge.docx")
        val n = TortureFiles.docxWithPhotos(f, officeLimitMb * 2)
        torture("docx_photos_over_limit ($n photos)", f, DOCX, Expect.REFUSED, openSeconds = 60)
    }

    @Test fun xlsx_100k_rows() {
        val f = File(dir, "${PREFIX}ledger.xlsx").also { TortureFiles.xlsxRows(it, 100_000) }
        torture("xlsx_100k_rows", f, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Expect.EITHER, openSeconds = 150)
    }

    @Test fun csv_25mb() {
        val f = File(dir, "${PREFIX}export.csv").also { TortureFiles.csv(it, 25) }
        torture("csv_25mb", f, "text/csv", Expect.EITHER, openSeconds = 150)
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

    /** Moves [file] into the device's Downloads and returns its content uri (what a file manager would hand the app). */
    private fun publish(file: File, mime: String): Uri {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/torture")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "cannot add ${file.name} to Downloads" }
        published += uri
        checkNotNull(cr.openOutputStream(uri)) { "cannot write $uri" }.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 20) } }
        cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        file.delete()
        return uri
    }

    private fun torture(name: String, file: File, mime: String, expect: Expect, openSeconds: Int) {
        val fileName = file.name; val fileMb = file.length() shr 20
        val uri = publish(file, mime)
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val t0 = System.nanoTime()
        ActivityScenario.launch<MainActivity>(intent).use {
            // wait until the app itself says the document is open: the reader and the editors add it to the recent list then
            var opened = false
            val deadline = System.currentTimeMillis() + openSeconds * 1000L
            while (!opened && System.currentTimeMillis() < deadline) { Thread.sleep(500); opened = isOpen(fileName) }
            val openMs = (System.nanoTime() - t0) / 1_000_000
            Thread.sleep(2_000)
            val screen = if (opened) "" else " | on screen: ${screenTexts()}"
            screenshot(name)
            userGestures()
            Thread.sleep(3_000)   // background reads after opening (annotations, signatures…) get their turn

            val rt = Runtime.getRuntime()
            System.gc(); Thread.sleep(500)
            val usedMb = (rt.totalMemory() - rt.freeMemory()) shr 20
            val maxMb = rt.maxMemory() shr 20
            val parses = ParseGate.stats().joinToString("; ").ifEmpty { "-" }
            Log.i("TortureReport", "$name | file $fileMb MB | opened=$opened after $openMs ms | java heap $usedMb of $maxMb MB | native heap ${Debug.getNativeHeapAllocatedSize() shr 20} MB | parses: $parses$screen")

            assertTrue("$name: the Java heap is nearly full after opening ($usedMb of $maxMb MB)", usedMb < maxMb * 0.85)
            when (expect) {
                Expect.OPENS -> assertTrue("$name: did not open within $openSeconds s", opened)
                Expect.REFUSED -> assertFalse("$name: should have been refused as too large", opened)
                Expect.EITHER -> {}
            }
        }
    }

    private fun isOpen(fileName: String): Boolean = runBlocking { app.db.docs().recents().first().any { it.name == fileName } }

    /** What the screen says, for the report of a file that did not open (an error message, a dialog…). */
    private fun screenTexts(): String = runCatching {
        device.findObjects(By.pkg(ctx.packageName)).mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }.distinct().take(14).joinToString(" / ").take(600)
    }.getOrDefault("?").ifEmpty { "(no text)" }

    /** A screenshot into the folder the build collects after the run (when it provides one). */
    private fun screenshot(name: String) {
        val out = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        runCatching { File(out).mkdirs(); device.takeScreenshot(File(out, name.substringBefore(' ').replace(Regex("[^A-Za-z0-9_]"), "_") + ".png"), 0.5f, 80) }
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

    private companion object {
        /** In every test file name, so the app's working copies can be found and removed. */
        const val PREFIX = "torture_"
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
