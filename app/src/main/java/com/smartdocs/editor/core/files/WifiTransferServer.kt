package com.smartdocs.editor.core.files

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny HTTP server for transferring documents over the local network, protected by a 6-digit PIN.
 *
 * GET  /            → login page (PIN) or, once signed in, the RTL Arabic file list + upload form
 * POST /login       → form field `pin`; sets an HttpOnly session cookie (5 wrong tries per address → 60 s lockout)
 * GET  /dl/<name>   → downloads a file from [root]            (session required)
 * POST /upload      → multipart/form-data upload into [root]   (session required, streamed to disk)
 * GET  /logout      → ends the session
 *
 * Stops by itself after [idleTimeoutMs] without requests ([onAutoStop] is then called on a worker thread).
 * Set [pin] to null to disable the PIN (not recommended; kept for API compatibility).
 */
class WifiTransferServer(
    private val root: File,
    private val preferredPort: Int = 8080,
    val pin: String? = newPin(),
    var idleTimeoutMs: Long = 10 * 60_000L,
) {
    @Volatile private var socket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, Pair<Int, Long>>() // address → (count, lockedUntil)
    @Volatile private var lastActivity = System.currentTimeMillis()
    var port: Int = preferredPort; private set

    /** share6: listen only on this address (Wi‑Fi only mode); null = every interface. Set before [start]. */
    var bindAddress: java.net.InetAddress? = null

    /** Callback invoked (on a worker thread) after an upload changed the folder. */
    var onFilesChanged: (() -> Unit)? = null
    /** Callback invoked (on a worker thread) when the server stopped because it was idle. */
    var onAutoStop: (() -> Unit)? = null

    val isRunning get() = running.get()

    /** Milliseconds left before the idle auto-stop. */
    val idleRemainingMs: Long get() = (idleTimeoutMs - (System.currentTimeMillis() - lastActivity)).coerceAtLeast(0L)

    @Throws(IOException::class)
    fun start() {
        if (running.get()) return
        var ss: ServerSocket? = null
        var p = preferredPort
        var lastErr: IOException? = null
        for (i in 0 until 20) {
            try { ss = ServerSocket(p, 50, bindAddress); break } catch (e: IOException) { lastErr = e; p++ }
        }
        socket = ss ?: throw (lastErr ?: IOException("no free port"))
        port = p
        running.set(true)
        lastActivity = System.currentTimeMillis()
        Thread({
            while (running.get()) {
                val client = try { socket?.accept() ?: break } catch (e: IOException) { break }
                pool.execute { runCatching { handle(client) }; runCatching { client.close() } }
            }
        }, "wifi-transfer").apply { isDaemon = true }.start()
        Thread({
            while (running.get()) {
                try { Thread.sleep(5_000) } catch (e: InterruptedException) { break }
                if (running.get() && idleTimeoutMs > 0 && System.currentTimeMillis() - lastActivity > idleTimeoutMs) {
                    stop(); onAutoStop?.invoke(); break
                }
            }
        }, "wifi-transfer-idle").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        sessions.clear()
    }

    val url: String? get() = deviceIp()?.let { "http://$it:$port" }

    // ----------------------------------------------------------------------------------------- HTTP

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase(); val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val idx = l.indexOf(':'); if (idx > 0) headers[l.substring(0, idx).trim().lowercase()] = l.substring(idx + 1).trim()
        }
        lastActivity = System.currentTimeMillis()
        val path = target.substringBefore('?')
        val address = client.inetAddress?.hostAddress ?: "?"
        val authed = pin == null || sessionOf(headers)?.let { it in sessions } == true
        when {
            method == "POST" && path == "/login" -> handleLogin(input, headers, address, output)
            method == "GET" && path == "/logout" -> { sessionOf(headers)?.let { sessions.remove(it) }; redirect(output, "/", clearCookie = true) }
            !authed && method == "GET" && (path == "/" || path == "/index.html") -> sendHtml(output, loginPage(null))
            !authed -> sendText(output, 401, "Unauthorized")
            method == "GET" && (path == "/" || path == "/index.html") -> sendHtml(output, indexPage())
            method == "GET" && path.startsWith("/dl/") -> sendFile(output, path.removePrefix("/dl/"))
            method == "POST" && path == "/upload" -> { handleUpload(input, headers); redirect(output, "/") }
            else -> sendText(output, 404, "Not Found")
        }
        output.flush()
    }

    private fun sessionOf(headers: Map<String, String>): String? =
        headers["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("sd_session=") }?.removePrefix("sd_session=")

    private fun handleLogin(input: InputStream, headers: Map<String, String>, address: String, out: OutputStream) {
        val now = System.currentTimeMillis()
        val (count, lockedUntil) = failures[address] ?: (0 to 0L)
        if (now < lockedUntil) { sendHtml(out, loginPage("محاولات كثيرة خاطئة. انتظر دقيقة ثم حاول مجدداً.")); return }
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 4096) ?: 0
        val body = ByteArray(len); var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
        val form = String(body, 0, read, Charsets.UTF_8).split('&').associate {
            val k = it.substringBefore('='); val v = it.substringAfter('=', "")
            k to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
        val entered = form["pin"]?.filter { it.isDigit() }
        if (pin != null && entered == pin) {
            failures.remove(address)
            val token = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            sessions += token
            out.write(("HTTP/1.1 303 See Other\r\nLocation: /\r\nSet-Cookie: sd_session=$token; HttpOnly; SameSite=Strict; Path=/\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
        } else {
            val c = count + 1
            failures[address] = if (c >= 5) 0 to now + 60_000L else c to 0L
            sendHtml(out, loginPage("الرمز غير صحيح"))
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun files(): List<File> = (root.listFiles() ?: emptyArray())
        .filter { it.isFile && it.extension.lowercase() in setOf("pdf", "jpg", "jpeg", "png", "txt", "docx", "html") }
        .sortedByDescending { it.lastModified() }

    private val style = "<style>body{font-family:'Segoe UI',Tahoma,Arial,sans-serif;background:#f4f6f8;margin:0;padding:24px;color:#1f2937}" +
        ".card{background:#fff;border-radius:14px;box-shadow:0 2px 10px rgba(0,0,0,.06);padding:20px;max-width:760px;margin:0 auto 20px}" +
        "h1{font-size:22px;margin:0 0 6px}h2{font-size:17px;margin:0 0 12px}p{color:#6b7280;margin:0 0 12px}" +
        "ul{list-style:none;padding:0;margin:0}li{display:flex;align-items:center;justify-content:space-between;padding:10px 4px;border-bottom:1px solid #eef0f3}" +
        "a.dl{background:#2563eb;color:#fff;text-decoration:none;padding:6px 14px;border-radius:8px;font-size:14px}.sz{color:#9ca3af;font-size:13px;margin-inline-start:10px}" +
        "button{background:#059669;color:#fff;border:0;padding:10px 18px;border-radius:8px;font-size:15px;cursor:pointer}input[type=file]{margin-bottom:12px}" +
        "input.pin{font-size:28px;letter-spacing:10px;text-align:center;width:220px;padding:8px;border:1px solid #d1d5db;border-radius:10px;direction:ltr}" +
        ".err{color:#dc2626}.top{display:flex;justify-content:space-between;align-items:center}a.out{color:#6b7280;font-size:14px}</style>"

    private fun head(title: String) = "<!DOCTYPE html><html lang=\"ar\" dir=\"rtl\"><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>$title</title>$style</head><body>"

    private fun loginPage(error: String?): String = head("محرر الملفات الذكي — رمز الدخول") +
        "<div class=\"card\" style=\"text-align:center\"><h1>محرر الملفات الذكي</h1><p>أدخل الرمز المكوّن من 6 أرقام الظاهر على شاشة الهاتف</p>" +
        (if (error != null) "<p class=\"err\">${escape(error)}</p>" else "") +
        "<form method=\"post\" action=\"/login\"><input class=\"pin\" name=\"pin\" inputmode=\"numeric\" autocomplete=\"one-time-code\" maxlength=\"6\" autofocus required><br><br>" +
        "<button type=\"submit\">دخول</button></form></div></body></html>"

    private fun indexPage(): String {
        val sb = StringBuilder(head("محرر الملفات الذكي — النقل عبر الشبكة"))
        sb.append("<div class=\"card\"><div class=\"top\"><h1>محرر الملفات الذكي</h1><a class=\"out\" href=\"/logout\">خروج</a></div>")
        sb.append("<p>النقل عبر الشبكة — نزّل ملفاتك أو ارفع ملفات جديدة إلى الهاتف</p></div>")
        sb.append("<div class=\"card\"><h2>رفع ملفات إلى الهاتف</h2><form method=\"post\" action=\"/upload\" enctype=\"multipart/form-data\">")
        sb.append("<input type=\"file\" name=\"file\" multiple accept=\".pdf,.jpg,.jpeg,.png,.txt,.docx,.html\"><br><button type=\"submit\">رفع</button></form></div>")
        sb.append("<div class=\"card\"><h2>الملفات على الهاتف</h2>")
        val list = files()
        if (list.isEmpty()) sb.append("<p>لا توجد ملفات بعد</p>") else {
            sb.append("<ul>")
            list.forEach { f ->
                val enc = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
                sb.append("<li><span>").append(escape(f.name)).append("<span class=\"sz\">").append(DocumentRepository.formatSize(f.length())).append("</span></span>")
                sb.append("<a class=\"dl\" href=\"/dl/").append(enc).append("\" download>تنزيل</a></li>")
            }
            sb.append("</ul>")
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun sendHtml(out: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
    }

    private fun sendText(out: OutputStream, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $code $text\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
    }

    private fun redirect(out: OutputStream, to: String, clearCookie: Boolean = false) {
        val cookie = if (clearCookie) "Set-Cookie: sd_session=; Max-Age=0; Path=/\r\n" else ""
        out.write("HTTP/1.1 303 See Other\r\nLocation: $to\r\n${cookie}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    private fun sendFile(out: OutputStream, encodedName: String) {
        val name = runCatching { URLDecoder.decode(encodedName, "UTF-8") }.getOrDefault(encodedName)
        val f = File(root, name)
        if (name.contains("..") || name.contains('/') || !f.isFile || f.parentFile?.canonicalPath != root.canonicalPath) { sendText(out, 404, "Not Found"); return }
        val mime = when (f.extension.lowercase()) { "pdf" -> "application/pdf"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "txt" -> "text/plain; charset=utf-8"; "html" -> "text/html; charset=utf-8"; else -> "application/octet-stream" }
        val enc = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
        out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${f.length()}\r\nContent-Disposition: attachment; filename*=UTF-8''$enc\r\nConnection: close\r\n\r\n".toByteArray())
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); lastActivity = System.currentTimeMillis() }
        }
    }

    // ------------------------------------------------------------------------------------- upload

    /** Streams multipart parts straight to disk (no whole-body buffering). */
    private fun handleUpload(input: InputStream, headers: Map<String, String>) {
        val ct = headers["content-type"] ?: return
        val boundary = Regex("boundary=\"?([^\";]+)\"?").find(ct)?.groupValues?.get(1) ?: return
        val length = headers["content-length"]?.toLongOrNull() ?: return
        if (length > MAX_UPLOAD) return
        val limited = LimitedInput(input, length)
        val delim = "\r\n--$boundary".toByteArray()
        // the body starts with "--boundary" (no leading CRLF)
        if (readLine(limited) != "--$boundary") return
        var saved = 0
        while (true) {
            val partHeaders = StringBuilder()
            while (true) { val l = readLine(limited) ?: return; if (l.isEmpty()) break; partHeaders.append(l).append('\n') }
            val fileName = Regex("filename=\"([^\"]*)\"").find(partHeaders)?.groupValues?.get(1)?.let { File(it).name }?.takeIf { it.isNotBlank() }
            val target = fileName?.let { uniqueTarget(it) }
            val tmp = target?.let { File(root, ".${it.name}.upload") }
            val sink: OutputStream = tmp?.outputStream()?.buffered() ?: NullOutput
            val complete = sink.use { copyUntil(limited, delim, it) }
            if (tmp != null && target != null) {
                if (complete && tmp.length() > 0) { if (!tmp.renameTo(target)) { tmp.copyTo(target, true); tmp.delete() }; saved++ } else tmp.delete()
            }
            if (!complete) break
            val a = limited.read(); val b = limited.read()
            if (a == '-'.code && b == '-'.code) break // closing boundary
            if (a != '\r'.code || b != '\n'.code) break
        }
        if (saved > 0) onFilesChanged?.invoke()
    }

    /** Copies bytes to [out] until [delim] is found (delimiter consumed, not written). Returns false on EOF. */
    private fun copyUntil(input: InputStream, delim: ByteArray, out: OutputStream): Boolean {
        val window = ByteArray(delim.size); var filled = 0; var start = 0
        var counter = 0
        while (true) {
            val c = input.read()
            if (c < 0) { for (i in 0 until filled) out.write(window[(start + i) % delim.size].toInt()); return false }
            if (filled == delim.size) { out.write(window[start].toInt()); window[start] = c.toByte(); start = (start + 1) % delim.size }
            else { window[(start + filled) % delim.size] = c.toByte(); filled++ }
            if (filled == delim.size && window[start] == delim[0]) {
                var match = true
                for (i in 1 until delim.size) if (window[(start + i) % delim.size] != delim[i]) { match = false; break }
                if (match) return true
            }
            if (++counter and 0xFFFF == 0) lastActivity = System.currentTimeMillis()
        }
    }

    private class LimitedInput(private val inner: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int { if (remaining <= 0) return -1; val c = inner.read(); if (c >= 0) remaining--; return c }
    }

    private object NullOutput : OutputStream() { override fun write(b: Int) {} }

    private fun uniqueTarget(name: String): File {
        val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trimStart('.')
        val base = clean.substringBeforeLast('.'); val ext = clean.substringAfterLast('.', "")
        var f = File(root, clean); var n = 2
        while (f.exists()) { f = File(root, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"); n++ }
        return f
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private const val MAX_UPLOAD = 1024L * 1024 * 1024
        private val random = SecureRandom()

        /** Random 6-digit PIN (leading zeros allowed). */
        fun newPin(): String = "%06d".format(random.nextInt(1_000_000))

        /** Best-effort LAN IPv4 of this device (prefers the Wi-Fi / hotspot interface). */
        /** share6: address of the Wi‑Fi (or hotspot) interface only; null when not on Wi‑Fi. */
        fun wifiIp(): String? {
            val candidates = ArrayList<Pair<String, String>>()
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                    if (!ni.isUp || ni.isLoopback) return@forEach
                    val n = ni.name
                    if (!(n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap"))) return@forEach
                    ni.inetAddresses.toList().forEach { addr ->
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) candidates += n to (addr.hostAddress ?: return@forEach)
                    }
                }
            }
            return (candidates.firstOrNull { it.first.startsWith("wlan") } ?: candidates.firstOrNull())?.second
        }

        fun deviceIp(): String? {
            val candidates = ArrayList<Pair<String, String>>()
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                    if (!ni.isUp || ni.isLoopback) return@forEach
                    ni.inetAddresses.toList().forEach { addr ->
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) candidates += ni.name to (addr.hostAddress ?: return@forEach)
                    }
                }
            }
            return (candidates.firstOrNull { it.first.startsWith("wlan") } ?: candidates.firstOrNull { it.first.startsWith("ap") || it.first.startsWith("swlan") } ?: candidates.firstOrNull())?.second
        }
    }
}
