package com.trackfix.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element

class GpxCodecTest {

    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assumeTrue("Test fixture $name is not available", stream != null)
        return stream!!.use { it.readBytes() }
    }

    @Test
    fun originalFileIsParsed() {
        val file = GpxCodec.parse(resource("orig_file.gpx"))
        assertEquals(790, file.points.size)
        assertTrue(file.points.first().time != null)
        assertTrue(file.trackPoints().count { it.hasCoordinates } == 790)
    }

    @Test
    fun referenceIsParsedAsOnePath() {
        val file = GpxCodec.parse(resource("correct_track.gpx"), reference = true)
        val paths = GpxCodec.referencePaths(file)
        assertEquals(1, paths.size)
        val reference = ReferencePath(paths)
        assertEquals(5016.0, reference.totalLength, 5.0)
        assertTrue(reference.paths[0].closed)
    }

    @Test
    fun missingCoordinateAttributesAreReadAsNull() {
        val xml = """
            <?xml version="1.0"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">
              <trk><trkseg>
                <trkpt lat="" lon=""><time>2026-01-01T10:00:00Z</time></trkpt>
                <trkpt lat="45.0" lon="39.0"><time>2026-01-01T10:00:01Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent().toByteArray()
        val file = GpxCodec.parse(xml)
        assertEquals(2, file.points.size)
        assertEquals(null, file.points[0].lat)
        assertEquals(null, file.points[0].lon)
        assertTrue(file.trackPoints()[1].hasCoordinates)
    }

    @Test
    fun extensionValuesArePreservedAfterApplyingFixes() {
        val file = GpxCodec.parse(resource("orig_file.gpx"))
        val before = heartRates(file.points.map { it.element })
        val referenceFile = GpxCodec.parse(resource("correct_track.gpx"), reference = true)
        val reference = ReferencePath(GpxCodec.referencePaths(referenceFile))
        val result = TrackFixer(reference, FixSettings(30.0)).fix(file.trackPoints())
        GpxCodec.applyFixes(file, result)
        val after = heartRates(file.points.map { it.element })
        assertEquals(before, after)
    }

    @Test
    fun closeReferenceSegmentsAreStitched() {
        val xml = """
            <?xml version="1.0"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">
              <trk>
                <trkseg>
                  <trkpt lat="45.0" lon="39.0"><ele>100</ele></trkpt>
                  <trkpt lat="45.0" lon="39.0005"><ele>100</ele></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="45.0" lon="39.0007"><ele>100</ele></trkpt>
                  <trkpt lat="45.0" lon="39.0012"><ele>100</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent().toByteArray()
        val file = GpxCodec.parse(xml, reference = true)
        val paths = GpxCodec.referencePaths(file)
        assertEquals(1, paths.size)
        assertEquals(4, paths[0].size)
    }

    @Test
    fun distantReferenceSegmentsStaySeparate() {
        val xml = """
            <?xml version="1.0"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">
              <trk>
                <trkseg>
                  <trkpt lat="45.0" lon="39.0"><ele>100</ele></trkpt>
                  <trkpt lat="45.0" lon="39.0005"><ele>100</ele></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="45.0" lon="39.002"><ele>100</ele></trkpt>
                  <trkpt lat="45.0" lon="39.0025"><ele>100</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent().toByteArray()
        val file = GpxCodec.parse(xml, reference = true)
        val paths = GpxCodec.referencePaths(file)
        assertEquals(2, paths.size)
    }

    @Test
    fun suggestedNameUsesFixedSuffix() {
        assertEquals("run_fixed.gpx", GpxCodec.suggestedName("run.gpx", "_fixed"))
        assertEquals("run_fixed.gpx", GpxCodec.suggestedName("folder/run.gpx", "_fixed"))
    }

    private fun heartRates(elements: List<Element>): List<String?> {
        return elements.map { element ->
            val nodes = element.getElementsByTagNameNS("*", "hr")
            if (nodes.length > 0) nodes.item(0).textContent else null
        }
    }
}
