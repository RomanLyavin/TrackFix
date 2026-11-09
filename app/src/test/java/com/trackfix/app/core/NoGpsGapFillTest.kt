package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Regression for a FIT recording without GPS that contains long gaps between
 * records. The reconstructed route must follow the reference instead of
 * jumping straight across the loop, and the reported distance must match the
 * geometry that is actually written to the output GPX.
 */
class NoGpsGapFillTest {

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assumeTrue("Test fixture $name is not available", stream != null)
        return stream!!.use { it.readBytes() }
    }

    private fun extensionValue(element: Element, tag: String): String? {
        val nodes = element.getElementsByTagNameNS("*", tag)
        return if (nodes.length > 0) nodes.item(0).textContent else null
    }

    @Test
    fun longRecordingGapsAreFilledAlongTheReference() {
        val sourceBytes = resource("no_gps_gap_source.fit")
        val referenceBytes = resource("no_gps_gap_reference.gpx")

        val result = FixEngine.process(
            sourceBytes,
            "i206833920.fit",
            referenceBytes,
            FixSettings(30.0, fillMissingElevation = true)
        )

        val output = GpxCodec.parse(result.outputBytes)
        val outputFile = File("build/test-output/i206833920-fixed.gpx")
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(result.outputBytes)

        val source = FitCodec.toGpx(FitCodec.parse(sourceBytes))
        assertTrue("long gaps should add intermediate points", result.stats.insertedPoints > 0)
        assertEquals(528, result.stats.reconstructedCoordinates)
        assertEquals(
            source.points.size + result.stats.insertedPoints,
            result.stats.totalPoints
        )
        assertEquals(result.stats.totalPoints, output.points.size)
        assertTrue(output.points.all { Geo.isValidCoordinate(it.lat, it.lon) })

        val referenceFile = GpxCodec.parse(referenceBytes, reference = true)
        val reference = ReferencePath(GpxCodec.referencePaths(referenceFile))
        val referenceLength = reference.paths.maxByOrNull { it.totalLength }!!.totalLength
        val distance = Geo.distanceOf(output.trackPoints())
        println(
            "reference=$referenceLength output=$distance " +
                "inserted=${result.stats.insertedPoints}"
        )
        assertTrue(
            "distance $distance differs from $referenceLength",
            abs(distance - referenceLength) < 15.0
        )
        assertEquals(distance, result.stats.distanceAfter, 0.01)

        // The old failure produced a 469 m chord across the loop. With gap
        // filling, every consecutive pair must stay close.
        var maxGap = 0.0
        for (index in 1 until output.points.size) {
            val previous = output.points[index - 1]
            val current = output.points[index]
            maxGap = maxOf(
                maxGap,
                Geo.distanceMeters(
                    previous.lat!!,
                    previous.lon!!,
                    current.lat!!,
                    current.lon!!
                )
            )
        }
        assertTrue("consecutive points jump $maxGap m", maxGap < 50.0)

        assertEquals(Instant.parse("2026-11-07T06:00:50Z"), output.points.first().time)
        assertEquals(Instant.parse("2026-11-07T06:47:24Z"), output.points.last().time)

        // All points, including the inserted ones, lie on the reference route.
        for (point in output.points) {
            val projection = reference.nearest(point.lat!!, point.lon!!, 5.0)
            assertTrue(
                "point ${point.time} is not on the reference route",
                projection != null && projection.distance < 1.0
            )
        }

        // Time never goes backwards.
        for (index in 1 until output.points.size) {
            val previous = output.points[index - 1].time
            val current = output.points[index].time
            assertTrue(previous != null && current != null && !current.isBefore(previous))
        }

        // Original points stay in order and keep their time and sensors.
        var outputIndex = 0
        for (before in source.points) {
            while (outputIndex < output.points.size &&
                output.points[outputIndex].time != before.time
            ) {
                outputIndex++
            }
            assertTrue(
                "source time ${before.time} is missing from the output",
                outputIndex < output.points.size
            )
            val after = output.points[outputIndex]
            assertEquals(before.cadence, after.cadence)
            assertEquals(
                extensionValue(before.element, "hr"),
                extensionValue(after.element, "hr")
            )
            outputIndex++
        }

        // New points may appear only inside long source gaps.
        val sourceTimeSet = source.points.mapNotNull { it.time }.toSet()
        val longGaps = (1 until source.points.size).mapNotNull { index ->
            val start = source.points[index - 1].time
            val end = source.points[index].time
            if (start != null && end != null &&
                Duration.between(start, end).seconds >= 15L
            ) {
                start to end
            } else {
                null
            }
        }
        assertEquals(2, longGaps.size)
        val inserted = output.points.filter {
            val time = it.time
            time != null && time !in sourceTimeSet
        }
        assertEquals(result.stats.insertedPoints, inserted.size)
        for (point in inserted) {
            assertTrue(
                "inserted time ${point.time} is outside the long gaps",
                longGaps.any { (start, end) ->
                    point.time!!.isAfter(start) && point.time!!.isBefore(end)
                }
            )
            assertEquals(null, point.cadence)
            assertEquals(null, extensionValue(point.element, "hr"))
        }

        // The stale cumulative distance from FIT must not survive.
        for (point in output.points) {
            assertEquals(
                0,
                point.element.getElementsByTagNameNS("*", "distance").length
            )
            assertEquals(0, point.element.getElementsByTagName("distance").length)
        }
    }
}
