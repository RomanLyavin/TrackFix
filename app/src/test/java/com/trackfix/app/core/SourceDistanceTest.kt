package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SourceDistanceTest {

    private val start = Instant.parse("2026-01-01T10:00:00Z")

    private fun point(
        index: Int,
        speed: Double? = null,
        distance: Double? = null
    ): TrackPoint = TrackPoint(
        lat = null,
        lon = null,
        ele = null,
        time = start.plusSeconds(index * 10L),
        speedMetersPerSecond = speed,
        cumulativeDistanceMeters = distance
    )

    @Test
    fun cumulativeDistanceWinsOverSpeed() {
        val points = listOf(
            point(0, speed = 1.0, distance = 0.0),
            point(1, speed = 1.0, distance = 10.0),
            point(2, speed = 1.0, distance = 30.0),
            point(3, speed = 1.0, distance = 60.0)
        )

        val intervals = SourceDistance.intervalMeters(points, fallbackSpeed = 1.0)

        assertEquals(10.0, intervals[1], 1e-9)
        assertEquals(20.0, intervals[2], 1e-9)
        assertEquals(30.0, intervals[3], 1e-9)
    }

    @Test
    fun speedIsUsedWhenDistanceIsMissing() {
        val points = listOf(
            point(0, speed = 2.0),
            point(1, speed = 2.0),
            point(2, speed = 2.0)
        )

        val intervals = SourceDistance.intervalMeters(points, fallbackSpeed = 1.0)

        assertEquals(20.0, intervals[1], 1e-9)
        assertEquals(20.0, intervals[2], 1e-9)
    }

    @Test
    fun missingSpeedIsFilledFromTheNearestInterval() {
        val points = listOf(
            point(0, speed = 2.0),
            point(1),
            point(2),
            point(3, speed = 4.0),
            point(4)
        )

        val intervals = SourceDistance.intervalMeters(points, fallbackSpeed = 1.0)

        assertEquals(20.0, intervals[1], 1e-9)
        assertTrue("the missing interval must not become zero", intervals[2] > 0.0)
        assertEquals(40.0, intervals[3], 1e-9)
        assertEquals(40.0, intervals[4], 1e-9)
    }

    @Test
    fun timeIsTheLastResort() {
        val points = listOf(
            point(0),
            point(1),
            point(2)
        )

        val intervals = SourceDistance.intervalMeters(points, fallbackSpeed = 3.0)

        assertEquals(30.0, intervals[1], 1e-9)
        assertEquals(30.0, intervals[2], 1e-9)
    }
}
