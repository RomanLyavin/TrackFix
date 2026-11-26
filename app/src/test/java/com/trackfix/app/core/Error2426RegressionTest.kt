package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Duration
import java.io.File

/**
 * Regression checks for the 24.11.2026 recordings: a stable GPS track that
 * differs from the reference must survive a normal repair, only the lost tail
 * may be reconstructed, and merging must keep the workout type.
 */
class Error2426RegressionTest {

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assumeTrue("Test fixture $name is not available", stream != null)
        return stream!!.use { it.readBytes() }
    }

    private fun reference(): ReferencePath {
        val file = GpxCodec.parse(resource("err2426_reference.gpx"), reference = true)
        return ReferencePath(GpxCodec.referencePaths(file))
    }

    private fun sourceAsGpx(): GpxFile {
        val fit = FitCodec.parse(resource("err2426_source.fit"))
        return FitCodec.toGpx(fit, reference = false, sourceName = "err2426_source.fit")
    }

    private fun repair(followReference: Boolean): ProcessResult = FixEngine.process(
        originalBytes = resource("err2426_source.fit"),
        originalName = "err2426_source.fit",
        referenceBytes = resource("err2426_reference.gpx"),
        settings = FixSettings(30.0, fillMissingElevation = true, followReference = followReference)
    )

    /**
     * From about 09:00 to 28:45 the rider followed a different but perfectly
     * valid route. Those coordinates are kept byte-for-byte. The frozen tail
     * from 28:45 to the finish is placed on the reference route.
     */
    @Test
    fun stableOffRouteSectionIsKeptAndOnlyLostTailIsRepaired() {
        val source = sourceAsGpx()
        val result = repair(followReference = false)
        val output = GpxCodec.parse(result.outputBytes)
        val ref = reference()
        val start = source.points.first().time!!
        var firstChangedSecond = Long.MAX_VALUE
        var unchangedBeforeTail = 0

        for (index in source.points.indices) {
            val expectedLat = source.points[index].lat!!
            val expectedLon = source.points[index].lon!!
            val same = output.points[index].lat == expectedLat &&
                output.points[index].lon == expectedLon
            val seconds = Duration.between(start, output.points[index].time!!).seconds
            if (seconds < 1720) {
                assertTrue("point $index at ${seconds}s was changed", same)
                unchangedBeforeTail++
            } else if (!same && seconds < firstChangedSecond) {
                firstChangedSecond = seconds
            }
        }

        assertTrue("unchanged points before tail: $unchangedBeforeTail", unchangedBeforeTail > 700)
        assertTrue("first changed second $firstChangedSecond", firstChangedSecond in 1720..1740)
        assertTrue("rewrote ${result.stats.fixedCoordinates} points", result.stats.fixedCoordinates < 350)

        var tailPoints = 0
        for (index in output.points.indices) {
            val seconds = Duration.between(start, output.points[index].time!!).seconds
            if (seconds < 1800) continue
            val point = output.points[index]
            val projection = ref.nearest(point.lat!!, point.lon!!, 50.0)
            assertNotNull("tail point $index has no reference projection", projection)
            assertTrue(
                "tail point $index is ${projection!!.distance} m from the reference",
                projection.distance < 1.0
            )
            tailPoints++
        }
        assertTrue("tail points checked: $tailPoints", tailPoints > 200)

        // The FIT sport type survives the repair pipeline.
        assertTrue(
            "sport type was lost",
            String(result.outputBytes, Charsets.UTF_8).contains("<type>Biking</type>")
        )
    }

    /** The checkbox must still replace every coordinate when it is checked. */
    @Test
    fun followReferenceStillReplacesTheWholeTrack() {
        val result = repair(followReference = true)
        val output = GpxCodec.parse(result.outputBytes)
        val ref = reference()
        assertTrue("changed ${result.stats.fixedCoordinates}", result.stats.fixedCoordinates > 1000)
        var maxDeviation = 0.0
        for (point in output.points) {
            val projection = ref.nearest(point.lat!!, point.lon!!, 50.0)
            assertNotNull("point has no reference projection", projection)
            maxDeviation = maxOf(maxDeviation, projection!!.distance)
        }
        assertTrue("max deviation $maxDeviation", maxDeviation < 1.0)
    }

    private fun gpx(type: String?, time: String): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><name>source</name>${type?.let { "<type>$it</type>" } ?: ""}
            <trkseg>
              <trkpt lat="45.00000" lon="39.00000"><time>$time</time></trkpt>
              <trkpt lat="45.00010" lon="39.00010"><time>${time.replace("00Z", "10Z")}</time></trkpt>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent().toByteArray(Charsets.UTF_8)

    @Test
    fun mergedTrackKeepsTheTypeOfTheEarliestSource() {
        // Files are deliberately supplied in reverse chronological order.
        val result = MergeEngine.merge(
            listOf(
                MergeItem("later.gpx", gpx("Running", "2026-11-24T18:00:00Z")),
                MergeItem("earlier.gpx", gpx("Biking", "2026-11-24T17:00:00Z"))
            )
        )
        val xml = String(result.outputBytes, Charsets.UTF_8)
        assertTrue("merged type: $xml", xml.contains("<type>Biking</type>"))
        assertFalse("wrong merged type: $xml", xml.contains("<type>Running</type>"))
    }

    @Test
    fun mergeDoesNotInventATypeWhenSourcesHaveNone() {
        val result = MergeEngine.merge(
            listOf(
                MergeItem("a.gpx", gpx(null, "2026-11-24T17:00:00Z")),
                MergeItem("b.gpx", gpx(null, "2026-11-24T18:00:00Z"))
            )
        )
        assertFalse(
            "unexpected type",
            String(result.outputBytes, Charsets.UTF_8).contains("<type>")
        )
    }

    /** Writes the repaired example so it can be compared with the user's file. */
    @Test
    fun writesNovember24FixedGpx() {
        val result = repair(followReference = false)
        val dir = File("build/test-output")
        dir.mkdirs()
        File(dir, "err2426-fixed-1.7.9.gpx").writeBytes(result.outputBytes)
    }
}
