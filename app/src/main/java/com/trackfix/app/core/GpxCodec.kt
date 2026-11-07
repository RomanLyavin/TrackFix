package com.trackfix.app.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class GpxException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class GpxPoint(
    val index: Int,
    val lat: Double?,
    val lon: Double?,
    val ele: Double?,
    val time: Instant?,
    val speedMetersPerSecond: Double?,
    val cumulativeDistanceMeters: Double?,
    val element: Element,
    val segmentIndex: Int,
    val cadence: Int? = null
) {
    fun toTrackPoint(): TrackPoint = TrackPoint(
        lat = lat,
        lon = lon,
        ele = ele,
        time = time,
        speedMetersPerSecond = speedMetersPerSecond,
        cumulativeDistanceMeters = cumulativeDistanceMeters,
        cadence = cadence
    )
}

data class GpxFile(
    val document: Document,
    val points: List<GpxPoint>,
    val namespaceUri: String?,
    val reference: Boolean
) {
    fun trackPoints(): List<TrackPoint> = points.map { it.toTrackPoint() }
}

object GpxCodec {
    const val MAX_FILE_BYTES = 30 * 1024 * 1024
    private const val REFERENCE_JOIN_MAX_GAP_METERS = 100.0

    fun parse(bytes: ByteArray, reference: Boolean = false): GpxFile {
        if (bytes.isEmpty()) throw GpxException("empty file")
        if (bytes.size > MAX_FILE_BYTES) throw GpxException("file too large")

        val document = try {
            newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw GpxException("cannot parse XML", e)
        }

        val root = document.documentElement
            ?: throw GpxException("missing document element")
        val rootName = localName(root)
        if (!rootName.equals("gpx", ignoreCase = true)) {
            throw GpxException("not a GPX document")
        }

        val elements = if (reference) collectReferenceElements(document) else collectTrackElements(document)
        if (elements.isEmpty()) throw GpxException("no track points")

        val namespaceUri = root.namespaceURI
        val points = elements.mapIndexed { index, pair ->
            val element = pair.first
            val lat = parseDoubleOrNull(element.getAttribute("lat"))
            val lon = parseDoubleOrNull(element.getAttribute("lon"))
            val timeText = directChild(element, "time")?.textContent
            GpxPoint(
                index = index,
                lat = lat,
                lon = lon,
                ele = parseDoubleOrNull(directChild(element, "ele")?.textContent),
                time = parseTime(timeText),
                speedMetersPerSecond = descendantDouble(element, "speed"),
                cumulativeDistanceMeters = descendantDouble(element, "distance"),
                element = element,
                segmentIndex = pair.second,
                cadence = descendantInt(element, "cad")
            )
        }

        return GpxFile(document, points, namespaceUri, reference)
    }

    fun referencePaths(file: GpxFile): List<List<RefVertex>> {
        val grouped = LinkedHashMap<Int, MutableList<RefVertex>>()
        for (point in file.points) {
            if (!Geo.isValidCoordinate(point.lat, point.lon)) continue
            grouped.getOrPut(point.segmentIndex) { mutableListOf() }
                .add(RefVertex(point.lat!!, point.lon!!, point.ele))
        }
        val paths = grouped.values.filter { it.size >= 2 }.map { it.toList() }
        return stitchReferencePaths(paths)
    }

    /**
     * Recorders split a track into segments on every auto-pause. Those pieces
     * still describe one continuous route, so join the ones whose endpoints
     * are close. Genuinely separate pieces stay separate.
     */
    private fun stitchReferencePaths(paths: List<List<RefVertex>>): List<List<RefVertex>> {
        if (paths.size < 2) return paths
        val result = ArrayList<List<RefVertex>>()
        var current = paths.first().toMutableList()
        for (next in paths.drop(1)) {
            val end = current.last()
            val start = next.first()
            val gap = Geo.distanceMeters(end.lat, end.lon, start.lat, start.lon)
            if (gap <= REFERENCE_JOIN_MAX_GAP_METERS) {
                current.addAll(next)
            } else {
                result.add(current)
                current = next.toMutableList()
            }
        }
        result.add(current)
        return result
    }

    fun applyFixes(file: GpxFile, result: FixResult) {
        val document = file.document
        for (point in file.points) {
            val i = point.index
            if (result.coordinateChanged[i]) {
                val lat = result.lat[i]
                val lon = result.lon[i]
                if (lat != null && lon != null) {
                    point.element.setAttribute("lat", formatCoordinate(lat))
                    point.element.setAttribute("lon", formatCoordinate(lon))
                }
            }
            if (result.elevationFilled[i]) {
                val ele = result.ele[i] ?: continue
                setOrCreateElevation(document, point.element, file.namespaceUri, ele)
            }
        }
    }

