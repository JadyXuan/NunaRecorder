package com.example.nunarecorder.privacy

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class BackupPolicyTest {

    private val allBackupDomains = setOf(
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref"
    )

    @Test
    fun `application opts out of Android backup infrastructure`() {
        val application = document("AndroidManifest.xml")
            .getElementsByTagName("application")
            .item(0) as Element

        assertEquals(
            "false",
            application.getAttributeNS(ANDROID_NAMESPACE, "allowBackup")
        )
    }

    @Test
    fun `Android 12 rules deny both cloud backup and device transfer`() {
        val rules = document("res/xml/data_extraction_rules.xml")

        assertDenyAll(rules.getElementsByTagName("cloud-backup").item(0) as Element)
        // allowBackup=false alone looks sufficient, but some Android 12+ OEMs can
        // still perform device-to-device migration. This assertion locks that gap.
        assertDenyAll(rules.getElementsByTagName("device-transfer").item(0) as Element)
    }

    @Test
    fun `legacy backup rules deny every app-data domain`() {
        val rules = document("res/xml/backup_rules.xml").documentElement
        assertDenyAll(rules)
    }

    private fun assertDenyAll(parent: Element) {
        val excludes = parent.getElementsByTagName("exclude")
        val excludedDomains = (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .onEach { assertEquals(".", it.getAttribute("path")) }
            .map { it.getAttribute("domain") }
            .toSet()

        assertEquals(allBackupDomains, excludedDomains)
        assertFalse("deny-all policy must never contain include rules", parent.hasElements("include"))
    }

    private fun Element.hasElements(tagName: String): Boolean =
        getElementsByTagName(tagName).length > 0

    private fun document(relativePath: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main", relativePath))

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
