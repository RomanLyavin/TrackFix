package com.trackfix.app.core

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.time.Instant

class MergeException(
    val reason: Reason,
    message: String = reason.name
) : Exception(message) {
    enum class Reason {
        TOO_FEW_FILES,
        NO_TIME,
        NO_COORDINATES,
        EMPTY_RESULT
    }
}

data class MergeItem(
    val name: String,
    val bytes: ByteArray
)

data class MergeFileInfo(
    val points: Int,
    val startTime: Instant,
    val endTime: Instant,
    val distanceMeters: Double
)

data class MergeResult(
    val outputBytes: ByteArray,
    val suggestedName: String,
    val inputSourceCount: Int,
    val contributedSources: Int,
    val totalPoints: Int,
    val trimmedPoints: Int,
    val skippedSources: List<String>,
    val startTime: Instant?,
    val endTime: Instant?,
    val distanceMeters: Double,
    val segmentCount: Int
)

/**
 * Simple chronological merge of several GPX/FIT tracks. Every source is
 * parsed with the same codec as the repair mode. Files are ordered by their
 * first timestamp. For every following file the prefix whose timestamps are
 * not newer than the already merged part is removed, so the resulting track
 * has no backward jumps in time. Points are written into one GPX track, but
 * every non-empty source segment keeps its own <trkseg>. This prevents
 * straight connector lines between separate recordings or auto-pause pieces
 * and keeps distance calculation correct in Strava and similar services.
 * Existing point elements (including sensor extensions) are preserved, but
 * the per-point cumulative "distance" extension is removed: every source
 * starts it from zero, and keeping the old values makes Strava show only the
 * distance of one source file.
 */
object MergeEngine {

    private const val XMLNS_NAMESPACE = "http://www.w3.org/2000/xmlns/"

    private data class SourceMeta(
        val originalIndex: Int,
        val item: MergeItem,
        val info: MergeFileInfo
    )

    fun inspect(bytes: ByteArray): MergeFileInfo {
        val file = FixEngine.parseTrackFile(bytes)
        return inspect(file)
    }

    private fun inspect(file: GpxFile): MergeFileInfo {
        val times = file.points.mapNotNull { it.time }
        if (times.isEmpty()) throw MergeException(MergeException.Reason.NO_TIME)
        if (file.points.none { Geo.isValidCoordinate(it.lat, it.lon) }) {
            throw MergeException(MergeException.Reason.NO_COORDINATES)
        }
        return MergeFileInfo(
            points = file.points.size,
            startTime = times.minOrNull()!!,
            endTime = times.maxOrNull()!!,
            distanceMeters = distanceMeters(file)
        )
    }

    private fun distanceMeters(file: GpxFile): Double {
        var total = 0.0
        var previous: GpxPoint? = null
        for (point in file.points) {
            val prev = previous
            if (prev != null && prev.segmentIndex == point.segmentIndex &&
                Geo.isValidCoordinate(prev.lat, prev.lon) &&
                Geo.isValidCoordinate(point.lat, point.lon)
            ) {
                total += Geo.distanceMeters(
                    prev.lat!!,
                    prev.lon!!,
                    point.lat!!,
                    point.lon!!
                )
            }
            previous = point
        }
        return total
    }

