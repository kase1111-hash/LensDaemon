package com.lensdaemon.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Paths on the SMB share: '/' and '\' both separate folders, empty components are dropped
 * and the result is joined with '\'.
 */
class SmbPathTest {

    @Test
    fun trailingSlashOfThePrefixIsDropped() {
        assertEquals("lensdaemon\\a.mp4", smbPath("lensdaemon/", "a.mp4"))
    }

    @Test
    fun leadingAndTrailingSeparatorsAreDropped() {
        assertEquals("cams\\front\\a.mp4", smbPath("/cams/front/", "a.mp4"))
    }

    @Test
    fun emptyPrefixGivesTheBarePath() {
        assertEquals("a.mp4", smbPath("", "/a.mp4"))
    }

    @Test
    fun doubledSeparatorsAndMixedSlashesAreFolded() {
        assertEquals("x\\y\\d\\e.mp4", smbPath("x\\\\y", "d/e.mp4"))
        assertEquals("share\\dir\\2024\\clip.mp4", smbPath("\\share\\dir\\", "2024\\clip.mp4"))
    }

    @Test
    fun emptyRemotePathIsThePrefixFolder() {
        assertEquals("lensdaemon", smbPath("lensdaemon/", ""))
        assertEquals("", smbPath("", ""))
        assertEquals("", smbPath("/", "\\"))
    }
}
