package com.lyuan.cc.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebVersionTest {

    @Test
    fun equalVersionsCompareEqual() {
        assertEquals(0, DebVersion.compare("1.0.3", "1.0.3"))
        assertEquals(0, DebVersion.compare("1:1.0", "1:1.0"))
        assertEquals(0, DebVersion.compare("1.01", "1.1"))
    }

    @Test
    fun numericSegmentsCompareAsNumbers() {
        assertTrue(DebVersion.isNewer("3.14.18-1", "3.14.9-1"))
        assertFalse(DebVersion.isNewer("3.14.9-1", "3.14.18-1"))
        assertTrue(DebVersion.isNewer("2026.10", "2026.9"))
    }

    @Test
    fun epochHasPriority() {
        assertTrue(DebVersion.isNewer("1:0.1", "9.9"))
        assertFalse(DebVersion.isNewer("9.9", "1:0.1"))
    }

    @Test
    fun revisionIsComparedAfterUpstream() {
        assertTrue(DebVersion.isNewer("1.0-2", "1.0-1"))
        assertTrue(DebVersion.isNewer("1.0-1+b1", "1.0-1"))
        assertFalse(DebVersion.isNewer("1.0-1", "1.0-1+b1"))
    }

    @Test
    fun tildeSortsBeforeEverything() {
        assertTrue(DebVersion.isNewer("1.0", "1.0~beta1"))
        assertFalse(DebVersion.isNewer("1.0~beta1", "1.0"))
        assertTrue(DebVersion.isNewer("1.0~beta2", "1.0~beta1"))
    }

    @Test
    fun lettersSortBeforePunctuation() {
        assertTrue(DebVersion.isNewer("1.0a", "1.0"))
        assertTrue(DebVersion.isNewer("1.0+git", "1.0"))
        // dpkg 里字母排在非字母之前，所以 1.0a < 1.0+git。
        assertFalse(DebVersion.isNewer("1.0a", "1.0+git"))
    }

    @Test
    fun malformedEpochFallsBackToWholeString() {
        assertEquals(0, DebVersion.compare("abc:1.0", "abc:1.0"))
        assertTrue(DebVersion.isNewer("abc:1.1", "abc:1.0"))
    }
}