    /**
     * Removes the per-point cumulative "distance" extension. It becomes stale
     * when coordinates are rebuilt from a reference track, and some services
     * (Strava in particular) trust it instead of the real geometry.
     */
    fun removeDistanceExtensions(file: GpxFile) {
        for (point in file.points) {
            val nodes = ArrayList<Element>()
            val namespaced = point.element.getElementsByTagNameNS("*", "distance")
            for (index in 0 until namespaced.length) {
                (namespaced.item(index) as? Element)?.let { nodes.add(it) }
            }
            val plain = point.element.getElementsByTagName("distance")
            for (index in 0 until plain.length) {
                (plain.item(index) as? Element)?.let { if (it !in nodes) nodes.add(it) }
            }
            for (node in nodes) {
                val parent = node.parentNode ?: continue
                parent.removeChild(node)
                if (localName(parent) == "extensions" && !hasElementChildren(parent)) {
                    parent.parentNode?.removeChild(parent)
                }
            }
        }
    }

    fun serialize(file: GpxFile): ByteArray = serialize(file.document)

    fun serialize(document: Document): ByteArray {
        return try {
            val factory = TransformerFactory.newInstance()
            runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            val transformer = factory.newTransformer()
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            transformer.setOutputProperty(OutputKeys.INDENT, "no")
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            val output = ByteArrayOutputStream()
            transformer.transform(DOMSource(document), StreamResult(output))
            output.toByteArray()
        } catch (e: Exception) {
            throw GpxException("cannot serialize GPX", e)
        }
    }

    fun pointCountLabel(points: List<GpxPoint>): Int = points.size

    private fun collectTrackElements(document: Document): List<Pair<Element, Int>> {
        val result = mutableListOf<Pair<Element, Int>>()
        val segments = allElements(document, "trkseg")
        if (segments.isNotEmpty()) {
            segments.forEachIndexed { segmentIndex, segment ->
                directChildren(segment, "trkpt").forEach { result.add(it to segmentIndex) }
            }
        } else {
            allElements(document, "trkpt").forEach { result.add(it to 0) }
        }
        return result
    }

    private fun collectReferenceElements(document: Document): List<Pair<Element, Int>> {
        val tracks = collectTrackElements(document)
        if (tracks.isNotEmpty()) return tracks

        val routes = allElements(document, "rte")
        val routePoints = mutableListOf<Pair<Element, Int>>()
        if (routes.isNotEmpty()) {
            routes.forEachIndexed { routeIndex, route ->
                directChildren(route, "rtept").forEach { routePoints.add(it to routeIndex) }
            }
        } else {
            allElements(document, "rtept").forEach { routePoints.add(it to 0) }
        }
        return routePoints
    }

    private fun newDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { isXIncludeAware = false }
    }.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }

    private fun setOrCreateElevation(document: Document, point: Element, namespaceUri: String?, value: Double) {
        val formatted = formatElevation(value)
        val existing = directChild(point, "ele")
        if (existing != null) {
            existing.textContent = formatted
            return
        }
        val element = if (namespaceUri.isNullOrBlank()) {
            document.createElement("ele")
        } else {
            document.createElementNS(namespaceUri, "ele")
        }
        element.textContent = formatted
        val before = directChild(point, "time") ?: directChild(point, "extensions")
        if (before != null) {
            point.insertBefore(element, before)
        } else {
            point.appendChild(element)
        }
    }

    fun localName(node: Node): String {
        return node.localName ?: node.nodeName.substringAfter(':')
    }

    private fun allElements(document: Document, name: String): List<Element> {
        val namespaced = document.getElementsByTagNameNS("*", name)
        if (namespaced.length > 0) {
            return (0 until namespaced.length).mapNotNull { namespaced.item(it) as? Element }
        }
        val plain = document.getElementsByTagName(name)
        return (0 until plain.length).mapNotNull { plain.item(it) as? Element }
    }

    private fun directChildren(parent: Element, name: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && localName(child) == name) {
                result.add(child as Element)
            }
        }
        return result
    }

    private fun directChild(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && localName(child) == name) {
                return child as Element
            }
        }
        return null
    }

    fun parseDoubleOrNull(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        return value.trim().replace(',', '.').toDoubleOrNull()
    }

    private fun descendantDouble(element: Element, name: String): Double? {
        val nodes = element.getElementsByTagNameNS("*", name)
        if (nodes.length > 0) {
            return parseDoubleOrNull(nodes.item(0).textContent)
        }
        val plain = element.getElementsByTagName(name)
        if (plain.length > 0) {
            return parseDoubleOrNull(plain.item(0).textContent)
        }
        return null
    }

    private fun descendantInt(element: Element, name: String): Int? {
        return descendantDouble(element, name)?.toInt()
    }

    private fun hasElementChildren(parent: Node): Boolean {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            if (children.item(index).nodeType == Node.ELEMENT_NODE) return true
        }
        return false
    }

    fun parseTime(value: String?): Instant? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return Instant.parse(text) }
        runCatching { return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
        runCatching {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
        }
        runCatching {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay().toInstant(ZoneOffset.UTC)
        }
        return null
    }

    fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.7f", value)

    fun formatElevation(value: Double): String {
        val text = String.format(Locale.US, "%.2f", value)
        return text.trimEnd('0').trimEnd('.')
    }

    fun suggestedName(originalName: String, suffix: String): String {
        val clean = originalName.substringAfterLast('/').substringAfterLast('\\')
        val base = if (clean.contains('.')) clean.substringBeforeLast('.') else clean
        return (base.ifBlank { "track" } + suffix + ".gpx")
    }
}
