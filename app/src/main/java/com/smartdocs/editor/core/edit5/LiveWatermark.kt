package com.smartdocs.editor.core.edit5

import android.content.Context
import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.PageFrame
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * How the static appearance (what viewers without JavaScript show) is drawn and which font the viewer uses when the
 * script changes the value. The app uses shaped Arabic through [E5Line]; tests use a raw font dictionary.
 */
interface LiveFace {
    val size: Float
    /** Font dictionary registered in the AcroForm /DR under [LiveWmScript.DA_FONT] for the fields' /DA. */
    fun daFont(): COSBase
    fun width(line: String): Float
    /** Draw [line] with its baseline at ([x], [y]) into an appearance stream whose resources are [res]. */
    fun draw(cs: PDPageContentStream, res: PDResources, line: String, x: Float, y: Float)
}

/**
 * Live watermark: one read-only text field per page (`SDE5WM.p<n>`, merged field/widget) whose value is set by a
 * document script on open (/Names /JavaScript plus the /OpenAction chain) and before printing (catalog /AA /WP).
 * Each widget keeps a static appearance with the values of the last apply/refresh, so viewers without JavaScript
 * (PDFium, most mobile apps) show the watermark too. Everything we add carries `/SDE5 (wmlive)` so [remove] finds it.
 *
 * Limits of form fields: rotation in 90° steps ([LiveWmScript.snapRotation]), always over the page content, opacity
 * simulated by a lighter colour ([LiveWmScript.fadedColor]), one placement per page (no tiling).
 */
object LiveWatermark {
    private val MARK_KEY = COSName.getPDFName(Marked.KEY)
    private val WRAP_KEY = COSName.getPDFName("SDE5W")
    private val ACROFORM = COSName.getPDFName("AcroForm")
    private val FIELDS = COSName.getPDFName("Fields")
    private val KIDS = COSName.KIDS
    private val NAMES = COSName.getPDFName("Names")
    private val JAVASCRIPT = COSName.getPDFName("JavaScript")
    private val OPEN_ACTION = COSName.getPDFName("OpenAction")
    private val AA = COSName.getPDFName("AA")
    private val WP = COSName.getPDFName("WP")
    private val NEXT = COSName.getPDFName("Next")

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
    private fun ours(b: COSBase?): Boolean = (deref(b) as? COSDictionary)?.getString(MARK_KEY) == LiveWmScript.MARK

