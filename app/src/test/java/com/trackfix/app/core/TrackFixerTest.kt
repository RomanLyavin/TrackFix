package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class TrackFixerTest {

    private val start = Instant.parse("2026-01-01T10:00:00Z")
    private val latStep = 10.0 / 111_320.0
    private val startLat = 45.0
    private val startLon = 39.0

    private fun reference(): ReferencePath {
        val vertices = (0..100).map { index ->
            RefVertex(startLat + index * latStep, startLon, 20.0 + index * 0.1)
        }
        return ReferencePath(listOf(vertices))
    }

    private fun track(pointCount: Int = 101): MutableList<TrackPoint> {
        return (0 until pointCount).map { index ->
            TrackPoint(
                lat = startLat + index * latStep,
                lon = startLon,
                ele = 20.0 + index * 0.1,
                time = start.plusSeconds(index * 5L)
            )
        }.toMutableList()
    }

    private fun loopPoint(index: Int): Pair<Double, Double> {
        val step = index % 40
        val side = step / 10
        val offset = step % 10
        val eastStep = 10.0 / (111_320.0 * Math.cos(Math.toRadians(startLat)))
        return when (side) {
            0 -> startLat + offset * latStep to startLon
            1 -> startLat + 10 * latStep to startLon + offset * eastStep
            2 -> startLat + (10 - offset) * latStep to startLon + 10 * eastStep
            else -> startLat to startLon + (10 - offset) * eastStep
        }
    }

    private fun loopReference(): ReferencePath {
        val vertices = (0..40).map { index ->
            val (lat, lon) = loopPoint(index)
            RefVertex(lat, lon, 20.0)
        }
        return ReferencePath(listOf(vertices))
    }

    private fun loopTrack(): MutableList<TrackPoint> {
        return (0..80).map { index ->
            val (lat, lon) = loopPoint(index)
            TrackPoint(lat, lon, 20.0, start.plusSeconds(index * 5L))
        }.toMutableList()
    }

    @Test
    fun closedLoopRunCrossingLapBoundaryKeepsTheCorrectLap() {
        val input = loopTrack()
        for (index in 20..70) {
            input[index] = input[index].copy(lon = input[index].lon!! + 0.01)
        }
        val result = TrackFixer(loopReference(), FixSettings(30.0)).fix(input)

        assertEquals(51, result.stats.fixedCoordinates)
        for (index in 20..70) {
            val (lat, lon) = loopPoint(index)
            assertEquals(lat, result.lat[index]!!, 1e-6)
            assertEquals(lon, result.lon[index]!!, 1e-6)
        }
    }

    @Test
    fun frozenCoordinatesOnTheRouteAreSpreadBySensorDistance() {
        val input = track()
        val frozenLat = startLat + 25 * latStep
        for (index in 20..29) {
            input[index] = input[index].copy(
                lat = frozenLat,
                lon = startLon,
                speedMetersPerSecond = 2.0,
                cumulativeDistanceMeters = index * 10.0
            )
        }
        for (index in input.indices) {
            if (index !in 20..29) {
                input[index] = input[index].copy(
                    speedMetersPerSecond = 2.0,
                    cumulativeDistanceMeters = index * 10.0
                )
            }
        }

        val result = TrackFixer(reference(), FixSettings(30.0)).fix(input)

        assertEquals(1, result.stats.frozenRuns)
        assertEquals(10, result.stats.frozenCoordinates)
        for (index in 20..29) {
            assertEquals(
                "frozen point $index was not distributed by distance",
                startLat + index * latStep,
                result.lat[index]!!,
                0.00002
            )
            assertEquals(startLon, result.lon[index]!!, 0.00002)
        }
        // Anchors stay exactly where they were.
        assertEquals(input[19].lat!!, result.lat[19]!!, 1e-10)
        assertEquals(input[30].lat!!, result.lat[30]!!, 1e-10)
    }

    @Test
    fun frozenCoordinatesUseSpeedWhenDistanceIsAbsent() {
        val input = track()
        val frozenLat = startLat + 25 * latStep
        for (index in input.indices) {
            input[index] = input[index].copy(
                speedMetersPerSecond = 2.0,
                cumulativeDistanceMeters = null
            )
        }
        for (index in 20..29) {
            input[index] = input[index].copy(
                lat = frozenLat,
                lon = startLon,
                speedMetersPerSecond = 2.0
            )
        }

        val result = TrackFixer(reference(), FixSettings(30.0)).fix(input)

        assertEquals(1, result.stats.frozenRuns)
        assertEquals(10, result.stats.frozenCoordinates)
        for (index in 20..29) {
            assertEquals(
                "frozen point $index was not distributed by speed",
                startLat + index * latStep,
                result.lat[index]!!,
                0.00002
            )
            assertEquals(startLon, result.lon[index]!!, 0.00002)
        }
        assertEquals(input[19].lat!!, result.lat[19]!!, 1e-10)
        assertEquals(input[30].lat!!, result.lat[30]!!, 1e-10)
    }

    @Test
    fun cleanTrackIsNotChanged() {
        val input = track()
        val result = TrackFixer(reference(), FixSettings(30.0)).fix(input)
        assertEquals(0, result.stats.fixedCoordinates)
        assertEquals(0, result.stats.problemBlocks)
        input.forEachIndexed { index, point ->
            assertEquals(point.lat!!, result.lat[index]!!, 1e-10)
            assertEquals(point.lon!!, result.lon[index]!!, 1e-10)
        }
    }

    @Test
    fun teleportedRunIsReplacedWithReferenceLane() {
        val input = track()
        for (index in 20..29) {
            input[index] = input[index].copy(lon = startLon + 0.01)
        }
        val result = TrackFixer(reference(), FixSettings(30.0)).fix(input)

        assertTrue(result.stats.fixedCoordinates >= 10)
        for (index in 20..29) {
            assertNotNull(result.lat[index])
            val expected = startLat + index * latStep
            assertEquals(expected, result.lat[index]!!, 0.00002)
            assertEquals(startLon, result.lon[index]!!, 0.00002)
            assertEquals(input[index].time, input[index].time)
        }
        // The anchors on both sides stay untouched.
        assertEquals(input[19].lat!!, result.lat[19]!!, 1e-10)
        assertEquals(input[30].lat!!, result.lat[30]!!, 1e-10)
    }

    @Test
    fun missingCoordinatesAreFilledWithoutAddingPoints() {
        val input = track()
        for (index in 40..42) {
            input[index] = input[index].copy(lat = null, lon = null)
        }
        val result = TrackFixer(reference(), FixSettings(30.0)).fix(input)

        assertEquals(101, result.lat.size)
        assertEquals(3, result.stats.missingCoordinates)
        for (index in 40..42) {
            assertNotNull(result.lat[index])
            assertEquals(startLat + index * latStep, result.lat[index]!!, 0.00002)
            assertEquals(startLon, result.lon[index]!!, 0.00002)
        }
    }

    @Test
    fun trackContinuingPastReferenceEndIsNotDraggedBack() {
        val input = track(106)
        val result = TrackFixer(reference(), FixSettings(30.0)).fix(input)

        for (index in 101..105) {
            assertEquals(input[index].lat!!, result.lat[index]!!, 1e-10)
            assertEquals(input[index].lon!!, result.lon[index]!!, 1e-10)
        }
    }

    @Test
    fun missingPointsBeyondOpenReferenceStayUnresolved() {
        val shift = 30.0 / 111_320.0
        val input = track(106)
            .map { it.copy(lat = it.lat!! - shift) }
            .toMutableList()
        for (index in 0..3) {
            input[index] = input[index].copy(lat = null, lon = null)
        }
        val result = TrackFixer(reference(), FixSettings(30.0)).fix(input)

        for (index in 0..2) {
            assertEquals(null, result.lat[index])
        }
        assertNotNull(result.lat[3])
        assertEquals(3, result.stats.unresolvedPoints)
    }

    @Test
    fun leadingMissingCoordinatesAreFilledFromTheFirstFix() {
        val input = track()
        for (index in 0..29) {
            input[index] = input[index].copy(
                lat = null,
                lon = null,
                speedMetersPerSecond = 2.0,
                cumulativeDistanceMeters = index * 10.0
            )
        }
        for (index in 30..100) {
            input[index] = input[index].copy(
                speedMetersPerSecond = 2.0,
                cumulativeDistanceMeters = index * 10.0
            )
        }

        val result = TrackFixer(reference(), FixSettings(30.0)).fix(input)

        assertEquals(0, result.stats.unresolvedPoints)
        assertEquals(30, result.stats.leadingFixed)
        assertTrue(result.stats.missingCoordinates >= 30)
        for (index in 0..29) {
            assertNotNull("leading point $index was not repaired", result.lat[index])
            assertEquals(
                "leading point $index has wrong latitude",
                startLat + index * latStep,
                result.lat[index]!!,
                0.00002
            )
            assertEquals(startLon, result.lon[index]!!, 0.00002)
        }
        assertEquals(input[30].lat!!, result.lat[30]!!, 1e-10)
    }

    @Test
    fun trailingMissingCoordinatesAreFilledFromTheLastFix() {
        val input = track()
        for (index in 0..70) {
            input[index] = input[index].copy(
                speedMetersPerSecond = 2.0,
                cumulativeDistanceMeters = index * 10.0
            )
        }
        for (index in 71..100) {
            input[index] = input[index].copy(
                lat = null,
                lon = null,
                speedMetersPerSecond = 2.0,
                cumulativeDistanceMeters = index * 10.0
            )
        }

        val result = TrackFixer(reference(), FixSettings(30.0)).fix(input)

        assertEquals(0, result.stats.unresolvedPoints)
        assertEquals(30, result.stats.trailingFixed)
        for (index in 71..100) {
            assertNotNull("trailing point $index was not repaired", result.lat[index])
            assertEquals(
                "trailing point $index has wrong latitude",
                startLat + index * latStep,
                result.lat[index]!!,
                0.00002
            )
            assertEquals(startLon, result.lon[index]!!, 0.00002)
        }
        assertEquals(input[70].lat!!, result.lat[70]!!, 1e-10)
    }

    @Test
    fun leadingMissingCoordinatesCanUseTimeWhenDistanceIsAbsent() {
        val input = track()
        for (index in 0..29) {
            input[index] = input[index].copy(lat = null, lon = null)
        }
        for (index in input.indices) {
            input[index] = input[index].copy(
                speedMetersPerSecond = null,
                cumulativeDistanceMeters = null
            )
        }

        val result = TrackFixer(reference(), FixSettings(30.0)).fix(input)

        assertEquals(0, result.stats.unresolvedPoints)
        assertEquals(30, result.stats.leadingFixed)
        for (index in 0..29) {
            assertNotNull("leading point $index was not repaired", result.lat[index])
            assertEquals(startLat + index * latStep, result.lat[index]!!, 0.00002)
            assertEquals(startLon, result.lon[index]!!, 0.00002)
        }
    }

    @Test
    fun boundaryGapUsesAnOffRouteFirstFixAsFallbackAnchor() {
        val input = track()
        val shift = 100.0 / (111_320.0 * Math.cos(Math.toRadians(startLat)))
        for (index in 0..29) {
            input[index] = input[index].copy(
                lat = null,
                lon = null,
                speedMetersPerSecond = 2.0,
                cumulativeDistanceMeters = index * 10.0
            )
        }
        for (index in 30..100) {
            input[index] = input[index].copy(
                lon = input[index].lon!! + shift,
                speedMetersPerSecond = 2.0,
                cumulativeDistanceMeters = index * 10.0
            )
        }

        val result = TrackFixer(reference(), FixSettings(30.0)).fix(input)

        assertEquals(0, result.stats.unresolvedPoints)
        assertEquals(30, result.stats.leadingFixed)
        for (index in 0..29) {
            assertNotNull("leading point $index was not repaired", result.lat[index])
            assertEquals(startLat + index * latStep, result.lat[index]!!, 0.00002)
        }
    }

    @Test
    fun missingElevationIsFilledFromReference() {
        val input = track()
        input[50] = input[50].copy(ele = null)
        val result = TrackFixer(reference(), FixSettings(30.0, fillMissingElevation = true)).fix(input)
        assertNotNull(result.ele[50])
        assertTrue(result.elevationFilled[50])
    }

    @Test
    fun elevationIsPreservedWhenPresent() {
        val input = track()
        val originalElevation = input[50].ele
        val result = TrackFixer(reference(), FixSettings(30.0, fillMissingElevation = true)).fix(input)
        assertEquals(originalElevation, result.ele[50])
        assertTrue(!result.elevationFilled[50])
    }

    @Test
    fun followReferenceTakesEveryCoordinateFromTheReference() {
        val input = track()
        for (index in 20..70) {
            input[index] = input[index].copy(lon = input[index].lon!! + 0.01)
        }
        val result = TrackFixer(
            reference(),
            FixSettings(30.0, fillMissingElevation = false, followReference = true)
        ).fix(input)

        assertEquals(101, result.stats.fixedCoordinates)
        for (index in input.indices) {
            val expected = startLat + index * latStep
            assertTrue("point $index lat", abs(result.lat[index]!! - expected) < 1e-6)
            assertTrue("point $index lon", abs(result.lon[index]!! - startLon) < 1e-9)
        }
    }

    @Test
    fun followReferenceKeepsElevationOfTheSource() {
        val input = track()
        for (index in 20..70) {
            input[index] = input[index].copy(lon = input[index].lon!! + 0.01)
        }
        val result = TrackFixer(
            reference(),
            FixSettings(30.0, fillMissingElevation = false, followReference = true)
        ).fix(input)

        assertEquals(input.size, result.lat.size)
        assertEquals(0, result.stats.filledElevations)
        for (index in input.indices) {
            assertEquals("elevation $index", input[index].ele, result.ele[index])
        }
    }

    @Test
    fun followReferenceStaysOnTheCorrectLapOfARepeatedLoop() {
        val input = loopTrack()
        for (index in 20..70) {
            input[index] = input[index].copy(lon = input[index].lon!! + 0.01)
        }
        val result = TrackFixer(
            loopReference(),
            FixSettings(30.0, fillMissingElevation = false, followReference = true)
        ).fix(input)

        for (index in input.indices) {
            val (lat, lon) = loopPoint(index)
            assertTrue(
                "point $index landed on the wrong lap",
                Geo.distanceMeters(result.lat[index]!!, result.lon[index]!!, lat, lon) < 1.0
            )
        }
    }
}
