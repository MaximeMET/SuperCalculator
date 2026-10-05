package io.github.maximemet.supercalc.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 更新清单的解析与版本比较。 */
class UpdateManifestTest {

    @Test
    fun parseFullManifest() {
        val manifest = UpdateManifests.parse(
            """
            {
              "app": {"versionCode": 3, "versionName": "0.1.2", "url": "https://example.com/dl", "notes": "修了些东西"},
              "rules": {"version": 5, "json": "https://example.com/r.json", "sig": "https://example.com/r.sig"}
            }
            """.trimIndent()
        )
        assertNotNull(manifest)
        assertEquals(3, manifest.app?.versionCode)
        assertEquals("0.1.2", manifest.app?.versionName)
        assertEquals("修了些东西", manifest.app?.notes)
        assertEquals(5, manifest.rules?.version)
        assertTrue(UpdateManifests.appUpdateAvailable(2, manifest.app))
        assertFalse(UpdateManifests.appUpdateAvailable(3, manifest.app))
        assertTrue(UpdateManifests.rulesUpdateAvailable(4, manifest.rules))
        assertFalse(UpdateManifests.rulesUpdateAvailable(5, manifest.rules))
    }

    @Test
    fun partialManifestIsFine() {
        val manifest = UpdateManifests.parse(
            """{"rules": {"version": 2, "json": "j", "sig": "s"}}"""
        )
        assertNotNull(manifest)
        assertNull(manifest.app)
        assertNotNull(manifest.rules)
        assertFalse(UpdateManifests.appUpdateAvailable(99, manifest.app))
    }

    @Test
    fun brokenManifestReturnsNull() {
        assertNull(UpdateManifests.parse("nope"))
        assertNull(UpdateManifests.parse("""{"app": {"versionCode": 1}}"""), "缺 url 的应用段不算数")
        assertNull(UpdateManifests.parse("""{"rules": {"version": 2}}"""), "缺下载地址的规则段不算数")
        assertNull(UpdateManifests.parse("""{}"""), "两者都没有就没有可用信息")
    }
}
