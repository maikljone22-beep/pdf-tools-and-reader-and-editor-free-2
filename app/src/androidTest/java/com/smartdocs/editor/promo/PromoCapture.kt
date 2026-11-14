package com.smartdocs.editor.promo

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.smartdocs.editor.MainActivity
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.office5.Templates5
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Records the real app for the promo video: each test opens one part of the app with a proper sample document, uses
 * it the way a person would, and saves a screen recording and screenshots to /sdcard/Movies/promo (pulled by the
 * "promo" workflow). Nothing here is a check of the app; a scene that cannot be recorded is skipped, never failed.
 * Run only by the promo workflow (instrumentation argument `package=com.smartdocs.editor.promo`).
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)   // the home screen is recorded last, when its recent list is full
class PromoCapture {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val app get() = ctx.applicationContext as SmartDocsApp
    private val device get() = UiDevice.getInstance(instr)
    private val work = File(ctx.cacheDir, "promo")
    private val published = ArrayList<Uri>()
    private val w get() = device.displayWidth
    private val h get() = device.displayHeight

    private fun shell(cmd: String): String = instr.uiAutomation.executeShellCommand(cmd).use { pfd ->
        ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    @Before fun setUp() {
        work.deleteRecursively(); work.mkdirs()
        shell("mkdir -p $OUT")
        // straight to the app: no consent screen, no first-use tips over the screens, no reopening of the last document
        com.smartdocs.editor.core.review.AcctPrefs.completeOnboarding(ctx, analyticsOptIn = false)
        for (k in listOf("home", "reader")) com.smartdocs.editor.core.review.AcctPrefs.setCoachSeen(ctx, k)
        runBlocking { app.settings.update { it.copy(onboardingDone = true, restoreSession = false) } }
        runCatching { instr.uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS") }
        // a clean status bar: fixed clock, full battery and signal, no notification icons
        shell("settings put global sysui_demo_allowed 1")
        for (c in listOf("enter", "clock -e hhmm 0941", "battery -e level 100 -e plugged false", "network -e wifi show -e level 4", "network -e mobile show -e datatype none -e level 4", "notifications -e visible false"))
            shell("am broadcast -a com.android.systemui.demo -e command $c")
    }

    @After fun tearDown() {
        published.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        work.deleteRecursively()
    }

    // ---------------------------------------------------------------------------------------------- scenes

    @Test fun a_pdf_reader() {
        val f = File(work, "التقرير السنوي ٢٠٢٦.pdf").also { PromoPdf.write(it) }
        open(f, "application/pdf") {
            shot("pdf_cover")
            record("pdf_scroll", 14) {
                pause(1200)
                repeat(3) { swipeUp(0.55f, 55); pause(900) }
                pinch(open = true); pause(1100); pinch(open = false); pause(700)
                swipeUp(0.5f, 45)
            }
            shot("pdf_page")
            tapText("التعليق"); pause(1500); shot("pdf_comment_tab")
            tapText("العرض"); pause(900)
            if (tapText("الوضع الليلي")) { pause(1500); shot("pdf_night") }
        }
    }

    @Test fun b_word_cv() = template(Templates5.Kind.CV, "السيرة الذاتية.docx", DOCX, "word") {
        pause(1000); repeat(3) { swipeUp(0.5f, 60); pause(1000) }; swipeDown(0.6f, 40); pause(800)
    }

    @Test fun c_excel_invoice() = template(Templates5.Kind.INVOICE, "فاتورة.xlsx", XLSX, "excel") {
        pause(1200); device.click(w / 2, h / 2); pause(1200); device.click(w * 3 / 4, (h * 0.58f).toInt()); pause(1200)
        device.swipe(w * 3 / 4, h / 2, w / 4, h / 2, 50); pause(1000); device.swipe(w / 4, h / 2, w * 3 / 4, h / 2, 50); pause(800)
    }

    // the slide strip is at the bottom of the phone layout, first slide on the right: tapping a thumbnail shows that slide
    @Test fun d_slides() = template(Templates5.Kind.SLIDES, "عرض تقديمي.pptx", PPTX, "slides") {
        val y = (h * 0.865f).toInt()
        pause(1300)
        for (x in listOf(0.62f, 0.36f, 0.12f, 0.62f, 0.88f)) { device.click((w * x).toInt(), y); pause(1400) }
    }

    /** The other ready-made documents, as stills for the templates part of the video. */
    @Test fun g_templates() {
        for ((kind, name, tag) in listOf(
            Triple(Templates5.Kind.LETTER, "خطاب رسمي.docx", "tpl_letter"), Triple(Templates5.Kind.EXAM, "ورقة اختبار.docx", "tpl_exam"),
            Triple(Templates5.Kind.MINUTES, "محضر اجتماع.docx", "tpl_minutes"), Triple(Templates5.Kind.BUDGET, "ميزانية شهرية.xlsx", "tpl_budget"),
        )) {
            val f = File(work, name).also { Templates5.write(kind, true, it) }
            runCatching { open(f, if (kind.ext == "xlsx") XLSX else DOCX) { shot(tag) } }
        }
    }

    @Test fun e_tools() {
        ActivityScenario.launch(MainActivity::class.java).use {
            pause(3500)
            if (!tapText(ctx.getString(R.string.nav_tools))) return
            pause(2000); shot("tools")
            record("tools_scroll", 12) {
                pause(900); repeat(4) { swipeUp(0.6f, 45); pause(700) }; repeat(2) { swipeDown(0.6f, 30); pause(500) }
            }
            // the search field: typing the Arabic word for "merge"
            repeat(4) { swipeDown(0.6f, 15) }
            pause(600)
            val field = device.findObject(By.clazz("android.widget.EditText"))
            if (field != null) {
                record("tools_search", 7) { field.click(); pause(700); field.text = "دمج"; pause(2500) }
                shot("tools_search"); device.pressBack()
            }
        }
    }

    @Test fun f_office_start() {
        ActivityScenario.launch(MainActivity::class.java).use {
            pause(3500)
            if (!tapText(ctx.getString(R.string.nav_tools))) return
            pause(1500)
            val title = ctx.getString(R.string.o5_tool_open)
            var found = false
            for (i in 0 until 14) { if (tapText(title)) { found = true; break }; swipeUp(0.45f, 25); pause(500) }
            if (!found) return
            pause(2500); shot("office_start")
            record("office_templates", 11) { pause(1000); repeat(3) { swipeUp(0.55f, 55); pause(1100) }; swipeDown(0.5f, 40); pause(600) }
            shot("office_templates")
        }
    }

    @Test fun z_home() {
        ActivityScenario.launch(MainActivity::class.java).use {
            pause(4000); shot("home")
            record("home", 8) { pause(1200); swipeUp(0.4f, 50); pause(1500); swipeDown(0.4f, 50); pause(1000) }
            if (tapText(ctx.getString(R.string.nav_files))) { pause(2000); shot("files") }
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** Builds a ready-made Arabic document, opens it in its editor and records [act]. */
    private fun template(kind: Templates5.Kind, name: String, mime: String, tag: String, act: () -> Unit) {
        val f = File(work, name).also { Templates5.write(kind, true, it) }
        open(f, mime) { shot(tag); record("${tag}_use", 11, act); shot("${tag}_after") }
    }

    /** Hands [file] to the app the way a file manager does (through Downloads) and runs [scene] once it is on screen. */
    private fun open(file: File, mime: String, scene: () -> Unit) {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/promo"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "cannot add ${file.name} to Downloads" }
        published += uri
        checkNotNull(cr.openOutputStream(uri)).use { o -> file.inputStream().use { it.copyTo(o) } }
        cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch<MainActivity>(intent).use {
            device.wait(Until.hasObject(By.pkg(ctx.packageName).scrollable(true)), 20_000)
            pause(5000)   // first pages, pictures and fonts settle
            runCatching { scene() }
        }
    }

    /** Records the screen for [seconds] while [act] runs; the recording ends by itself, so the file is always complete. */
    private fun record(name: String, seconds: Int, act: () -> Unit) {
        val started = System.currentTimeMillis()
        val pfd = runCatching { instr.uiAutomation.executeShellCommand("screenrecord --bit-rate 16000000 --time-limit $seconds $OUT/$name.mp4") }.getOrNull()
        pause(900)
        runCatching { act() }
        val left = seconds * 1000L + 1800 - (System.currentTimeMillis() - started)
        if (left > 0) pause(left)
        runCatching { pfd?.close() }
    }

    private fun shot(name: String) { runCatching { shell("screencap -p $OUT/$name.png") } }
    private fun pause(ms: Long) = Thread.sleep(ms)

    private fun tapText(text: String): Boolean = runCatching {
        val o = device.findObject(By.pkg(ctx.packageName).text(text)) ?: return false
        o.click(); true
    }.getOrDefault(false)

    private fun swipeUp(part: Float, steps: Int) { device.swipe(w / 2, (h * (0.5f + part / 2)).toInt(), w / 2, (h * (0.5f - part / 2)).toInt(), steps) }
    private fun swipeDown(part: Float, steps: Int) { device.swipe(w / 2, (h * (0.5f - part / 2)).toInt(), w / 2, (h * (0.5f + part / 2)).toInt(), steps) }
    private fun pinch(open: Boolean) {
        runCatching { device.findObject(By.pkg(ctx.packageName).scrollable(true))?.let { if (open) it.pinchOpen(0.55f, 500) else it.pinchClose(0.55f, 500) } }
    }

    private companion object {
        const val OUT = "/sdcard/Movies/promo"
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    }
}
