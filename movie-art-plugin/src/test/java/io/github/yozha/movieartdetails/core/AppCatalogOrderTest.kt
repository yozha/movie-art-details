package io.github.yozha.movieartdetails.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Order of the app list on the source app screen. */
class AppCatalogOrderTest {

    @Test
    fun `TV apps come first, then the rest, each sorted by label`() {
        val tv = listOf(app("tv.zeta", "Zeta TV"), app("tv.alpha", "alpha TV"), app("both.app", "Both"))
        val phone = listOf(app("phone.beta", "Beta"), app("both.app", "Both"), app("phone.aaa", "Aaa"), app("self", "Plugin"))
        val result = AndroidAppCatalog.tvFirst(listOf(tv, phone), exclude = "self")
        assertEquals(listOf("alpha TV", "Both", "Zeta TV", "Aaa", "Beta"), result.map { it.label })
    }

    @Test
    fun `an app with several launcher activities is listed once`() {
        val tv = listOf(app("tv.app", "TV"), app("tv.app", "TV"))
        assertEquals(listOf("tv.app"), AndroidAppCatalog.tvFirst(listOf(tv, emptyList()), exclude = "self").map { it.packageName })
    }

    private fun app(pkg: String, label: String) = InstalledApp(pkg, label)
}
