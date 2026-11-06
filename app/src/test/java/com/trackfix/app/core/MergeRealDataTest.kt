package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Smoke checks that the merge pipeline works on the real GPX and FIT files of
 * the project, including the FIT -> GPX conversion path.
 */
class MergeRealDataTest {

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assumeTrue("Test fixture $name is not available", stream != null)
        return stream!!.use { it.readBytes() }
    }

    private fun mergedTimes(bytes: ByteArray): List<java.time.Instant> =
        GpxCodec.parse(bytes).points.mapNotNull { it.time }

    private fun segmentCount(file: GpxFile): Int =
        file.points.map { it.segmentIndex }.distinct().size

    @Test
    fun realGpxFilesWithDifferentDatesAreMerged() {
        val original = resource("orig_file.gpx")
        val broken = resource("supercycle_broken.gpx")
        val originalFile = GpxCodec.parse(original)
        val brokenFile = GpxCodec.parse(broken)
        val originalPoints = originalFile.points.size
        val brokenPoints = brokenFile.points.size
        val expectedSegments = segmentCount(originalFile) + segmentCount(brokenFile)

        val result = MergeEngine.merge(
            listOf(
                MergeItem("orig_file.gpx", original),
                MergeItem("supercycle_broken.gpx", broken)
            )
        )

        assertEquals(originalPoints + brokenPoints, result.totalPoints)
        assertEquals(2, result.contributedSources)
        assertEquals(0, result.trimmedPoints)
        assertEquals(expectedSegments, result.segmentCount)
        assertTrue(result.skippedSources.isEmpty())

        val parsed = GpxCodec.parse(result.outputBytes)
        assertEquals(originalPoints + brokenPoints, parsed.points.size)
        assertEquals(expectedSegments, segmentCount(parsed))
        val times = mergedTimes(result.outputBytes)
        assertEquals(times.sorted(), times)
    }

    @Test
    fun realFitFileCanBeTheBaseDocument() {
        val fit = resource("supercycle_broken.fit")
        val fitFile = FixEngine.parseTrackFile(fit)
        val expectedSegments = segmentCount(fitFile) + 1
        val late = (
            "<gpx version=\"1.1\" creator=\"test\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\">" +
                "<trk><trkseg>" +
                "<trkpt lat=\"45.1\" lon=\"39.1\">" +
                "<time>2027-01-01T10:00:00Z</time></trkpt>" +
                "<trkpt lat=\"45.1001\" lon=\"39.1\">" +
                "<time>2027-01-01T10:00:01Z</time></trkpt>" +
                "</trkseg></trk></gpx>"
            ).toByteArray()

        val result = MergeEngine.merge(
            listOf(
                MergeItem("late.gpx", late),
                MergeItem("SuperCycle-ride-20261103-1746.fit", fit)
            )
        )

        assertEquals(fitFile.points.size + 2, result.totalPoints)
        assertEquals(2, result.contributedSources)
        assertEquals(expectedSegments, result.segmentCount)
        assertEquals("2026-11-03T14:46:55Z", result.startTime.toString())
        assertEquals("2027-01-01T10:00:01Z", result.endTime.toString())
        val parsed = GpxCodec.parse(result.outputBytes)
        assertEquals(fitFile.points.size + 2, parsed.points.size)
        assertEquals(expectedSegments, segmentCount(parsed))
    }

    @Test
    fun realFitFileIsMergedAsGpx() {
        val original = resource("orig_file.gpx")
        val fit = resource("supercycle_broken.fit")
        val originalFile = GpxCodec.parse(original)
        val originalPoints = originalFile.points.size
        val fitFile = FixEngine.parseTrackFile(fit)
        val fitPoints = fitFile.points.size
        val expectedSegments = segmentCount(originalFile) + segmentCount(fitFile)

        val result = MergeEngine.merge(
            listOf(
                MergeItem("SuperCycle-ride-20261103-1746.fit", fit),
                MergeItem("orig_file.gpx", original)
            )
        )

        assertEquals(originalPoints + fitPoints, result.totalPoints)
        assertEquals(2, result.contributedSources)
        assertEquals(0, result.trimmedPoints)
        assertEquals(expectedSegments, result.segmentCount)

        val parsed = GpxCodec.parse(result.outputBytes)
        assertEquals(originalPoints + fitPoints, parsed.points.size)
        assertEquals(expectedSegments, segmentCount(parsed))
        val times = mergedTimes(result.outputBytes)
        assertEquals(times.sorted(), times)
        assertTrue(times.size > fitPoints)
        val last = parsed.points.last()
        assertTrue(last.speedMetersPerSecond != null)
    }
}
