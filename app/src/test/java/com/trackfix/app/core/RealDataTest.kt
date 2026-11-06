package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end checks on the real GPX files the app was built for.
 */
class RealDataTest {

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assumeTrue("Test fixture $name is not available", stream != null)
        return stream!!.use { it.readBytes() }
    }

    private fun reference(): ReferencePath {
        val file = GpxCodec.parse(resource("correct_track.gpx"), reference = true)
        return ReferencePath(GpxCodec.referencePaths(file))
    }

    @Test
    fun cleanRealTrackIsLeftUntouched() {
        val file = GpxCodec.parse(resource("orig_file.gpx"))
        val result = TrackFixer(reference(), FixSettings(30.0, fillMissingElevation = true))
            .fix(file.trackPoints())

        assertEquals(790, result.stats.totalPoints)
        assertEquals(0, result.stats.fixedCoordinates)
        assertEquals(0, result.stats.problemBlocks)
        for (index in file.points.indices) {
            val point = file.points[index]
            assertEquals(point.lat!!, result.lat[index]!!, 0.0)
            assertEquals(point.lon!!, result.lon[index]!!, 0.0)
            assertEquals(point.ele, result.ele[index])
        }
    }

    @Test
    fun injectedGlitchesAreRepairedAndEverythingElseIsPreserved() {
        val file = GpxCodec.parse(resource("orig_file.gpx"))
        val reference = reference()
        val input = file.trackPoints().toMutableList()
        val broken = (500..509).toSet()
        for (index in broken) {
            input[index] = if (index % 2 == 0) {
                input[index].copy(lat = null, lon = null)
            } else {
                input[index].copy(lon = input[index].lon!! + 0.01)
            }
        }
        val timesBefore = file.points.map { it.element.getElementsByTagNameNS("*", "time").item(0)?.textContent }
        val heartRatesBefore = valuesOf(file, "hr")
        val cadencesBefore = valuesOf(file, "cad")
        val elevationsBefore = file.points.map { it.ele }

        val result = TrackFixer(reference, FixSettings(30.0, fillMissingElevation = true)).fix(input)

        assertEquals(790, result.lat.size)
        assertEquals(10, result.stats.fixedCoordinates)
        assertEquals(5, result.stats.missingCoordinates)
        for (index in broken) {
            assertNotNull("coordinate $index was not filled", result.lat[index])
            val projection = reference.nearest(result.lat[index]!!, result.lon[index]!!, 5.0)
            assertNotNull("coordinate $index is off the reference", projection)
            assertTrue("coordinate $index is too far", projection!!.distance < 1.0)
        }

        GpxCodec.applyFixes(file, result)
        val reparsed = GpxCodec.parse(GpxCodec.serialize(file))

        assertEquals(790, reparsed.points.size)
        assertEquals(timesBefore, reparsed.points.map { it.element.getElementsByTagNameNS("*", "time").item(0)?.textContent })
        assertEquals(heartRatesBefore, valuesOf(reparsed, "hr"))
        assertEquals(cadencesBefore, valuesOf(reparsed, "cad"))
        assertEquals(elevationsBefore, reparsed.points.map { it.ele })

        for (index in reparsed.points.indices) {
            val point = reparsed.points[index]
            if (index !in broken) {
                assertEquals(file.points[index].lat!!, point.lat!!, 0.0)
                assertEquals(file.points[index].lon!!, point.lon!!, 0.0)
            }
        }
        // The end of the track must not be extended or moved.
        assertEquals(file.points.last().lat!!, reparsed.points.last().lat!!, 0.0)
        assertEquals(file.points.last().lon!!, reparsed.points.last().lon!!, 0.0)
    }

    private fun valuesOf(file: GpxFile, tag: String): List<String?> =
        file.points.map { point ->
            val nodes = point.element.getElementsByTagNameNS("*", tag)
            if (nodes.length > 0) nodes.item(0).textContent else null
        }
}
