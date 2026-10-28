package com.smartdocs.editor.core.convert6

import java.io.File

/** A message that is already localised for the user (shown as it is by [Cv6Errors]). */
open class UserMessage(message: String) : Exception(message)

/** A password-protected PDF input: [file] needs a password, [wrong] = the one given did not open it. */
class Cv6NeedPassword(val file: File, val wrong: Boolean, cause: Throwable? = null) : Exception("password needed: " + file.name, cause)

/** An Office file protected with a password (OOXML in an encrypted CFB container). */
class EncryptedOffice(val name: String) : Exception("encrypted office file")

/**
 * The PDF's owner restricts what this conversion would do (modify / assemble / an unprotected copy) and was not opened
 * with its owner password (convert audit item 8 / C16): refused instead of writing an unrestricted copy.
 */
class RestrictedInput(val name: String) : Exception("restricted: $name")

/** The page range of [name] selects no page (or is not a valid range). */
class InvalidRange(val name: String) : Exception("range: $name")

/** The page could not be printed because the WebView renderer process died (usually out of memory). */
class RendererGone(val crashed: Boolean) : java.io.IOException("renderer gone")

/** What went wrong, for a localised message (convert audit A-H8 / B16 / C12: no raw English, no class names). */
enum class Cv6Err {
    OUT_OF_MEMORY, TOO_LARGE, TIMEOUT, NETWORK, HTTP, DAMAGED, DAMAGED_PDF, PASSWORD, WRONG_PASSWORD, ENCRYPTED, UNSUPPORTED,
    IMAGE, IMAGE_WRITE, DISK_FULL, NAME_TOO_LONG, OCR, EMPTY, RESTRICTED, RANGE, USER, UNKNOWN,
}

/** Classification of conversion failures. Pure (exception types from Android libraries are matched by name). */
object Cv6Errors {

    class Classified(val kind: Cv6Err, val detail: String? = null, val code: Int = 0)

    private fun chain(t: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>(); var c: Throwable? = t
        while (c != null && out.size < 8 && c !in out) { out += c; c = c.cause }
        return out
    }

    fun classify(t: Throwable): Classified {
        val all = chain(t)
        for (e in all) {
            val name = e.javaClass.simpleName
            val msg = e.message.orEmpty()
            when {
                e is UserMessage -> return Classified(Cv6Err.USER, msg)
                e is Cv6NeedPassword -> return Classified(if (e.wrong) Cv6Err.WRONG_PASSWORD else Cv6Err.PASSWORD, e.file.name)
                e is EncryptedOffice || name == "LegacyEncrypted" -> return Classified(Cv6Err.ENCRYPTED)
                e is RendererGone -> return Classified(Cv6Err.TOO_LARGE)
                e is OutOfMemoryError -> return Classified(Cv6Err.OUT_OF_MEMORY)
                name == "InvalidPasswordException" || name == "PasswordNeeded" || name == "PasswordRequiredException" -> return Classified(Cv6Err.PASSWORD)
                e is RestrictedInput -> return Classified(Cv6Err.RESTRICTED, e.name)
                e is InvalidRange -> return Classified(Cv6Err.RANGE, e.name)
                name == "UnsupportedSource" -> return Classified(Cv6Err.UNSUPPORTED, msg.removePrefix("unsupported: ").ifBlank { null })
                name == "UnsupportedImage" -> return Classified(Cv6Err.IMAGE, msg)
                name == "ImageWriteFailed" -> return Classified(Cv6Err.IMAGE_WRITE)
            }
        }
        for (e in all) {
            val name = e.javaClass.simpleName
            val msg = e.message.orEmpty()
            val low = msg.lowercase()
            when {
                "enospc" in low || "no space left" in low || "disk full" in low -> return Classified(Cv6Err.DISK_FULL)
                "enametoolong" in low || "file name too long" in low -> return Classified(Cv6Err.NAME_TOO_LONG)
                name == "TimeoutException" || name == "SocketTimeoutException" || name == "TimeoutCancellationException" || low == "timeout" -> return Classified(Cv6Err.TIMEOUT)
                name == "UnknownHostException" || name == "ConnectException" || name == "NoRouteToHostException" || name == "SSLException" ||
                    name == "SSLHandshakeException" || low.startsWith("net::err_") || name == "OfflineException" -> return Classified(Cv6Err.NETWORK)
                Regex("""^http (\d{3})\b""").find(low) != null -> return Classified(Cv6Err.HTTP, code = Regex("""^http (\d{3})""").find(low)!!.groupValues[1].toInt())
                "ocr init" in low || "tesseract" in low -> return Classified(Cv6Err.OCR)
                low == "empty pdf" || low == "list is empty." || low == "empty" -> return Classified(Cv6Err.EMPTY)
                "not a compound file" in low || "not a zip" in low || name == "ZipException" || "no content.xml" in low || "not an epub" in low ||
                    "no opf" in low || "not a tiff" in low || name == "XmlPullParserException" || name == "SAXParseException" || "unexpected end of zlib" in low ||
                    name == "EOFException" || name == "UTFDataFormatException" || "not a valid unicode code point" in low -> return Classified(Cv6Err.DAMAGED)
                "end-of-file" in low || "header doesn't contain versioninfo" in low || "missing root object" in low || "expected a long type" in low ||
                    "xref" in low || "cosstream" in low || "trailer" in low -> return Classified(Cv6Err.DAMAGED_PDF)
            }
        }
        return Classified(Cv6Err.UNKNOWN)
    }
}