    fun merge(items: List<MergeItem>): MergeResult {
        if (items.size < 2) throw MergeException(MergeException.Reason.TOO_FEW_FILES)

        // First pass: only metadata is kept. This lets us order the files
        // without holding all source DOM trees in memory at the same time.
        val metas = items.mapIndexed { index, item ->
            val file = FixEngine.parseTrackFile(item.bytes, item.name)
            SourceMeta(index, item, inspect(file))
        }
        val ordered = metas.sortedWith(
            compareBy<SourceMeta> { it.info.startTime }.thenBy { it.originalIndex }
        )

        val baseMeta = ordered.first()
        val baseFile = FixEngine.parseTrackFile(baseMeta.item.bytes, baseMeta.item.name)
        val document = baseFile.document
        val root = document.documentElement
            ?: throw GpxException("missing GPX root")
        val namespace = root.namespaceURI
        val declaredNamespaces = LinkedHashMap<String, String>()
        collectNamespaceDeclarations(root, declaredNamespaces)

        // Rebuild the document from scratch but keep the root and its
        // namespace declarations. This preserves prefixes used by extensions
        // from the first source and gives a stable base for imported points.
        while (root.hasChildNodes()) root.removeChild(root.firstChild)
        root.setAttribute("creator", "TrackFix")

        val metadata = createElement(document, namespace, "metadata")
        val metadataTime = createElement(document, namespace, "time")
        metadataTime.textContent = baseMeta.info.startTime.toString()
        metadata.appendChild(metadataTime)
        root.appendChild(metadata)

        val track = createElement(document, namespace, "trk")
        val trackName = createElement(document, namespace, "name")
        trackName.textContent = baseName(baseMeta.item.name) + " (merged)"
        track.appendChild(trackName)
        root.appendChild(track)

        var cutoff: Instant? = null
        var keptPoints = 0
        var trimmedPoints = 0
        var contributedSources = 0
        var firstTime: Instant? = null
        var lastTime: Instant? = null
        var totalDistance = 0.0
        var previousPoint: GpxPoint? = null
        var segmentCount = 0
        val skippedSources = ArrayList<String>()

        for ((position, meta) in ordered.withIndex()) {
            val file = if (position == 0) {
                baseFile
            } else {
                FixEngine.parseTrackFile(meta.item.bytes, meta.item.name)
            }
            if (position > 0) {
                copyNamespaceDeclarations(file.document.documentElement, root, declaredNamespaces)
            }

            val firstIndex = if (cutoff == null) {
                0
            } else {
                file.points.indexOfFirst { point ->
                    point.time != null && point.time!!.isAfter(cutoff)
                }
            }
            if (firstIndex < 0) {
                trimmedPoints += file.points.size
                skippedSources.add(meta.item.name)
                continue
            }
            if (firstIndex > 0) trimmedPoints += firstIndex

            // Every non-empty source segment becomes its own <trkseg>. A
            // source file always starts a new segment even when its first
            // segment index repeats the previous file's index.
            var keptInSource = 0
            var currentSourceSegment = -1
            var currentSegment: Element? = null
            for (index in firstIndex until file.points.size) {
                val point = file.points[index]
                if (currentSegment == null || point.segmentIndex != currentSourceSegment) {
                    currentSegment = createElement(document, namespace, "trkseg")
                    track.appendChild(currentSegment)
                    currentSourceSegment = point.segmentIndex
                    segmentCount++
                    // Distance must not be summed across a segment break:
                    // there is no real path between the last point of one
                    // segment and the first point of the next one.
                    previousPoint = null
                }

                val element = if (position == 0) {
                    point.element
                } else {
                    val imported = document.importNode(point.element, true) as Element
                    ensurePointNamespaces(
                        file.document.documentElement,
                        imported,
                        root,
                        declaredNamespaces
                    )
                    imported
                }
                removeStaleDistance(element)
                currentSegment.appendChild(element)
                keptInSource++
                keptPoints++

                point.time?.let { time ->
                    if (firstTime == null || time.isBefore(firstTime)) firstTime = time
                    if (lastTime == null || time.isAfter(lastTime)) lastTime = time
                    if (cutoff == null || time.isAfter(cutoff)) cutoff = time
                }
                if (Geo.isValidCoordinate(point.lat, point.lon)) {
                    val previous = previousPoint
                    if (previous != null &&
                        Geo.isValidCoordinate(previous.lat, previous.lon)
                    ) {
                        totalDistance += Geo.distanceMeters(
                            previous.lat!!,
                            previous.lon!!,
                            point.lat!!,
                            point.lon!!
                        )
                    }
                    previousPoint = point
                }
            }
            if (keptInSource > 0) contributedSources++
        }

        if (keptPoints == 0) throw MergeException(MergeException.Reason.EMPTY_RESULT)

        metadataTime.textContent = (firstTime ?: baseMeta.info.startTime).toString()

        return MergeResult(
            outputBytes = GpxCodec.serialize(document),
            suggestedName = GpxCodec.suggestedName(baseMeta.item.name, "_merged"),
            inputSourceCount = items.size,
            contributedSources = contributedSources,
            totalPoints = keptPoints,
            trimmedPoints = trimmedPoints,
            skippedSources = skippedSources,
            startTime = firstTime,
            endTime = lastTime,
            distanceMeters = totalDistance,
            segmentCount = segmentCount
        )
    }

