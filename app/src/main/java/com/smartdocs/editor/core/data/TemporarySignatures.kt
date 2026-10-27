package com.smartdocs.editor.core.data

import com.smartdocs.editor.SmartDocsApp
import java.io.File

/**
 * "Discard the signature when the app closes" (nitro-fillsign2): a temporary signature is marked in the
 * [SignatureMeta] sidecar and deleted (image, encrypted copy, Room row, sidecar entry) at the next app start,
 * i.e. once the session it was made in has ended. It is never synced or exported (core/share5/AppDataBundle).
 */
object TemporarySignatures {
    suspend fun cleanUp(app: SmartDocsApp) {
        SignatureMetaStore.temporaryPaths(app).forEach { path ->
            runCatching { File(path).delete(); LockedSignatureFiles.blobOf(File(path)).delete() }
            runCatching { app.db.signatures().deleteByPath(path) }
            SignatureMetaStore.remove(app, path)
        }
    }
}