    /**
     * Hook for the reader: refresh the static appearance of a live watermark in [file] (current date/time, page
     * numbers, [userName] or the stored user) and save it in place, so viewers without JavaScript (our PDFium reader)
     * show current values. Returns false (file untouched) when the file has no live watermark or cannot be opened.
     */
    suspend fun refresh(context: Context, file: File, password: String?, userName: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (!mayContain(file)) return@withContext false
        runCatching {
            E5Io.load(file, password).use { doc ->
                if (!present(doc)) return@use false
                val spec = DecorSpec.fromJson(Marked.getSetting(doc, WatermarkOps.settingKey(DecorType.WATERMARK)))?.takeIf { it.isLive } ?: return@use false
                val sp = if (userName.isNullOrBlank()) spec else spec.copy(user = userName)
                install(doc, sp, file.name, WatermarkOps.liveFace(context, doc, sp))
                E5Io.save(doc, file)
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Cheap pre-check without parsing: our dictionaries carry the key `/SDE5` in plain bytes (names are never
     * encrypted, PdfBox 2.0 writes no object streams). False = certainly no live watermark written by us.
     */
    fun mayContain(file: File): Boolean = runCatching { file.inputStream().buffered(1 shl 16).use { scan(it, "/${Marked.KEY} ".toByteArray(Charsets.ISO_8859_1)) } }.getOrDefault(false)

    private fun scan(input: java.io.InputStream, needle: ByteArray): Boolean {
        var matched = 0
        var b = input.read()
        while (b >= 0) {
            matched = if (b.toByte() == needle[matched]) matched + 1 else if (b.toByte() == needle[0]) 1 else 0
            if (matched == needle.size) return true
            b = input.read()
        }
        return false
    }

    /** True when [doc] contains a live watermark. */
    fun present(doc: PDDocument): Boolean {
        val form = doc.documentCatalog.cosObject.getCOSDictionary(ACROFORM) ?: return false
        val fields = form.getCOSArray(FIELDS) ?: return false
        return (0 until fields.size()).any { ours(fields.getObject(it)) }
    }

    /**
     * Add the live watermark for [spec] (text source; replaces an earlier live watermark). [fileName] and [now] give
     * the values of the static appearance; [faceFor] builds the face for the text style (colour already faded).
     */
    fun install(doc: PDDocument, spec: DecorSpec, fileName: String, faceFor: (E5TextStyle) -> LiveFace,
                date: LocalDate = LocalDate.now(), time: LocalTime = LocalTime.now()): Int {
        remove(doc)
        val n = doc.numberOfPages
        val targets = PageSet.filter(PageSet.parse(spec.range, n), spec.subset) { E5Io.isLandscape(doc.getPage(it)) }
        if (targets.isEmpty() || spec.text.isBlank()) return 0
        val style = spec.style.copy(color = LiveWmScript.fadedColor(spec.style.color, spec.opacity))
        val face = faceFor(style)
        val info = doc.documentInformation
        val base = TokenContext(pages = n, date = date, time = time, file = fileName, title = info.title ?: "", author = info.author ?: "", user = spec.user)

        val cat = doc.documentCatalog.cosObject
        val form = cat.getCOSDictionary(ACROFORM) ?: COSDictionary().also { cat.setItem(ACROFORM, it) }
        val fields = form.getCOSArray(FIELDS) ?: COSArray().also { form.setItem(FIELDS, it) }
        form.removeItem(COSName.getPDFName("NeedAppearances"))
        val dr = form.getCOSDictionary(COSName.DR) ?: COSDictionary().also { form.setItem(COSName.DR, it) }
        val drFonts = dr.getCOSDictionary(COSName.FONT) ?: COSDictionary().also { dr.setItem(COSName.FONT, it) }
        drFonts.setItem(COSName.getPDFName(LiveWmScript.DA_FONT), face.daFont())
        val c = E5Io.rgb(style.color)
        val da = "/${LiveWmScript.DA_FONT} ${num(style.size)} Tf ${num(c[0])} ${num(c[1])} ${num(c[2])} rg"

        val lineCount = spec.text.split('\n').count { it.isNotBlank() }.coerceAtLeast(1)
        val parent = COSDictionary().apply {
            setString(COSName.T, LiveWmScript.FIELD)
            setName(COSName.FT, "Tx")
            setInt(COSName.FF, LiveWmScript.fieldFlags(lineCount))
            setString(COSName.DA, da)
            setInt(COSName.Q, 1)
            setString(MARK_KEY, LiveWmScript.MARK)
        }
        val kids = COSArray()
        parent.setItem(KIDS, kids)
        fields.add(parent)

        for (i in targets) {
            val page = doc.getPage(i)
            val text = Tokens.expand(spec.text, base.copy(page = i + 1), spec.arabicDigits).split('\n').filter { it.isNotBlank() }
            if (text.isEmpty()) continue
            kids.add(widget(doc, page, i, parent, spec, style, face, text))
        }

        val js = LiveWmScript.script(spec.text, base, spec.user, spec.arabicDigits)
        addNamedScript(doc, js)
        chain(cat, OPEN_ACTION, jsAction(js))
        val aa = cat.getCOSDictionary(AA) ?: COSDictionary().also { cat.setItem(AA, it) }
        chain(aa, WP, jsAction(js))
        return kids.size()
    }

    private fun widget(doc: PDDocument, page: PDPage, index: Int, parent: COSDictionary, spec: DecorSpec, style: E5TextStyle, face: LiveFace, lines: List<String>): COSDictionary {
        val frame = PageFrame.of(page)
        val lh = style.size * 1.25f
        val textW = lines.maxOf { face.width(it) }.coerceAtLeast(style.size)
        val userRot = LiveWmScript.snapRotation(spec.rotation)
        val along = if (userRot % 180 == 0) frame.width else frame.height
        // room for longer live values (user names, dates): wider than the static text, within the page
        val w = maxOf(textW + style.size, minOf(textW * 1.6f + 2f * style.size, along - 12f))
        val h = lh * lines.size + style.size * 0.5f
        val bw = if (userRot % 180 == 0) w else h
        val bh = if (userRot % 180 == 0) h else w
        val cx = when (spec.hAlign) { -1 -> bw / 2f + 18f; 1 -> frame.width - bw / 2f - 18f; else -> frame.width / 2f } + spec.offsetX
        val cy = when (spec.vAlign) { -1 -> bh / 2f + 18f; 1 -> frame.height - bh / 2f - 18f; else -> frame.height / 2f } + spec.offsetY
        val corners = listOf(cx - bw / 2f to cy - bh / 2f, cx + bw / 2f to cy - bh / 2f, cx - bw / 2f to cy + bh / 2f, cx + bw / 2f to cy + bh / 2f)
            .map { (x, y) -> Affine.apply(frame.toUser, x, y) }
        val rect = PDRectangle(corners.minOf { it.first }, corners.minOf { it.second },
            corners.maxOf { it.first } - corners.minOf { it.first }, corners.maxOf { it.second } - corners.minOf { it.second })
        val rot = ((userRot + frame.rotation) % 360 + 360) % 360

        val ap = PDAppearanceStream(doc)
        ap.bBox = PDRectangle(0f, 0f, w, h)
        val res = PDResources()
        ap.resources = res
        ap.cosObject.setItem(COSName.MATRIX, rotationMatrix(rot))
        PDPageContentStream(doc, ap).use { cs ->
            @Suppress("DEPRECATION") cs.appendRawCommands("/Tx BMC\n")
            cs.saveGraphicsState()
            cs.addRect(1f, 1f, w - 2f, h - 2f); cs.clip()
            val top = h / 2f + lh * lines.size / 2f
            lines.forEachIndexed { k, l -> face.draw(cs, res, l, (w - face.width(l)) / 2f, top - lh * (k + 1) + style.size * 0.25f) }
            cs.restoreGraphicsState()
            @Suppress("DEPRECATION") cs.appendRawCommands("EMC\n")
        }

        return COSDictionary().apply {
            setName(COSName.TYPE, "Annot")
            setName(COSName.SUBTYPE, "Widget")
            setItem(COSName.PARENT, parent)
            setString(COSName.T, "p${index + 1}")
            setItem(COSName.RECT, rect.cosArray)
            setItem(COSName.P, page.cosObject)
            setInt(COSName.F, LiveWmScript.annotFlags(spec.onScreen, spec.onPrint))
            setItem(COSName.MK, COSDictionary().apply { setInt(COSName.R, rot) })
            setItem(COSName.V, COSString(lines.joinToString("\r")))
            setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, ap.cosObject) })
            setString(MARK_KEY, LiveWmScript.MARK)
            page.cosObject.let { pd ->
                val annots = pd.getCOSArray(COSName.ANNOTS) ?: COSArray().also { pd.setItem(COSName.ANNOTS, it) }
                annots.add(this)
            }
        }
    }

