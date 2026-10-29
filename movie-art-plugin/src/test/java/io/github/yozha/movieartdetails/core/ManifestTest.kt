package io.github.yozha.movieartdetails.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Package visibility for source-app badges: launcher apps through `<queries>`, never QUERY_ALL_PACKAGES. */
class ManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest: Element by lazy {
        // Unit tests run in the module directory.
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("manifest at ${file.absolutePath}", file.isFile)
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.androidName(): String = getAttributeNS(android, "name")

    @Test
    fun `queries make launcher apps visible`() {
        val intents = manifest.children("queries").flatMap { it.children("intent") }.map { intent ->
            intent.children("action").map { it.androidName() }.toSet() to intent.children("category").map { it.androidName() }.toSet()
        }
        val main = "android.intent.action.MAIN"
        assertTrue(intents.toString(), setOf(main) to setOf("android.intent.category.LEANBACK_LAUNCHER") in intents)
        assertTrue(intents.toString(), setOf(main) to setOf("android.intent.category.LAUNCHER") in intents)
        val packages = manifest.children("queries").flatMap { it.children("package") }.map { it.androidName() }
        assertTrue(packages.toString(), ProjectivyHost.PROJECTIVY_PACKAGE in packages)
    }

    @Test
    fun `no permission to see all packages`() {
        val permissions = manifest.children("uses-permission").map { it.androidName() }
        assertFalse(permissions.toString(), "android.permission.QUERY_ALL_PACKAGES" in permissions)
    }
}
