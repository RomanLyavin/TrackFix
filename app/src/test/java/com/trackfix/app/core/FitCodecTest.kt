package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/**
 * FIT support is checked against the two real SuperCycle recordings that are
 * already used by the GPX frozen-coordinate regression test.
 */
class FitCodecTest {

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assumeTrue("Test fixture $name is not available", stream != null)
        return stream!!.use { it.readBytes() }
    }

    private fun value(point: GpxPoint, tag: String): Double? {
        val nodes = point.element.getElementsByTagNameNS("*", tag)
        if (nodes.length == 0) return null
        return GpxCodec.parseDoubleOrNull(nodes.item(0).textContent)
    }

    private val settings = FixSettings(30.0, fillMissingElevation = true)

    @Test
    fun parsesBrokenFitRecordsAndSensors() {
        val fit = FitCodec.parse(resource("supercycle_broken.fit"))
        println(
            "broken FIT: records=${fit.records.size} events=${fit.events.size} " +
                "segments=${fit.records.map { it.segmentIndex }.distinct().size} " +
                "first=${fit.records.first().timestamp} last=${fit.records.last().timestamp}"
        )

        assertEquals(2119, fit.records.size)
        assertEquals(22, fit.events.size)
        assertEquals(11, fit.records.map { it.segmentIndex }.distinct().size)
        assertNotNull(fit.records.first().timestamp)

        val first = fit.records.first()
        assertEquals(45.0627386, first.lat!!, 1e-6)
        assertEquals(39.0106571, first.lon!!, 1e-6)
        assertEquals(98.4, first.ele!!, 0.3)
        assertEquals(79, first.heartRate)
        assertEquals(0, first.cadence)
        assertEquals(0, first.power)
        assertEquals(0.0, first.cumulativeDistanceMeters!!, 0.01)
        assertEquals(0.0, first.speedMetersPerSecond!!, 0.001)

        // Every record keeps its timestamp and all records stay in order.
        assertTrue(fit.records.all { it.timestamp != null })
        for (index in 1 until fit.records.size) {
            assertTrue(
                "timestamp goes backwards at $index",
                !fit.records[index].timestamp!!.isBefore(fit.records[index - 1].timestamp!!)
            )
        }
    }

    @Test
    fun parsesReferenceFitAndStitchesAutoPauseSegments() {
        val fit = FitCodec.parse(resource("supercycle_reference.fit"))
        val rawSegments = fit.records.map { it.segmentIndex }.distinct()
        println(
            "reference FIT: records=${fit.records.size} events=${fit.events.size} " +
                "segments=${rawSegments.size} paths=${FitCodec.toGpx(fit, reference = true).points.size}"
        )

        assertEquals(2004, fit.records.size)
        assertEquals(14, fit.events.size)
        assertEquals(7, rawSegments.size)

        val gpx = FitCodec.toGpx(fit, reference = true)
        assertEquals(2004, gpx.points.size)
        assertEquals(7, gpx.points.map { it.segmentIndex }.distinct().size)

        val paths = GpxCodec.referencePaths(gpx)
        assertEquals(1, paths.size)
        assertEquals(11544.3, ReferencePath(paths).totalLength, 5.0)
    }

    @Test
    fun processesFitOriginalAgainstFitReference() {
        val fit = FitCodec.parse(resource("supercycle_broken.fit"))
        val result = FixEngine.process(
            originalBytes = resource("supercycle_broken.fit"),
            originalName = "SuperCycle-ride-20261103-1746.fit",
            referenceBytes = resource("supercycle_reference.fit"),
            settings = settings
        )
        println(
            "FIT+FIT: points=${result.stats.totalPoints} " +
                "frozenRuns=${result.stats.frozenRuns} " +
                "frozenCoordinates=${result.stats.frozenCoordinates} " +
                "fixed=${result.stats.fixedCoordinates} blocks=${result.stats.problemBlocks} " +
                "unresolved=${result.stats.unresolvedPoints}"
        )

        assertEquals(2119, result.stats.totalPoints)
        assertEquals(2, result.stats.frozenRuns)
        assertEquals(826, result.stats.frozenCoordinates)
        assertEquals("SuperCycle-ride-20261103-1746_fixed.gpx", result.suggestedName)

        val output = GpxCodec.parse(result.outputBytes)
        assertEquals(2119, output.points.size)
        File("build/test-output/supercycle-fit-fixed.gpx").apply {
            parentFile?.mkdirs()
            writeBytes(result.outputBytes)
        }

        // Times, sensor values and existing elevation must survive the
        // FIT -> GPX conversion and coordinate repair.
        for (index in fit.records.indices) {
            val expected = fit.records[index]
            val actual = output.points[index]
            assertEquals("time $index", expected.timestamp, actual.time)
            assertEquals("hr $index", expected.heartRate, value(actual, "hr")?.toInt())
            assertEquals("cad $index", expected.cadence, value(actual, "cad")?.toInt())
            assertEquals("power $index", expected.power, value(actual, "power")?.toInt())
            assertEquals(
                "distance $index",
                expected.cumulativeDistanceMeters ?: 0.0,
                value(actual, "distance") ?: 0.0,
                0.01
            )
            assertEquals(
                "speed $index",
                expected.speedMetersPerSecond ?: 0.0,
                value(actual, "speed") ?: 0.0,
                0.001
            )
            assertEquals(
                "elevation $index",
                expected.ele ?: 0.0,
                actual.ele ?: 0.0,
                0.01
            )
        }
    }

    @Test
    fun allGpxAndFitCombinationsWork() {
        val brokenFit = resource("supercycle_broken.fit")
        val referenceFit = resource("supercycle_reference.fit")
        val brokenGpx = resource("supercycle_broken.gpx")
        val referenceGpx = resource("supercycle_reference.gpx")

        val combinations = listOf(
            Triple("fit/fit", brokenFit, referenceFit),
            Triple("fit/gpx", brokenFit, referenceGpx),
            Triple("gpx/fit", brokenGpx, referenceFit),
            Triple("gpx/gpx", brokenGpx, referenceGpx)
        )
        for ((label, original, reference) in combinations) {
            val result = FixEngine.process(
                originalBytes = original,
                originalName = "track.fit",
                referenceBytes = reference,
                settings = settings
            )
            println(
                "$label: points=${result.stats.totalPoints} " +
                    "frozenRuns=${result.stats.frozenRuns} " +
                    "frozenCoordinates=${result.stats.frozenCoordinates} " +
                    "fixed=${result.stats.fixedCoordinates} " +
                    "reference=${result.referenceLengthMeters}"
            )
            assertEquals("$label point count", 2119, result.stats.totalPoints)
            assertEquals("$label frozen runs", 2, result.stats.frozenRuns)
            assertEquals("$label frozen coordinates", 826, result.stats.frozenCoordinates)
            assertEquals("$label output name", "track_fixed.gpx", result.suggestedName)
            assertEquals("$label output points", 2119, GpxCodec.parse(result.outputBytes).points.size)
        }
    }

    /**
     * Real recordings use plain little-endian definitions and whole-second
     * timestamps. This synthetic file covers the other legal FIT features:
     * a compressed timestamp header, a developer field, and a big-endian
     * definition. Message boundaries must stay correct in all three cases.
     */
    @Test
    fun parsesCompressedTimestampsDeveloperFieldsAndBigEndian() {
        val start = Instant.parse("2026-10-29T13:55:16Z")
        val startFit = start.epochSecond - FIT_EPOCH_SECONDS
        val builder = FitBuilder()

        // Event with an explicit timestamp seeds the compressed accumulator.
        builder.definition(
            1,
            21,
            listOf(Triple(253, 4, 0x86), Triple(0, 1, 0x00), Triple(1, 1, 0x00))
        )
        builder.data(1, le32(startFit) + byteArrayOf(0, 1))

        // Records without a timestamp field, timed by compressed headers.
        builder.definition(
            0,
            20,
            listOf(Triple(0, 4, 0x85), Triple(1, 4, 0x85), Triple(2, 2, 0x84))
        )
        builder.compressed(
            0,
            ((startFit + 1) and 0x1F).toInt(),
            latLonPayload(45.0, 22.5) + le16(2743)
        )
        builder.compressed(
            0,
            ((startFit + 2) and 0x1F).toInt(),
            latLonPayload(45.0001, 22.5001) + le16(2744)
        )

        // Record with a developer field that must be skipped.
        builder.definition(
            2,
            20,
            listOf(Triple(0, 4, 0x85), Triple(1, 4, 0x85)),
            developerFields = listOf(0 to 2)
        )
        builder.data(2, latLonPayload(45.1, 22.6) + byteArrayOf(0x12, 0x34))

        // Big-endian record.
        builder.definition(
            3,
            20,
            listOf(Triple(0, 4, 0x85), Triple(1, 4, 0x85)),
            bigEndian = true
        )
        builder.data(3, latLonPayload(46.0, 23.0, bigEndian = true))

        // Compressed speed/distance, including the 12-bit distance rollover.
        builder.definition(4, 20, listOf(Triple(8, 3, 0x0D)))
        builder.data(4, packedSpeedDistance(speedRaw = 1234, distanceRaw = 100))
        builder.data(4, packedSpeedDistance(speedRaw = 100, distanceRaw = 10))

        val bytes = builder.build()
        File("build/test-output/synthetic.fit").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        val file = FitCodec.parse(bytes)
        assertEquals(6, file.records.size)
        assertEquals(1, file.events.size)
        assertEquals(start, file.events.single().timestamp)

        assertEquals(start.plusSeconds(1), file.records[0].timestamp)
        assertEquals(start.plusSeconds(2), file.records[1].timestamp)
        assertEquals(45.0, file.records[0].lat!!, 1e-6)
        assertEquals(22.5, file.records[0].lon!!, 1e-6)
        assertEquals(48.6, file.records[0].ele!!, 1e-6)

        assertEquals(45.1, file.records[2].lat!!, 1e-6)
        assertEquals(22.6, file.records[2].lon!!, 1e-6)
        assertEquals(null, file.records[2].timestamp)

        assertEquals(46.0, file.records[3].lat!!, 1e-6)
        assertEquals(23.0, file.records[3].lon!!, 1e-6)

        assertEquals(12.34, file.records[4].speedMetersPerSecond!!, 1e-6)
        assertEquals(6.25, file.records[4].cumulativeDistanceMeters!!, 1e-6)
        assertEquals(1.0, file.records[5].speedMetersPerSecond!!, 1e-6)
        assertEquals(256.625, file.records[5].cumulativeDistanceMeters!!, 1e-6)

        val gpx = FitCodec.toGpx(file)
        assertEquals(6, gpx.points.size)
        assertEquals(start.plusSeconds(1), gpx.points[0].time)
    }

    @Test
    fun rejectsNonFitInput() {
        assertFalse(FitCodec.looksLikeFit("<?xml version=\"1.0\"?>".toByteArray()))
        var thrown = false
        try {
            FitCodec.parse("not a FIT file".toByteArray())
        } catch (e: GpxException) {
            thrown = true
        }
        assertTrue("non-FIT input must be rejected", thrown)
    }
}