    private fun rotationMatrix(rot: Int): COSArray {
        val v = when (rot) { 90 -> floatArrayOf(0f, 1f, -1f, 0f, 0f, 0f); 180 -> floatArrayOf(-1f, 0f, 0f, -1f, 0f, 0f); 270 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, 0f); else -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f) }
        return COSArray().apply { v.forEach { add(COSInteger.get(it.toLong())) } }
    }

    private fun num(f: Float): String = if (f == Math.round(f).toFloat()) Math.round(f).toString() else String.format(java.util.Locale.ROOT, "%.3f", f)

    private fun jsAction(js: String) = COSDictionary().apply {
        setName(COSName.TYPE, "Action")
        setName(COSName.S, "JavaScript")
        setItem(COSName.JS, COSString(js))
        setString(MARK_KEY, LiveWmScript.MARK)
    }

    // ---------------- action chains ----------------

    /** Run [action] after whatever [holder]/[key] already does (an /OpenAction destination is wrapped in a GoTo). */
    private fun chain(holder: COSDictionary, key: COSName, action: COSDictionary) {
        when (val cur = deref(holder.getDictionaryObject(key))) {
            null -> holder.setItem(key, action)
            is COSArray -> holder.setItem(key, COSDictionary().apply {
                setName(COSName.TYPE, "Action"); setName(COSName.S, "GoTo"); setItem(COSName.D, cur); setItem(NEXT, action); setBoolean(WRAP_KEY, true)
            })
            is COSDictionary -> appendNext(cur, action)
            else -> holder.setItem(key, action)
        }
    }

    private fun appendNext(a: COSDictionary, action: COSDictionary) {
        when (val next = deref(a.getDictionaryObject(NEXT))) {
            null -> a.setItem(NEXT, action)
            is COSDictionary -> a.setItem(NEXT, COSArray().apply { add(next); add(action) })
            is COSArray -> next.add(action)
            else -> a.setItem(NEXT, action)
        }
    }

    /** Remove our actions from the chain at [holder]/[key], restoring a wrapped destination. */
    private fun unchain(holder: COSDictionary, key: COSName) {
        val cur = deref(holder.getDictionaryObject(key)) as? COSDictionary ?: return
        if (ours(cur)) { holder.removeItem(key); return }
        stripNext(cur)
        if (cur.getBoolean(WRAP_KEY, false) && cur.getDictionaryObject(NEXT) == null) {
            val d = cur.getDictionaryObject(COSName.D)
            if (d != null) holder.setItem(key, d) else holder.removeItem(key)
        }
    }

    private fun stripNext(a: COSDictionary) {
        when (val next = deref(a.getDictionaryObject(NEXT))) {
            is COSDictionary -> if (ours(next)) a.removeItem(NEXT) else stripNext(next)
            is COSArray -> {
                for (k in next.size() - 1 downTo 0) {
                    val e = deref(next.getObject(k)) as? COSDictionary ?: continue
                    if (ours(e)) next.remove(k) else stripNext(e)
                }
                when (next.size()) { 0 -> a.removeItem(NEXT); 1 -> a.setItem(NEXT, next.get(0)) }
            }
            else -> Unit
        }
    }

    // ---------------- /Names /JavaScript ----------------

    private fun jsTree(doc: PDDocument, create: Boolean): Pair<COSDictionary, COSDictionary>? {
        val cat = doc.documentCatalog.cosObject
        val names = cat.getCOSDictionary(NAMES) ?: if (create) COSDictionary().also { cat.setItem(NAMES, it) } else return null
        val tree = names.getCOSDictionary(JAVASCRIPT) ?: if (create) COSDictionary().also { names.setItem(JAVASCRIPT, it) } else return null
        return names to tree
    }

    /** All (name, action) entries of a name tree, kids included. */
    private fun entries(node: COSDictionary, out: MutableList<Pair<String, COSBase>>, depth: Int = 0) {
        if (depth > 20) return
        node.getCOSArray(NAMES)?.let { a -> var k = 0; while (k + 1 < a.size()) { (deref(a.getObject(k)) as? COSString)?.let { out += it.string to a.get(k + 1) }; k += 2 } }
        node.getCOSArray(KIDS)?.let { a -> for (k in 0 until a.size()) (deref(a.getObject(k)) as? COSDictionary)?.let { entries(it, out, depth + 1) } }
    }

    private fun writeTree(tree: COSDictionary, list: List<Pair<String, COSBase>>) {
        tree.removeItem(KIDS); tree.removeItem(COSName.LIMITS)
        tree.setItem(NAMES, COSArray().apply { list.sortedBy { it.first }.forEach { (n, v) -> add(COSString(n)); add(v) } })
    }

    private fun addNamedScript(doc: PDDocument, js: String) {
        val (_, tree) = jsTree(doc, true)!!
        val list = ArrayList<Pair<String, COSBase>>()
        entries(tree, list)
        list.removeAll { it.first == LiveWmScript.JS_NAME || ours(it.second) }
        list += LiveWmScript.JS_NAME to jsAction(js)
        writeTree(tree, list)
    }

    private fun removeNamedScript(doc: PDDocument) {
        val (names, tree) = jsTree(doc, false) ?: return
        val list = ArrayList<Pair<String, COSBase>>()
        entries(tree, list)
        if (list.none { it.first == LiveWmScript.JS_NAME || ours(it.second) }) return
        list.removeAll { it.first == LiveWmScript.JS_NAME || ours(it.second) }
        if (list.isEmpty()) {
            names.removeItem(JAVASCRIPT)
            if (names.size() == 0) doc.documentCatalog.cosObject.removeItem(NAMES)
        } else writeTree(tree, list)
    }

    // ---------------- removal ----------------

    /** Remove the fields, widgets, /DR font and scripts of a live watermark. Returns the number of widgets removed. */
    fun remove(doc: PDDocument): Int {
        var removed = 0
        for (page in doc.pages) {
            val annots = page.cosObject.getCOSArray(COSName.ANNOTS) ?: continue
            for (k in annots.size() - 1 downTo 0) {
                val a = deref(annots.getObject(k)) as? COSDictionary ?: continue
                if (ours(a) || ours(a.getDictionaryObject(COSName.PARENT))) { annots.remove(k); removed++ }
            }
            if (annots.size() == 0) page.cosObject.removeItem(COSName.ANNOTS)
        }
        val cat = doc.documentCatalog.cosObject
        cat.getCOSDictionary(ACROFORM)?.let { form ->
            form.getCOSArray(FIELDS)?.let { f -> for (k in f.size() - 1 downTo 0) if (ours(f.getObject(k))) f.remove(k) }
            form.getCOSDictionary(COSName.DR)?.getCOSDictionary(COSName.FONT)?.removeItem(COSName.getPDFName(LiveWmScript.DA_FONT))
            val empty = (form.getCOSArray(FIELDS)?.size() ?: 0) == 0 && form.getDictionaryObject(COSName.getPDFName("XFA")) == null
            if (empty) cat.removeItem(ACROFORM)
        }
        removeNamedScript(doc)
        unchain(cat, OPEN_ACTION)
        cat.getCOSDictionary(AA)?.let { aa -> unchain(aa, WP); if (aa.size() == 0) cat.removeItem(AA) }
        return removed
    }
}
