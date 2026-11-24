package com.lyuan.cc.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipJsonTest {

    @Test
    fun parsesListAndSortsByName() {
        val text = "WARNING: something\n" +
            "[{\"name\":\"Flask\",\"version\":\"3.1.3\"}," +
            "{\"name\":\"click\",\"version\":\"8.5.1\"}]"
        val packages = PipJson.parseList(text)
        assertEquals(listOf("click", "Flask"), packages.map { it.name })
        assertEquals("8.5.1", packages.first().version)
    }

    @Test
    fun parsesOutdatedPackages() {
        val text = "[{\"name\":\"pip\",\"version\":\"26.2\",\"latest_version\":\"26.3\"}," +
            "{\"name\":\"setuptools\",\"version\":\"84.0.0\",\"latest_version\":\"84.2.1\"}]"
        val outdated = PipJson.parseOutdated(text)
        assertEquals(2, outdated.size)
        assertEquals("26.3", outdated.first().latestVersion)
    }

    @Test
    fun malformedOutputIsEmpty() {
        assertTrue(PipJson.parseList("not json").isEmpty())
        assertTrue(PipJson.parseOutdated("").isEmpty())
        assertTrue(PipJson.parseList("[]").isEmpty())
    }

    @Test
    fun reportRedactionHidesCredentials() {
        val text = "Looking in indexes: https://user:pass@example.com/simple " +
            "token=secret123"
        val redacted = PipManager.redact(text)
        assertFalse(redacted.contains("user:pass"))
        assertFalse(redacted.contains("token=secret123"))
        assertTrue(redacted.contains("https://***@example.com/simple"))
    }
}