private const val FIT_EPOCH_SECONDS = 631065600L

private class FitBuilder {
    private val body = ByteArrayOutputStream()

    fun definition(
        local: Int,
        global: Int,
        fields: List<Triple<Int, Int, Int>>,
        bigEndian: Boolean = false,
        developerFields: List<Pair<Int, Int>> = emptyList()
    ) {
        body.write(0x40 or (if (developerFields.isNotEmpty()) 0x20 else 0) or local)
        body.write(0)
        body.write(if (bigEndian) 1 else 0)
        writeUInt16(body, global, bigEndian)
        body.write(fields.size)
        for ((number, size, baseType) in fields) {
            body.write(number)
            body.write(size)
            body.write(baseType)
        }
        if (developerFields.isNotEmpty()) {
            body.write(developerFields.size)
            for ((number, size) in developerFields) {
                body.write(number)
                body.write(size)
                body.write(0) // developer_data_index
            }
        }
    }

    fun data(local: Int, payload: ByteArray) {
        body.write(local)
        body.write(payload)
    }

    fun compressed(local: Int, timeOffset: Int, payload: ByteArray) {
        body.write(0x80 or ((local and 0x03) shl 5) or (timeOffset and 0x1F))
        body.write(payload)
    }

    fun build(): ByteArray {
        val data = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(12)
        out.write(0x10)
        writeUInt16(out, 2100, bigEndian = false)
        writeUInt32(out, data.size.toLong(), bigEndian = false)
        out.write(".FIT".toByteArray(Charsets.US_ASCII))
        out.write(data)
        return out.toByteArray()
    }
}

