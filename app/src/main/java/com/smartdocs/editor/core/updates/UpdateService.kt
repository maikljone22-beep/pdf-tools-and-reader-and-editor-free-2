package com.smartdocs.editor.core.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateChannelUnavailable : java.io.IOException()
object UpdateFiles {
    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun readFeed(input: InputStream): String {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            if (out.size() + count > 64 * 1024) throw UpdateException(UpdateProblem.INVALID_FEED)
            out.write(buffer, 0, count)
        }
        return out.toString(Charsets.UTF_8.name())
    }
    fun verifyHash(file: File, artifact: UpdateArtifact) {
        if (!file.isFile || file.length() != artifact.size || sha256(file) != artifact.sha256) throw UpdateException(UpdateProblem.CHECKSUM)
    }
}

object UpdateService {
    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(com.smartdocs.editor.core.data.NpfNet.interceptor)
            .addNetworkInterceptor { chain ->
                if (!chain.request().url.isHttps) throw UpdateException(UpdateProblem.UNSAFE_URL)
                chain.proceed(chain.request())
            }.connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }
    @Suppress("DEPRECATION") private fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION") private fun identity(info: PackageInfo): ApkIdentity {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val current = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
        val history = if (Build.VERSION.SDK_INT >= 28 && info.signingInfo?.hasMultipleSigners() == false) info.signingInfo?.signingCertificateHistory.orEmpty() else emptyArray()
        return ApkIdentity(info.packageName, version(info), info.applicationInfo?.minSdkVersion ?: 1,
            current.map { hash(it.toByteArray()) }.toSet(), history.map { hash(it.toByteArray()) }.toSet())
    }
    @Suppress("DEPRECATION") private fun flags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    suspend fun check(context: Context, url: String): UpdateOffer? = withContext(Dispatchers.IO) {
        val installed = context.packageManager.getPackageInfo(context.packageName, flags())
        client.newCall(Request.Builder().url(UpdateUrls.requireHttps(url)).build()).execute().use { response ->
            if (response.code == 404) throw UpdateChannelUnavailable()
            if (!response.isSuccessful) throw UpdateException(UpdateProblem.DOWNLOAD)
            val body = response.body ?: throw UpdateException(UpdateProblem.INVALID_FEED)
            val json = body.byteStream().use(UpdateFiles::readFeed)
            coroutineContext.ensureActive()
            UpdateManifest.parse(json, context.packageName, version(installed), Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())
        }
    }
    suspend fun download(context: Context, offer: UpdateOffer, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "app_updates").apply { check(mkdirs() || isDirectory) }
        val target = File(dir, "update-${offer.versionCode}-${offer.artifact.sha256}.apk")
        if (target.isFile) {
            try { verify(context, target, offer); return@withContext target }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { target.delete() }
        }
        val partial = File.createTempFile("update-${offer.versionCode}-", ".partial.apk", dir)
        try {
            client.newCall(Request.Builder().url(UpdateUrls.requireHttps(offer.artifact.url)).build()).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException(UpdateProblem.DOWNLOAD)
                val body = response.body ?: throw UpdateException(UpdateProblem.DOWNLOAD)
                if (body.contentLength() >= 0 && body.contentLength() != offer.artifact.size) throw UpdateException(UpdateProblem.CHECKSUM)
                body.byteStream().use { input -> partial.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        if (total > offer.artifact.size) throw UpdateException(UpdateProblem.CHECKSUM)
                        out.write(buffer, 0, count); progress(total, offer.artifact.size)
                    }
                } }
            }
            verify(context, partial, offer)
            coroutineContext.ensureActive()
            check(partial.renameTo(target))
            // Retain only the update that has just been verified, never unrelated application files.
            dir.listFiles()?.filter { it != target && it.name.startsWith("update-") && it.name.endsWith(".apk") && !it.name.endsWith(".partial.apk") }?.forEach { it.delete() }
            target
        } finally { partial.delete() }
    }
    suspend fun verify(context: Context, file: File, offer: UpdateOffer) = withContext(Dispatchers.IO) {
        UpdateFiles.verifyHash(file, offer.artifact)
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags())
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, flags()) ?: throw UpdateException(UpdateProblem.SIGNATURE)
        coroutineContext.ensureActive()
        UpdateIdentity.verify(identity(installed), identity(candidate), offer, Build.VERSION.SDK_INT)
    }
}
