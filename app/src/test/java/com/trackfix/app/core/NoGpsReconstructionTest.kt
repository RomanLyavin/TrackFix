package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.Instant
import kotlin.math.abs

/**
 * Checks the automatic route reconstruction for recordings that have no GPS
 * coordinates at all. The source keeps its point count, time and sensors; the
 * reference distance is spread over the moving time and pauses hold position.
 */
class NoGpsReconstructionTest {

    private val start = Instant.parse("2026-01-01T10:00:00Z")
    private val latStep = 10.0 / 111_320.0
    private val startLat = 45.0
    private val startLon = 39.0

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assumeTrue("Test fixture $name is not available", stream != null)
        return stream!!.use { it.readBytes() }
    }

    private fun syntheticReference(): ReferencePath {
        val vertices = (0..100).map { index ->
            RefVertex(startLat + index * latStep, startLon, 20.0)
        }
        return ReferencePath(listOf(vertices))
    }

    private fun syntheticTrack(): List<TrackPoint> {
        return (0..10).map { index ->
            val paused = index == 5 || index == 6
            TrackPoint(
                lat = null,
                lon = null,
                ele = if (index == 0) 100.0 else null,
                time = start.plusSeconds(index * 10L),
                speedMetersPerSecond = if (paused) 0.0 else 2.0,
                cadence = if (paused) 0 else 80
            )
        }
    }

    private fun isPause(point: TrackPoint): Boolean {
        val speed = point.speedMetersPerSecond
        if (speed != null && speed.isFinite()) return speed <= 0.05
        val cadence = point.cadence
        return cadence != null && cadence <= 0
    }

    private fun extensionValue(element: Element, tag: String): String? {
        val nodes = element.getElementsByTagNameNS("*", tag)
        return if (nodes.length > 0) nodes.item(0).textContent else null
    }

    @Test
    fun constantSpeedDistributionKeepsPausesInPlace() {
        val reference = syntheticReference()
        val input = syntheticTrack()

        val result = NoGpsReconstructor.reconstruct(
            input,
            reference,
            fillMissingElevation = true
        )

        assertEquals(11, result.stats.totalPoints)
        assertEquals(11, result.stats.reconstructedCoordinates)
        assertEquals(0, result.stats.unresolvedPoints)
        assertTrue(FixWarning.NO_GPS_RECONSTRUCTED in result.warnings)
        assertTrue(result.coordinateChanged.all { it })

        // The route starts at the first reference point and ends at its end.
        assertEquals(startLat, result.lat.first()!!, 1e-9)
        assertEquals(startLon, result.lon.first()!!, 1e-9)
        assertEquals(startLat + 100 * latStep, result.lat.last()!!, 1e-9)
        assertEquals(startLon, result.lon.last()!!, 1e-9)
        assertEquals(reference.totalLength, result.stats.distanceAfter, 1e-6)

        // 10 intervals of 10 s minus the single interval where both ends are
        // paused gives 90 s of moving time for the full reference length.
        assertEquals(reference.totalLength / 90.0, result.stats.speedLimit, 1e-9)

        // The pause keeps one position for both of its points.
        assertEquals(result.lat[5]!!, result.lat[6]!!, 0.0)
        assertEquals(result.lon[5]!!, result.lon[6]!!, 0.0)

        // Coordinates advance only on moving intervals.
        for (index in 1 until input.size) {
            if (isPause(input[index - 1]) && isPause(input[index])) {
                assertEquals(result.lat[index - 1]!!, result.lat[index]!!, 0.0)
                assertEquals(result.lon[index - 1]!!, result.lon[index]!!, 0.0)
            } else {
                assertTrue(result.lat[index]!! > result.lat[index - 1]!!)
            }
        }

        // Existing elevation is preserved, missing elevation comes from the
        // reference only when it is available there.
        assertEquals(100.0, result.ele[0]!!, 0.0)
        assertEquals(20.0, result.ele[1]!!, 0.0)
        assertEquals(10, result.stats.filledElevations)
    }

    @Test
    fun realFitWithoutGpsIsRebuiltFromTheReferenceRoute() {
        val sourceBytes = resource("no_gps_source.fit")
        val referenceBytes = resource("no_gps_reference.gpx")

        val result = FixEngine.process(
            sourceBytes,
            "i206267704.fit",
            referenceBytes,
            FixSettings(30.0, fillMissingElevation = true)
        )

        val output = GpxCodec.parse(result.outputBytes)
        val outputFile = File("build/test-output/i206267704-fixed.gpx")
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(result.outputBytes)

        assertEquals(719, result.stats.totalPoints)
        assertEquals(719, result.stats.reconstructedCoordinates)
        assertEquals(0, result.stats.unresolvedPoints)
        assertTrue(FixWarning.NO_GPS_RECONSTRUCTED in result.warnings)
        assertEquals(719, output.points.size)
        assertTrue(output.points.all { Geo.isValidCoordinate(it.lat, it.lon) })

        val referenceFile = GpxCodec.parse(referenceBytes, reference = true)
        val reference = ReferencePath(GpxCodec.referencePaths(referenceFile))
        val path = reference.paths.maxByOrNull { it.totalLength }!!

        assertEquals(path.vertices.first().lat, output.points.first().lat!!, 1e-9)
        assertEquals(path.vertices.first().lon, output.points.first().lon!!, 1e-9)

        val distance = Geo.distanceOf(output.trackPoints())
        println("reference=${path.totalLength} reconstructed=$distance")
        assertTrue(
            "distance $distance differs from ${path.totalLength}",
            abs(distance - path.totalLength) / path.totalLength < 0.01
        )

        // The stale cumulative distance from FIT must not survive.
        for (point in output.points) {
            assertEquals(
                0,
                point.element.getElementsByTagNameNS("*", "distance").length
            )
            assertEquals(0, point.element.getElementsByTagName("distance").length)
        }

        // Point count, time, heart rate and cadence are untouched.
        val source = FitCodec.toGpx(FitCodec.parse(sourceBytes))
        assertEquals(source.points.size, output.points.size)
        assertEquals(Instant.parse("2026-11-05T16:53:11Z"), output.points.first().time)
        assertEquals(Instant.parse("2026-11-05T17:48:41Z"), output.points.last().time)
        for (index in source.points.indices) {
            val before = source.points[index]
            val after = output.points[index]
            assertEquals(before.time, after.time)
            assertEquals(before.cadence, after.cadence)
            assertEquals(
                extensionValue(before.element, "hr"),
                extensionValue(after.element, "hr")
            )
        }

        // Every interval where both ends are paused holds the same position.
        val sourcePoints = source.trackPoints()
        var heldIntervals = 0
        for (index in 1 until sourcePoints.size) {
            if (isPause(sourcePoints[index - 1]) && isPause(sourcePoints[index])) {
                assertEquals(output.points[index - 1].lat!!, output.points[index].lat!!, 0.0)
                assertEquals(output.points[index - 1].lon!!, output.points[index].lon!!, 0.0)
                heldIntervals++
            }
        }
        assertTrue("the fixture should contain pauses", heldIntervals > 0)
    }
}