    private fun createElement(document: Document, namespace: String?, name: String): Element {
        return if (namespace.isNullOrBlank()) {
            document.createElement(name)
        } else {
            document.createElementNS(namespace, name)
        }
    }

    /**
     * Cumulative distance is source-local. After merging it is either wrong
     * (the next source starts from zero) or missing (FIT), so remove it and
     * let consumers calculate distance from the coordinates.
     */
    private fun removeStaleDistance(point: Element) {
        val children = point.childNodes
        for (index in children.length - 1 downTo 0) {
            val child = children.item(index)
            if (child !is Element || GpxCodec.localName(child) != "extensions") continue
            val extensionChildren = child.childNodes
            for (extensionIndex in extensionChildren.length - 1 downTo 0) {
                val extension = extensionChildren.item(extensionIndex)
                if (extension is Element &&
                    GpxCodec.localName(extension) == "distance"
                ) {
                    child.removeChild(extension)
                }
            }
            if (!child.hasChildNodes()) point.removeChild(child)
        }
    }

    private fun collectNamespaceDeclarations(
        element: Element,
        result: MutableMap<String, String>
    ) {
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index) as? Attr ?: continue
            val name = attribute.name
            val prefix = when {
                name == "xmlns" -> ""
                name.startsWith("xmlns:") -> name.substringAfter("xmlns:")
                else -> continue
            }
            result[prefix] = attribute.value
        }
    }

    private fun copyNamespaceDeclarations(
        sourceRoot: Element,
        targetRoot: Element,
        declared: MutableMap<String, String>
    ) {
        val sourceDeclarations = LinkedHashMap<String, String>()
        collectNamespaceDeclarations(sourceRoot, sourceDeclarations)
        for ((prefix, uri) in sourceDeclarations) {
            if (declared.containsKey(prefix)) continue
            targetRoot.setAttributeNS(XMLNS_NAMESPACE, namespaceAttribute(prefix), uri)
            declared[prefix] = uri
        }
    }

    private fun ensurePointNamespaces(
        sourceRoot: Element,
        imported: Element,
        targetRoot: Element,
        declared: MutableMap<String, String>
    ) {
        copyNamespaceDeclarations(sourceRoot, targetRoot, declared)

        // GPX 1.0 elements have no namespace. If the base document uses a
        // default GPX 1.1 namespace, keep the imported element semantically
        // unchanged by giving it an empty local default namespace.
        if (imported.namespaceURI.isNullOrEmpty() && !declared[""].isNullOrEmpty()) {
            imported.setAttributeNS(XMLNS_NAMESPACE, "xmlns", "")
        }

        val used = LinkedHashMap<String, String>()
        collectUsedNamespaces(imported, used)
        for ((prefix, uri) in used) {
            val current = declared[prefix]
            if (current == null) {
                targetRoot.setAttributeNS(XMLNS_NAMESPACE, namespaceAttribute(prefix), uri)
                declared[prefix] = uri
            } else if (current != uri) {
                imported.setAttributeNS(XMLNS_NAMESPACE, namespaceAttribute(prefix), uri)
            }
        }
    }

    private fun collectUsedNamespaces(element: Element, result: MutableMap<String, String>) {
        val namespace = element.namespaceURI
        if (!namespace.isNullOrEmpty()) {
            result[element.prefix ?: ""] = namespace
        }

        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index) as? Attr ?: continue
            val name = attribute.name
            if (name == "xmlns" || name.startsWith("xmlns:")) continue
            val namespaceUri = attribute.namespaceURI
            if (!namespaceUri.isNullOrEmpty()) {
                result[attribute.prefix ?: ""] = namespaceUri
            }
        }

        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) collectUsedNamespaces(child, result)
        }
    }

    private fun namespaceAttribute(prefix: String): String {
        return if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"
    }

    private fun baseName(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
        val base = if (clean.contains('.')) clean.substringBeforeLast('.') else clean
        return base.ifBlank { "track" }
    }
}
