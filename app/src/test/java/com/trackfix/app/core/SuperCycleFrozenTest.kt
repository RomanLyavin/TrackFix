package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Regression checks on the real SuperCycle recordings: the 806-point frozen
 * GPS run must be spread along the reference, while stops and a legitimate
 * smooth detour must stay exactly where the recorder wrote them.
 */
class SuperCycleFrozenTest {

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assumeTrue("Test fixture $name is not available", stream != null)
        return stream!!.use { it.readBytes() }
    }

    private fun loadReference(): GpxFile =
        GpxCodec.parse(resource("supercycle_reference.gpx"), reference = true)

    private fun valuesOf(file: GpxFile, tag: String): List<String?> =
        file.points.map { point ->
            val nodes = point.element.getElementsByTagNameNS("*", tag)
            if (nodes.length > 0) nodes.item(0).textContent else null
        }

    @Test
    fun referenceAutoPauseSegmentsAreStitchedIntoOnePath() {
        val paths = GpxCodec.referencePaths(loadReference())
        assertEquals(1, paths.size)
        val reference = ReferencePath(paths)
        assertEquals(11544.3, reference.totalLength, 5.0)
    }

    @Test
    fun frozenRunIsSpreadAlongTheReference() {
        val referenceFile = loadReference()
        val reference = ReferencePath(GpxCodec.referencePaths(referenceFile))
        val file = GpxCodec.parse(resource("supercycle_broken.gpx"))
        val input = file.trackPoints()
        val originalLat = file.points.map { it.lat }
        val originalLon = file.points.map { it.lon }
        val originalEle = file.points.map { it.ele }

        val timesBefore = valuesOf(file, "time")
        val heartRatesBefore = valuesOf(file, "hr")
        val cadencesBefore = valuesOf(file, "cad")
        val powersBefore = valuesOf(file, "power")
        val distancesBefore = valuesOf(file, "distance")

        val result = TrackFixer(reference, FixSettings(30.0, fillMissingElevation = true)).fix(input)

        println(
            "frozenRuns=${result.stats.frozenRuns} " +
                "frozenCoordinates=${result.stats.frozenCoordinates} " +
                "fixed=${result.stats.fixedCoordinates} blocks=${result.stats.problemBlocks} " +
                "unresolved=${result.stats.unresolvedPoints}"
        )

        GpxCodec.applyFixes(file, result)
        val output = File("build/test-output/supercycle-fixed.gpx")
        output.parentFile?.mkdirs()
        output.writeBytes(GpxCodec.serialize(file))

        val changedOutsideFrozen = (0 until input.size).filter { index ->
            result.coordinateChanged[index] && index !in 0..19 && index !in 1254..2059
        }
        if (changedOutsideFrozen.isNotEmpty()) {
            println("changed outside frozen runs: $changedOutsideFrozen")
        }

        assertEquals(2119, result.stats.totalPoints)
        assertEquals(2, result.stats.frozenRuns)
        assertEquals(826, result.stats.frozenCoordinates)

        // The frozen run must now follow the reference, not sit on one point.
        val frozenStart = 1254
        val frozenEnd = 2059
        var previousS = -1.0
        for (index in frozenStart..frozenEnd) {
            assertTrue("frozen coordinate $index was not repaired", result.coordinateChanged[index])
            val projection = reference.nearest(result.lat[index]!!, result.lon[index]!!, 5.0)
            assertNotNull("frozen coordinate $index did not land on the reference", projection)
            assertTrue("frozen coordinate $index is off the reference", projection!!.distance < 1.0)
            assertTrue(
                "frozen coordinate $index moves backwards along the reference",
                projection.s >= previousS - 0.01
            )
            previousS = projection.s
        }

        // The leading frozen run must be repaired too, including point zero.
        var previousStartS = -1.0
        for (index in 0..19) {
            assertTrue("start coordinate $index was not repaired", result.coordinateChanged[index])
            val projection = reference.nearest(result.lat[index]!!, result.lon[index]!!, 5.0)
            assertNotNull("start coordinate $index did not land on the reference", projection)
            assertTrue("start coordinate $index is off the reference", projection!!.distance < 1.0)
            assertTrue(
                "start coordinate $index moves backwards along the reference",
                projection.s >= previousStartS - 0.01
            )
            previousStartS = projection.s
        }
        val startProjection = reference.nearest(result.lat[frozenStart]!!, result.lon[frozenStart]!!, 5.0)!!
        val endProjection = reference.nearest(result.lat[frozenEnd]!!, result.lon[frozenEnd]!!, 5.0)!!
        assertTrue(
            "frozen run covers too little of the reference: ${endProjection.s - startProjection.s} m",
            endProjection.s - startProjection.s > 4000.0
        )

        // The old 806-point freeze must be gone. Short identical runs at real
        // stops are fine because the sensor distance does not grow there.
        var longestIdenticalRun = 1
        var currentRun = 1
        for (index in 1 until result.lat.size) {
            if (result.lat[index] == result.lat[index - 1] &&
                result.lon[index] == result.lon[index - 1]
            ) {
                currentRun++
                if (currentRun > longestIdenticalRun) longestIdenticalRun = currentRun
            } else {
                currentRun = 1
            }
        }
        assertTrue("identical coordinate run is still too long: $longestIdenticalRun", longestIdenticalRun < 50)

        // The smooth detour and the stops are real riding, not GPS glitches.
        for (index in 659..770) {
            assertEquals(originalLat[index]!!, result.lat[index]!!, 0.0)
            assertEquals(originalLon[index]!!, result.lon[index]!!, 0.0)
        }
        for (index in listOf(710, 711, 712, 713, 714, 790, 791, 792, 904, 905, 1060, 1061, 1062)) {
            assertEquals(originalLat[index]!!, result.lat[index]!!, 0.0)
            assertEquals(originalLon[index]!!, result.lon[index]!!, 0.0)
        }

        // Neighbouring anchors must stay untouched.
        for (index in listOf(1253, 2071)) {
            assertEquals(originalLat[index]!!, result.lat[index]!!, 0.0)
            assertEquals(originalLon[index]!!, result.lon[index]!!, 0.0)
        }

        val reparsed = GpxCodec.parse(GpxCodec.serialize(file))
        assertEquals(2119, reparsed.points.size)
        assertEquals(timesBefore, valuesOf(reparsed, "time"))
        assertEquals(heartRatesBefore, valuesOf(reparsed, "hr"))
        assertEquals(cadencesBefore, valuesOf(reparsed, "cad"))
        assertEquals(powersBefore, valuesOf(reparsed, "power"))
        assertEquals(distancesBefore, valuesOf(reparsed, "distance"))
        assertEquals(originalEle, reparsed.points.map { it.ele })
    }
}
