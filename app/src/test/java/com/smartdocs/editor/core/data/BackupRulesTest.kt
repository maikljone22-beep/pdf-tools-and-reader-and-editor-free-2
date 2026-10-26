package com.smartdocs.editor.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Audit DI-14 / DI-17 / DI-08: secrets never leave the device; Room schema is exported with no destructive upgrade. */
class BackupRulesTest {
    private val res = File("src/main/res/xml")

    private fun excludes(file: String, section: String?): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, file))
        val scope: Element = if (section == null) doc.documentElement else doc.getElementsByTagName(section).item(0) as Element
        val n = scope.getElementsByTagName("exclude")
        return (0 until n.length).map { (n.item(it) as Element).let { e -> e.getAttribute("domain") + ":" + e.getAttribute("path") } }.toSet()
    }

    private val secrets = listOf(
        "sharedpref:cloud_drive.xml", "sharedpref:cloud_onedrive.xml", "sharedpref:cloud_dropbox.xml", "sharedpref:cloud_box.xml",
        "sharedpref:oauth_pending.xml", "sharedpref:license_state.xml", "sharedpref:pro_entitlement.xml", "sharedpref:connected_keys.xml",
        "file:datastore/settings.preferences_pb", "database:search_index.db",
    )

    @Test fun secretsExcludedEverywhere() {
        for ((file, section) in listOf("data_extraction_rules.xml" to "cloud-backup", "data_extraction_rules.xml" to "device-transfer", "backup_rules.xml" to null)) {
            val ex = excludes(file, section)
            for (s in secrets) assertTrue("$s missing in $file/$section", s in ex)
        }
    }

    @Test fun cloudBackupStaysSmall() {
        for ((file, section) in listOf("data_extraction_rules.xml" to "cloud-backup", "backup_rules.xml" to null)) {
            val ex = excludes(file, section)
            for (s in listOf("external:Documents/", "file:tessdata/", "file:trash/")) assertTrue("$s in $file", s in ex)
        }
    }

    @Test fun manifestReferencesRules() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(m.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }

    @Test fun roomSchemaExportedAndNoDestructiveUpgrade() {
        val src = File("src/main/java/com/smartdocs/editor/core/data/AppDatabase.kt").readText()
        assertTrue(src.contains("exportSchema = true"))
        assertFalse(Regex("""\.fallbackToDestructiveMigration\(""").containsMatchIn(src))
        val version = Regex("""version\s*=\s*(\d+)""").find(src)!!.groupValues[1]
        assertTrue("schema $version.json must be committed", File("schemas/com.smartdocs.editor.core.data.AppDatabase/$version.json").isFile)
    }
}