private fun latLonPayload(lat: Double, lon: Double, bigEndian: Boolean = false): ByteArray {
    val out = ByteArrayOutputStream()
    writeSInt32(out, semicircles(lat), bigEndian)
    writeSInt32(out, semicircles(lon), bigEndian)
    return out.toByteArray()
}

private fun packedSpeedDistance(speedRaw: Int, distanceRaw: Int): ByteArray {
    val packed = (speedRaw and 0xFFF) or ((distanceRaw and 0xFFF) shl 12)
    return byteArrayOf(
        (packed and 0xFF).toByte(),
        ((packed shr 8) and 0xFF).toByte(),
        ((packed shr 16) and 0xFF).toByte()
    )
}

private fun semicircles(degrees: Double): Long =
    Math.round(degrees * (2147483648.0 / 180.0))

private fun le32(value: Long): ByteArray = ByteArrayOutputStream().also {
    writeUInt32(it, value, bigEndian = false)
}.toByteArray()

private fun le16(value: Int): ByteArray = ByteArrayOutputStream().also {
    writeUInt16(it, value, bigEndian = false)
}.toByteArray()

private fun writeUInt16(out: ByteArrayOutputStream, value: Int, bigEndian: Boolean) {
    if (bigEndian) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    } else {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }
}

private fun writeUInt32(out: ByteArrayOutputStream, value: Long, bigEndian: Boolean) {
    if (bigEndian) {
        for (shift in 24 downTo 0 step 8) out.write(((value shr shift) and 0xFF).toInt())
    } else {
        for (shift in 0..24 step 8) out.write(((value shr shift) and 0xFF).toInt())
    }
}

private fun writeSInt32(out: ByteArrayOutputStream, value: Long, bigEndian: Boolean) {
    writeUInt32(out, value and 0xFFFFFFFFL, bigEndian)
}
