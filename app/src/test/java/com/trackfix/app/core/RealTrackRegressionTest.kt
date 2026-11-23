package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Duration
import java.io.File

/**
 * Regression checks for the two real recordings that exposed branch flips on
 * self-overlapping references. The fixtures are byte-for-byte copies of the
 * files supplied with the bug report.
 */
class RealTrackRegressionTest {

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assumeTrue("Test fixture $name is not available", stream != null)
        return stream!!.use { it.readBytes() }
    }

    private fun fix(source: String, reference: String): GpxFile {
        val result = FixEngine.process(
            originalBytes = resource(source),
            originalName = source,
            referenceBytes = resource(reference),
            settings = FixSettings(30.0, fillMissingElevation = true)
        )
        return GpxCodec.parse(result.outputBytes)
    }

    private fun reference(name: String): ReferencePath {
        val file = GpxCodec.parse(resource(name), reference = true)
        return ReferencePath(GpxCodec.referencePaths(file))
    }

    private fun maxStep(output: GpxFile): Double {
        var max = 0.0
        for (index in 1 until output.points.size) {
            val a = output.points[index - 1]
            val b = output.points[index]
            if (a.lat == null || a.lon == null || b.lat == null || b.lon == null) continue
            val distance = Geo.distanceMeters(a.lat!!, a.lon!!, b.lat!!, b.lon!!)
            if (distance > max) max = distance
        }
        return max
    }

    private fun stepAt(output: GpxFile, index: Int): Double {
        val a = output.points[index - 1]
        val b = output.points[index]
        if (a.lat == null || a.lon == null || b.lat == null || b.lon == null) {
            return Double.POSITIVE_INFINITY
        }
        return Geo.distanceMeters(a.lat!!, a.lon!!, b.lat!!, b.lon!!)
    }

    /**
     * Cumulative physical distance along the repaired output. Projecting a
     * stadium track onto a reference with many near-identical lap copies is
     * ambiguous, while the output itself is continuous. The total distance is
     * therefore the reliable check that the whole reference route was covered
     * exactly once.
     */
    private fun cumulativeDistance(output: GpxFile): DoubleArray {
        val result = DoubleArray(output.points.size)
        var total = 0.0
        for (index in 1 until output.points.size) {
            val a = output.points[index - 1]
            val b = output.points[index]
            if (a.lat != null && a.lon != null && b.lat != null && b.lon != null) {
                total += Geo.distanceMeters(a.lat!!, a.lon!!, b.lat!!, b.lon!!)
            }
            result[index] = total
        }
        return result
    }

    @Test
    fun november12JumpIsRemovedAndTimesArePreserved() {
        val fit = FitCodec.parse(resource("fix_20261112_source.fit"))
        val output = fix("fix_20261112_source.fit", "fix_20261112_reference.gpx")
        val reference = reference("fix_20261112_reference.gpx")

        assertEquals(1025, output.points.size)
        val sourceTimes = fit.records.mapNotNull { it.timestamp }
        val outputTimes = output.points.mapNotNull { it.time }
        assertEquals(sourceTimes.size, outputTimes.size)
        for (index in sourceTimes.indices) {
            assertEquals("time $index", sourceTimes[index], outputTimes[index])
        }
        assertTrue(output.points.all { it.lat != null && it.lon != null })

        // The old output contained a 331 m jump at index 162 and a branch flip
        // around 17:45. Neither may survive.
        assertTrue("max step ${maxStep(output)}", maxStep(output) < 60.0)
        for (index in 155..170) {
            assertTrue("step $index = ${stepAt(output, index)}", stepAt(output, index) < 60.0)
        }
        for (index in 781..793) {
            assertTrue("step $index = ${stepAt(output, index)}", stepAt(output, index) < 60.0)
        }

        // The route is one closed loop: the last point returns to the first.
        val first = output.points.first()
        val last = output.points.last()
        assertTrue(
            "end is ${Geo.distanceMeters(first.lat!!, first.lon!!, last.lat!!, last.lon!!)} m from start",
            Geo.distanceMeters(first.lat!!, first.lon!!, last.lat!!, last.lon!!) < 120.0
        )
        val travelled = cumulativeDistance(output)
        assertTrue("total distance ${travelled.last()}", travelled.last() > reference.totalLength * 0.95)
        assertTrue("total distance ${travelled.last()}", travelled.last() < reference.totalLength * 1.10)
    }

    @Test
    fun november10CompletesSixteenFullLaps() {
        val fit = FitCodec.parse(resource("fix_20261110_source.fit"))
        val output = fix("fix_20261110_source.fit", "fix_20261110_reference.gpx")
        val reference = reference("fix_20261110_reference.gpx")

        assertEquals(2559, output.points.size)
        val sourceTimes = fit.records.mapNotNull { it.timestamp }
        val outputTimes = output.points.mapNotNull { it.time }
        assertEquals(sourceTimes.size, outputTimes.size)
        for (index in sourceTimes.indices) {
            assertEquals("time $index", sourceTimes[index], outputTimes[index])
        }
        assertTrue(output.points.all { it.lat != null && it.lon != null })
        assertTrue("max step ${maxStep(output)}", maxStep(output) < 60.0)

        // The trailing GPS gap must continue forward from the last good point.
        // Anchoring it at the beginning of the route produced 15 full laps plus
        // an incomplete lap.
        val travelled = cumulativeDistance(output)
        assertTrue("distance 2057 = ${travelled[2057]}", travelled[2057] > 5000.0)
        assertTrue("distance 2124 = ${travelled[2124]}", travelled[2124] > 5200.0)
        assertTrue(
            "trailing block moves backwards: ${travelled[2057]} -> ${travelled[2124]}",
            travelled[2124] - travelled[2057] > 100.0
        )
        // One complete reference route is sixteen stadium laps.
        assertTrue(
            "total distance ${travelled.last()}",
            travelled.last() > reference.totalLength * 0.95
        )
        assertTrue(
            "total distance ${travelled.last()}",
            travelled.last() < reference.totalLength * 1.05
        )

        // The full reference route is completed at the start/finish seam.
        val first = output.points.first()
        val last = output.points.last()
        val seam = Geo.distanceMeters(first.lat!!, first.lon!!, last.lat!!, last.lon!!)
        assertTrue("end is $seam m from the start", seam < 40.0)
        assertNotNull(output.points[2558].lat)
        assertNotNull(output.points[2558].lon)
    }

    private fun fixFollow(source: String, referenceName: String, threshold: Double): GpxFile {
        val result = FixEngine.process(
            originalBytes = resource(source),
            originalName = source,
            referenceBytes = resource(referenceName),
            settings = FixSettings(threshold, fillMissingElevation = true, followReference = true)
        )
        return GpxCodec.parse(result.outputBytes)
    }

    /**
     * The 22.11.2026 recording covers the same route length as its reference
     * (9787 m against 9699 m) while the reference passes the same places
     * repeatedly. Selecting the nearest copy per point flipped onto another
     * copy and produced a 74 m step around 40:53. The overlay must stay
     * continuous along the reference instead.
     */
    @Test
    fun november22FollowReferenceHasNoJumpOnTheLastLap() {
        val output = fixFollow("err2211_source.fit", "err2211_reference.gpx", 20.0)
        val ref = reference("err2211_reference.gpx")

        var maxDeviation = 0.0
        for (point in output.points) {
            if (point.lat == null || point.lon == null) continue
            val projection = ref.nearest(point.lat!!, point.lon!!, 500.0)
            if (projection != null) maxDeviation = maxOf(maxDeviation, projection.distance)
        }
        assertTrue("max deviation from reference: $maxDeviation m", maxDeviation < 1.0)

        val start = output.points.first().time!!
        for (index in 1 until output.points.size) {
            val a = output.points[index - 1]
            val b = output.points[index]
            if (a.lat == null || a.lon == null || b.lat == null || b.lon == null) continue
            val seconds = Duration.between(start, b.time).toSeconds().toDouble()
            if (seconds < 2445.0 || seconds > 2456.0) continue
            val step = Geo.distanceMeters(a.lat!!, a.lon!!, b.lat!!, b.lon!!)
            assertTrue("step ${step}m at ${seconds}s", step < 10.0)
        }

        val worst = maxStep(output)
        assertTrue("worst step ${worst}m", worst < 45.0)
    }

    @Test
    fun writesNovember22FollowGpx() {
        val result = FixEngine.process(
            originalBytes = resource("err2211_source.fit"),
            originalName = "i213267505.fit",
            referenceBytes = resource("err2211_reference.gpx"),
            settings = FixSettings(20.0, fillMissingElevation = true, followReference = true)
        )
        val dir = File("build/test-output")
        dir.mkdirs()
        File(dir, "err2211-follow-1.7.7.gpx").writeBytes(result.outputBytes)
    }
}
