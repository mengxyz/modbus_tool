package dev.modbustool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun parsesAndOrdersSemanticVersions() {
        assertTrue(SemanticVersion.parse("v1.2.0")!! > SemanticVersion.parse("1.1.9")!!)
        assertTrue(SemanticVersion.parse("1.0.0")!! > SemanticVersion.parse("1.0.0-rc.1")!!)
        assertTrue(SemanticVersion.parse("1.0.0-rc.10")!! > SemanticVersion.parse("1.0.0-rc.2")!!)
        assertEquals(SemanticVersion.parse("1.0.0"), SemanticVersion.parse("v1.0.0+build.7"))
    }

    @Test
    fun reportsNewerRelease() {
        val result = compareRelease("1.0.0", "v1.1.0", "https://example.test/release")
        assertIs<UpdateCheckState.Available>(result)
        assertEquals("v1.1.0", result.version)
    }

    @Test
    fun rejectsNonSemanticReleaseTag() {
        assertIs<UpdateCheckState.Failed>(compareRelease("1.0.0", "latest", "https://example.test/release"))
    }
}
