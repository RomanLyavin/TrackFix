package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeEngineTest {

    private data class Point(
        val time: String?,
        val lat: Double,
        val lon: Double,
        val heartRate: Int? = null,
        val extraNamespace: String? = null,
        val extraValue: String? = null,
        val distance: String? = null
    )

    private fun gpx(points: List<Point>): ByteArray = gpxSegments(listOf(points))

    private fun gpxSegments(segments: List<List<Point>>): ByteArray {
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        xml.append("<gpx version=\"1.1\" creator=\"test\"")
        xml.append(" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        xml.append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v2\"")
        xml.append(" xmlns:foo=\"urn:test:foo\">")
        xml.append("<trk>")
        for (points in segments) {
            xml.append("<trkseg>")
            for (point in points) {
                xml.append("<trkpt lat=\"").append(point.lat).append("\" lon=\"")
                    .append(point.lon).append("\">")
                point.time?.let { xml.append("<time>").append(it).append("</time>") }
                if (point.heartRate != null || point.extraValue != null ||
                    point.distance != null
                ) {
                    xml.append("<extensions>")
                    point.distance?.let {
                        xml.append("<distance>").append(it).append("</distance>")
                    }
                    point.heartRate?.let {
                        xml.append("<gpxtpx:TrackPointExtension><gpxtpx:hr>")
                            .append(it)
                            .append("</gpxtpx:hr></gpxtpx:TrackPointExtension>")
                    }
                    if (point.extraValue != null) {
                        xml.append("<foo:data>").append(point.extraValue).append("</foo:data>")
                    }
                    xml.append("</extensions>")
                }
                xml.append("</trkpt>")
            }
            xml.append("</trkseg>")
        }
        xml.append("</trk></gpx>")
        return xml.toString().toByteArray(Charsets.UTF_8)
    }

    private fun item(name: String, points: List<Point>): MergeItem {
        return MergeItem(name, gpx(points))
    }

    private fun outputTimes(bytes: ByteArray): List<String?> {
        val parsed = GpxCodec.parse(bytes)
        return parsed.points.map { it.time?.toString() }
    }

    private fun segmentIndexes(bytes: ByteArray): Set<Int> {
        return GpxCodec.parse(bytes).points.map { it.segmentIndex }.toSet()
    }

    private fun segmentSizes(bytes: ByteArray): List<Int> {
        return GpxCodec.parse(bytes).points.groupingBy { it.segmentIndex }.eachCount()
            .toSortedMap().values.toList()
    }

    @Test
    fun filesAreOrderedByTheirFirstTimestamp() {
        val later = item(
            "later.gpx",
            listOf(
                Point("2026-01-01T10:10:00Z", 45.0, 39.0),
                Point("2026-01-01T10:11:00Z", 45.0001, 39.0)
            )
        )
        val earlier = item(
            "earlier.gpx",
            listOf(
                Point("2026-01-01T10:00:00Z", 45.0, 39.0),
                Point("2026-01-01T10:01:00Z", 45.0001, 39.0)
            )
        )

        val result = MergeEngine.merge(listOf(later, earlier))

        assertEquals(4, result.totalPoints)
        assertEquals(2, result.segmentCount)
        assertEquals(setOf(0, 1), segmentIndexes(result.outputBytes))
        assertEquals("2026-01-01T10:00:00Z", result.startTime.toString())
        assertEquals(
            listOf(
                "2026-01-01T10:00:00Z",
                "2026-01-01T10:01:00Z",
                "2026-01-01T10:10:00Z",
                "2026-01-01T10:11:00Z"
            ),
            outputTimes(result.outputBytes)
        )
    }

    @Test
    fun overlappingPrefixOfTheNextFileIsTrimmed() {
        val first = item(
            "first.gpx",
            (0..5).map { index ->
                Point("2026-01-01T10:0$index:00Z", 45.0 + index * 0.0001, 39.0)
            }
        )
        val second = item(
            "second.gpx",
            (3..8).map { index ->
                Point("2026-01-01T10:0$index:00Z", 45.0 + index * 0.0001, 39.0)
            }
        )

        val result = MergeEngine.merge(listOf(first, second))

        assertEquals(9, result.totalPoints)
        assertEquals(3, result.trimmedPoints)
        assertEquals(2, result.contributedSources)
        assertEquals(2, result.segmentCount)
        assertEquals(listOf(6, 3), segmentSizes(result.outputBytes))
        assertTrue(result.skippedSources.isEmpty())
        assertEquals(
            listOf(
                "2026-01-01T10:00:00Z",
                "2026-01-01T10:01:00Z",
                "2026-01-01T10:02:00Z",
                "2026-01-01T10:03:00Z",
                "2026-01-01T10:04:00Z",
                "2026-01-01T10:05:00Z",
                "2026-01-01T10:06:00Z",
                "2026-01-01T10:07:00Z",
                "2026-01-01T10:08:00Z"
            ),
            outputTimes(result.outputBytes)
        )
    }

    @Test
    fun fullyOverlappedFileIsSkipped() {
        val first = item(
            "first.gpx",
            (0..10).map { index ->
                Point("2026-01-01T10:${index.toString().padStart(2, '0')}:00Z", 45.0, 39.0)
            }
        )
        val contained = item(
            "contained.gpx",
            listOf(
                Point("2026-01-01T10:02:00Z", 45.1, 39.1),
                Point("2026-01-01T10:03:00Z", 45.1, 39.1)
            )
        )

        val result = MergeEngine.merge(listOf(first, contained))

        assertEquals(1, result.contributedSources)
        assertEquals(listOf("contained.gpx"), result.skippedSources)
        assertEquals(2, result.trimmedPoints)
        assertEquals(1, result.segmentCount)
        assertTrue(result.totalPoints > 0)
    }

    @Test
    fun fileWithoutTimeIsRejected() {
        val timed = item("timed.gpx", listOf(Point("2026-01-01T10:00:00Z", 45.0, 39.0)))
        val untimed = MergeItem(
            "untimed.gpx",
            ("<gpx version=\"1.1\" creator=\"test\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\">" +
                "<trk><trkseg><trkpt lat=\"45\" lon=\"39\"/></trkseg></trk></gpx>")
                .toByteArray()
        )

        val error = runCatching { MergeEngine.merge(listOf(timed, untimed)) }.exceptionOrNull()
        assertTrue(error is MergeException)
        assertEquals(MergeException.Reason.NO_TIME, (error as MergeException).reason)
    }

    @Test
    fun fileWithoutCoordinatesIsRejected() {
        val noPosition = MergeItem(
            "no-position.gpx",
            ("<gpx version=\"1.1\" creator=\"test\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\">" +
                "<trk><trkseg><trkpt><time>2026-01-01T10:00:00Z</time></trkpt>" +
                "</trkseg></trk></gpx>").toByteArray()
        )

        val error = runCatching { MergeEngine.inspect(noPosition.bytes) }.exceptionOrNull()
        assertTrue(error is MergeException)
        assertEquals(
            MergeException.Reason.NO_COORDINATES,
            (error as MergeException).reason
        )
    }

    @Test
    fun sensorExtensionsOfAllSourcesArePreserved() {
        val first = item(
            "first.gpx",
            listOf(
                Point("2026-01-01T10:00:00Z", 45.0, 39.0, heartRate = 101),
                Point("2026-01-01T10:01:00Z", 45.0001, 39.0, heartRate = 102)
            )
        )
        val second = item(
            "second.gpx",
            listOf(
                Point("2026-01-01T10:02:00Z", 45.0002, 39.0, heartRate = 103),
                Point("2026-01-01T10:03:00Z", 45.0003, 39.0, heartRate = 104)
            )
        )

        val result = MergeEngine.merge(listOf(first, second))
        val parsed = GpxCodec.parse(result.outputBytes)
        val heartRates = parsed.points.map { point ->
            val nodes = point.element.getElementsByTagNameNS("*", "hr")
            if (nodes.length > 0) nodes.item(0).textContent else null
        }

        assertEquals(listOf("101", "102", "103", "104"), heartRates)
        assertEquals(setOf(0, 1), parsed.points.map { it.segmentIndex }.toSet())
    }

    @Test
    fun staleCumulativeDistanceIsRemovedFromMergedPoints() {
        val first = item(
            "first.gpx",
            listOf(
                Point("2026-01-01T10:00:00Z", 45.0, 39.0, distance = "0"),
                Point("2026-01-01T10:01:00Z", 45.0001, 39.0, distance = "11.12")
            )
        )
        val second = item(
            "second.gpx",
            listOf(
                Point("2026-01-01T11:00:00Z", 45.1, 39.0, distance = "0"),
                Point("2026-01-01T11:01:00Z", 45.1001, 39.0, distance = "11.12")
            )
        )

        val result = MergeEngine.merge(listOf(first, second))
        val parsed = GpxCodec.parse(result.outputBytes)
        val xml = result.outputBytes.toString(Charsets.UTF_8)

        assertFalse(xml.contains("<distance>"))
        assertTrue(parsed.points.all { it.cumulativeDistanceMeters == null })
        assertTrue(result.distanceMeters > 22.0)
    }

    @Test
    fun customExtensionNamespaceFromImportedFileIsKeptValid() {
        val first = item(
            "first.gpx",
            listOf(Point("2026-01-01T10:00:00Z", 45.0, 39.0))
        )
        val second = item(
            "second.gpx",
            listOf(
                Point(
                    "2026-01-01T10:01:00Z",
                    45.0001,
                    39.0,
                    extraValue = "42"
                )
            )
        )

        val result = MergeEngine.merge(listOf(first, second))
        val parsed = GpxCodec.parse(result.outputBytes)
        val nodes = parsed.document.getElementsByTagNameNS("urn:test:foo", "data")

        assertEquals(1, nodes.length)
        assertEquals("42", nodes.item(0).textContent)
    }

    @Test
    fun mergedTrackUsesSeparateSegmentsForEachSource() {
        val first = item(
            "morning.gpx",
            listOf(
                Point("2026-01-01T10:00:00Z", 45.0, 39.0),
                Point("2026-01-01T10:01:00Z", 45.0001, 39.0)
            )
        )
        val second = item(
            "evening.gpx",
            listOf(
                Point("2026-01-01T18:00:00Z", 45.0002, 39.0),
                Point("2026-01-01T18:01:00Z", 45.0003, 39.0)
            )
        )

        val result = MergeEngine.merge(listOf(first, second))
        val parsed = GpxCodec.parse(result.outputBytes)

        assertEquals(4, parsed.points.size)
        assertEquals(setOf(0, 1), parsed.points.map { it.segmentIndex }.toSet())
        assertEquals(2, result.segmentCount)
        assertEquals(listOf(2, 2), segmentSizes(result.outputBytes))
        assertEquals("morning_merged.gpx", result.suggestedName)
        assertNotNull(result.startTime)
        assertNotNull(result.endTime)
    }

    @Test
    fun segmentsInsideOneSourceArePreserved() {
        val paused = MergeItem(
            "paused.gpx",
            gpxSegments(
                listOf(
                    listOf(
                        Point("2026-01-01T10:00:00Z", 45.0, 39.0),
                        Point("2026-01-01T10:01:00Z", 45.0001, 39.0)
                    ),
                    listOf(
                        Point("2026-01-01T10:10:00Z", 45.001, 39.0),
                        Point("2026-01-01T10:11:00Z", 45.0011, 39.0)
                    )
                )
            )
        )
        val later = item(
            "later.gpx",
            listOf(
                Point("2026-01-01T10:20:00Z", 45.002, 39.0),
                Point("2026-01-01T10:21:00Z", 45.0021, 39.0)
            )
        )

        val result = MergeEngine.merge(listOf(later, paused))

        assertEquals(6, result.totalPoints)
        assertEquals(3, result.segmentCount)
        assertEquals(listOf(2, 2, 2), segmentSizes(result.outputBytes))
        assertEquals(
            listOf(
                "2026-01-01T10:00:00Z",
                "2026-01-01T10:01:00Z",
                "2026-01-01T10:10:00Z",
                "2026-01-01T10:11:00Z",
                "2026-01-01T10:20:00Z",
                "2026-01-01T10:21:00Z"
            ),
            outputTimes(result.outputBytes)
        )
    }

    @Test
    fun fullyTrimmedSegmentsDoNotAppearInTheResult() {
        val first = item(
            "first.gpx",
            (0..5).map { index ->
                Point(
                    "2026-01-01T10:${index.toString().padStart(2, '0')}:00Z",
                    45.0 + index * 0.0001,
                    39.0
                )
            }
        )
        val second = MergeItem(
            "second.gpx",
            gpxSegments(
                listOf(
                    listOf(
                        Point("2026-01-01T10:02:00Z", 45.1, 39.0),
                        Point("2026-01-01T10:03:00Z", 45.11, 39.0)
                    ),
                    listOf(
                        Point("2026-01-01T10:06:00Z", 45.12, 39.0),
                        Point("2026-01-01T10:07:00Z", 45.13, 39.0)
                    )
                )
            )
        )

        val result = MergeEngine.merge(listOf(first, second))

        assertEquals(8, result.totalPoints)
        assertEquals(2, result.trimmedPoints)
        assertEquals(2, result.segmentCount)
        assertEquals(listOf(6, 2), segmentSizes(result.outputBytes))
    }

    @Test
    fun distanceDoesNotIncludeGapsBetweenSegments() {
        val first = item(
            "first.gpx",
            listOf(Point("2026-01-01T10:00:00Z", 45.0, 39.0))
        )
        val second = item(
            "second.gpx",
            listOf(Point("2026-01-01T11:00:00Z", 46.0, 40.0))
        )

        val result = MergeEngine.merge(listOf(first, second))

        assertEquals(2, result.segmentCount)
        assertEquals(0.0, result.distanceMeters, 1e-9)
    }

    @Test
    fun oneFileIsNotEnoughForMerge() {
        val only = item(
            "only.gpx",
            listOf(Point("2026-01-01T10:00:00Z", 45.0, 39.0))
        )

        val error = runCatching { MergeEngine.merge(listOf(only)) }.exceptionOrNull()
        assertTrue(error is MergeException)
        assertEquals(
            MergeException.Reason.TOO_FEW_FILES,
            (error as MergeException).reason
        )
    }
}
