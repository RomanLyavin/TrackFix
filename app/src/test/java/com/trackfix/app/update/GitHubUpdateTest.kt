package com.trackfix.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateTest {

    @Test
    fun parsesLatestReleaseAndSelectsApk() {
        val json = """
            {
              "tag_name": "v1.8.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "release-notes.txt",
                  "browser_download_url": "https://example.com/notes.txt",
                  "size": 12
                },
                {
                  "name": "TrackFix-1.8.0.apk",
                  "url": "https://api.github.com/repos/o/r/releases/assets/2",
                  "browser_download_url": "https://example.com/TrackFix-1.8.0.apk",
                  "size": 123456
                }
              ]
            }
        """.trimIndent()

        val release = GitHubUpdate.parseLatestRelease(json)

        assertNotNull(release)
        assertEquals("v1.8.0", release!!.tag)
        assertEquals("1.8.0", release.version)
        assertEquals("https://api.github.com/repos/o/r/releases/assets/2", release.apkUrl)
        assertEquals("TrackFix-1.8.0.apk", release.apkName)
        assertEquals(123456L, release.apkSize)
    }

    @Test
    fun prefersApkMatchingTheReleaseVersion() {
        val json = """
            {
              "tag_name": "v1.8.0",
              "assets": [
                {
                  "name": "TrackFix-1.7.1.apk",
                  "url": "https://api.github.com/repos/o/r/releases/assets/1",
                  "browser_download_url": "https://example.com/old.apk",
                  "size": 1
                },
                {
                  "name": "TrackFix-1.8.0.apk",
                  "url": "https://api.github.com/repos/o/r/releases/assets/2",
                  "browser_download_url": "https://example.com/new.apk",
                  "size": 2
                }
              ]
            }
        """.trimIndent()

        val release = GitHubUpdate.parseLatestRelease(json)!!

        assertEquals("https://api.github.com/repos/o/r/releases/assets/2", release.apkUrl)
    }

    @Test
    fun fallsBackToBrowserUrlWithoutApiUrl() {
        val json = """
            {
              "tag_name": "v1.8.0",
              "assets": [
                {
                  "name": "TrackFix-1.8.0.apk",
                  "browser_download_url": "https://example.com/TrackFix-1.8.0.apk",
                  "size": 2
                }
              ]
            }
        """.trimIndent()

        val release = GitHubUpdate.parseLatestRelease(json)!!

        assertEquals("https://example.com/TrackFix-1.8.0.apk", release.apkUrl)
    }

    @Test
    fun releaseWithoutApkIsReportedAsSuch() {
        val json = """
            {
              "tag_name": "v1.8.0",
              "assets": [
                {
                  "name": "release-notes.txt",
                  "browser_download_url": "https://example.com/notes.txt",
                  "size": 12
                }
              ]
            }
        """.trimIndent()

        val release = GitHubUpdate.parseLatestRelease(json)

        assertNotNull(release)
        assertNull(release!!.apkUrl)
        assertEquals(0L, release.apkSize)
    }

    @Test
    fun draftAndPrereleaseAreIgnored() {
        assertNull(
            GitHubUpdate.parseLatestRelease(
                """{"tag_name":"v1.8.0","draft":true,"assets":[]}"""
            )
        )
        assertNull(
            GitHubUpdate.parseLatestRelease(
                """{"tag_name":"v1.8.0","prerelease":true,"assets":[]}"""
            )
        )
    }

    @Test
    fun comparesNumericVersionParts() {
        assertTrue(GitHubUpdate.isNewer("1.8.0", "1.7.1"))
        assertTrue(GitHubUpdate.isNewer("v1.10.0", "1.9.9"))
        assertFalse(GitHubUpdate.isNewer("1.7.1", "1.7.1"))
        assertFalse(GitHubUpdate.isNewer("1.7", "1.7.0"))
        assertFalse(GitHubUpdate.isNewer("invalid", "1.7.0"))
        assertEquals(0, GitHubUpdate.compareVersions("1.7.0", "1.7"))
        assertTrue(GitHubUpdate.compareVersions("1.7.2", "1.7.10") < 0)
    }

    @Test
    fun normalizesReleaseTags() {
        assertEquals("1.7.1", GitHubUpdate.normalizeTag("v1.7.1"))
        assertEquals("1.7.1", GitHubUpdate.normalizeTag("V1.7.1"))
        assertEquals("1.7.1", GitHubUpdate.normalizeTag("1.7.1-beta"))
        assertEquals("1.7.1", GitHubUpdate.normalizeTag("1.7.1+build"))
    }
}
