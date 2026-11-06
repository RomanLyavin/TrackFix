package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityStatsTest {

    private data class Point(
        val time: String?,
        val lat: Double,
        val lon: Double,
        val ele: Double? = null,
        val heartRate: Int? = null,
        val power: Int? = null
    )

    private fun gpx(segments: List<List<Point>>): GpxFile {
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        xml.append("<gpx version=\"1.1\" creator=\"test\"")
        xml.append(" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        xml.append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v2\">")
        xml.append("<trk>")
        for (segment in segments) {
            xml.append("<trkseg>")
            for (point in segment) {
                xml.append("<trkpt lat=\"").append(point.lat).append("\" lon=\"")
                    .append(point.lon).append("\">")
                point.time?.let { xml.append("<time>").append(it).append("</time>") }
                point.ele?.let { xml.append("<ele>").append(it).append("</ele>") }
                if (point.heartRate != null || point.power != null) {
                    xml.append("<extensions>")
                    point.power?.let {
                        xml.append("<power>").append(it).append("</power>")
                    }
                    point.heartRate?.let {
                        xml.append("<gpxtpx:TrackPointExtension><gpxtpx:hr>")
                            .append(it)
                            .append("</gpxtpx:hr></gpxtpx:TrackPointExtension>")
                    }
                    xml.append("</extensions>")
                }
                xml.append("</trkpt>")
            }
            xml.append("</trkseg>")
        }
        xml.append("</trk></gpx>")
        return GpxCodec.parse(xml.toString().toByteArray(Charsets.UTF_8))
    }

    @Test
    fun calculatesMovingStatsWithoutConnectingSegments() {
        val file = gpx(
            listOf(
                listOf(
                    Point("2026-01-01T10:00:00Z", 45.0, 39.0, 100.0, 100, 100),
                    Point("2026-01-01T10:00:10Z", 45.001, 39.0, 110.0, 120, 200),
                    Point("2026-01-01T10:00:20Z", 45.001, 39.0, 110.0, 140, 0),
                    Point("2026-01-01T10:00:30Z", 45.002, 39.0, 105.0, 160, 150)
                ),
                listOf(
                    Point("2026-01-01T10:01:40Z", 45.1, 39.0, 100.0),
                    Point("2026-01-01T10:01:50Z", 45.101, 39.0, 120.0, 180, 100)
                )
            )
        )

        val stats = ActivityStatsCalculator.calculate(file)

        val expectedDistance = Geo.distanceMeters(45.0, 39.0, 45.001, 39.0) * 3
        assertEquals(expectedDistance, stats.distanceMeters, 1e-6)
        assertEquals(30L, stats.movingTimeSeconds)
        assertEquals(90L, stats.averagePaceSecondsPerKm)
        assertEquals(40.03, stats.averageSpeedKmh!!, 0.01)
        assertEquals(140.0, stats.averageHeartRate!!, 1e-9)
        assertEquals(110.0, stats.averagePowerWatts!!, 1e-9)
        assertEquals(30.0, stats.totalAscentMeters!!, 1e-9)
        assertEquals("2026-01-01T10:00:00Z", stats.startTime.toString())
        assertEquals("2026-01-01T10:01:50Z", stats.endTime.toString())
        assertTrue(stats.supports(ShareMetric.MOVING_TIME))
        assertTrue(stats.supports(ShareMetric.TOTAL_ASCENT))
    }

    @Test
    fun longPauseIsNotIncludedInMovingTime() {
        val file = gpx(
            listOf(
                listOf(
                    Point("2026-01-01T10:00:00Z", 45.0, 39.0),
                    Point("2026-01-01T10:00:10Z", 45.001, 39.0),
                    Point("2026-01-01T10:05:00Z", 45.002, 39.0)
                )
            )
        )

        val stats = ActivityStatsCalculator.calculate(file)

        assertEquals(10L, stats.movingTimeSeconds)
        assertTrue(stats.distanceMeters > 200.0)
    }

    @Test
    fun noTimestampsStillAllowsDistanceAndSensors() {
        val file = gpx(
            listOf(
                listOf(
                    Point(null, 45.0, 39.0, 100.0, 100, 100),
                    Point(null, 45.001, 39.0, 101.0, 120, 200)
                )
            )
        )

        val stats = ActivityStatsCalculator.calculate(file)

        assertNull(stats.movingTimeSeconds)
        assertNull(stats.averagePaceSecondsPerKm)
        assertNull(stats.averageSpeedKmh)
        assertTrue(stats.distanceMeters > 100.0)
        assertEquals(110.0, stats.averageHeartRate!!, 1e-9)
        assertEquals(150.0, stats.averagePowerWatts!!, 1e-9)
        assertEquals(0.0, stats.totalAscentMeters!!, 1e-9)
        assertFalse(stats.supports(ShareMetric.MOVING_TIME))
        assertTrue(stats.supports(ShareMetric.DISTANCE))
        assertTrue(stats.supports(ShareMetric.AVERAGE_HEART_RATE))
        assertTrue(stats.supports(ShareMetric.AVERAGE_POWER))
        assertTrue(stats.supports(ShareMetric.TOTAL_ASCENT))
    }

    @Test
    fun missingSensorsAndElevationAreReportedAsUnsupported() {
        val file = gpx(
            listOf(
                listOf(
                    Point("2026-01-01T10:00:00Z", 45.0, 39.0),
                    Point("2026-01-01T10:00:10Z", 45.001, 39.0)
                )
            )
        )

        val stats = ActivityStatsCalculator.calculate(file)

        assertNull(stats.averageHeartRate)
        assertNull(stats.averagePowerWatts)
        assertNull(stats.totalAscentMeters)
        assertFalse(stats.supports(ShareMetric.AVERAGE_HEART_RATE))
        assertFalse(stats.supports(ShareMetric.AVERAGE_POWER))
        assertFalse(stats.supports(ShareMetric.TOTAL_ASCENT))
    }

    @Test
    fun allZeroPowerIsNotAvailable() {
        val file = gpx(
            listOf(
                listOf(
                    Point("2026-01-01T10:00:00Z", 45.0, 39.0, power = 0),
                    Point("2026-01-01T10:00:10Z", 45.001, 39.0, power = 0)
                )
            )
        )

        val stats = ActivityStatsCalculator.calculate(file)

        assertNull(stats.averagePowerWatts)
        assertFalse(stats.supports(ShareMetric.AVERAGE_POWER))
    }

    @Test
    fun elevationNoiseIsIgnored() {
        val file = gpx(
            listOf(
                listOf(
                    Point("2026-01-01T10:00:00Z", 45.0, 39.0, 100.0),
                    Point("2026-01-01T10:00:10Z", 45.001, 39.0, 100.5),
                    Point("2026-01-01T10:00:20Z", 45.002, 39.0, 99.8),
                    Point("2026-01-01T10:00:30Z", 45.003, 39.0, 101.5),
                    Point("2026-01-01T10:00:40Z", 45.004, 39.0, 100.9)
                )
            )
        )

        val stats = ActivityStatsCalculator.calculate(file)

        assertEquals(0.0, stats.totalAscentMeters!!, 1e-9)
    }
}
