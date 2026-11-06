package com.trackfix.app.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class RefVertex(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null
)

data class Projection(
    val pathIndex: Int,
    val s: Double,
    val distance: Double,
    val lat: Double,
    val lon: Double,
    val ele: Double?
)

class RefPath internal constructor(rawVertices: List<RefVertex>) {
    val vertices: List<RefVertex>
    val cumulative: DoubleArray
    val totalLength: Double
    val closed: Boolean
    private val grid: SegmentGrid

    init {
        val cleaned = ArrayList<RefVertex>(rawVertices.size)
        for (vertex in rawVertices) {
            val previous = cleaned.lastOrNull()
            if (previous == null ||
                Geo.distanceMeters(previous.lat, previous.lon, vertex.lat, vertex.lon) > 0.2
            ) {
                cleaned.add(vertex)
            }
        }
        vertices = cleaned
        cumulative = DoubleArray(vertices.size)
        var total = 0.0
        for (i in 1 until vertices.size) {
            val a = vertices[i - 1]
            val b = vertices[i]
            total += Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon)
            cumulative[i] = total
        }
        totalLength = total
        closed = vertices.size > 2 &&
            Geo.distanceMeters(
                vertices.first().lat,
                vertices.first().lon,
                vertices.last().lat,
                vertices.last().lon
            ) <= 30.0 && totalLength > 200.0
        grid = SegmentGrid(vertices)
    }

    fun projectionFromSegment(segmentIndex: Int, t: Double, distance: Double, lat: Double, lon: Double): Projection {
        val s = cumulative[segmentIndex] +
            t * (cumulative[segmentIndex + 1] - cumulative[segmentIndex])
        return Projection(
            pathIndex = -1,
            s = s,
            distance = distance,
            lat = lat,
            lon = lon,
            ele = elevationAt(s)
        )
    }

    fun candidates(lat: Double, lon: Double, maxDistance: Double): List<Projection> {
        val segmentIndices = grid.query(lat, lon, maxDistance)
        if (segmentIndices.isEmpty()) return emptyList()
        val result = ArrayList<Projection>()
        for (index in segmentIndices) {
            val a = vertices[index]
            val b = vertices[index + 1]
            val projection = Geo.projectOnSegment(lat, lon, a.lat, a.lon, b.lat, b.lon)
            if (projection.distance <= maxDistance) {
                val s = cumulative[index] + projection.t * (cumulative[index + 1] - cumulative[index])
                result.add(
                    Projection(
                        pathIndex = -1,
                        s = s,
                        distance = projection.distance,
                        lat = projection.lat,
                        lon = projection.lon,
                        ele = elevationAt(s)
                    )
                )
            }
        }
        return cluster(result)
    }

    private fun cluster(projections: List<Projection>): List<Projection> {
        if (projections.size <= 1) return projections
        val sorted = projections.sortedBy { it.s }
        val clusters = ArrayList<Projection>()
        for (projection in sorted) {
            val previous = clusters.lastOrNull()
            if (previous != null && abs(previous.s - projection.s) < 10.0) {
                if (projection.distance < previous.distance) {
                    clusters[clusters.lastIndex] = projection
                }
            } else {
                clusters.add(projection)
            }
        }
        return clusters
    }

    fun pointAt(s: Double, wrap: Boolean = true): RefVertex {
        if (vertices.isEmpty()) return RefVertex(0.0, 0.0, null)
        if (vertices.size == 1 || totalLength <= 1e-9) return vertices[0]

        var position = s
        if (closed && wrap) {
            position = ((position % totalLength) + totalLength) % totalLength
        } else {
            position = position.coerceIn(0.0, totalLength)
        }

        var low = 0
        var high = vertices.size - 1
        while (low + 1 < high) {
            val middle = (low + high) / 2
            if (cumulative[middle] <= position) low = middle else high = middle
        }
        val start = cumulative[low]
        val end = cumulative[low + 1]
        val t = if (end - start <= 1e-9) 0.0 else ((position - start) / (end - start)).coerceIn(0.0, 1.0)
        val a = vertices[low]
        val b = vertices[low + 1]
        return RefVertex(
            lat = a.lat + (b.lat - a.lat) * t,
            lon = a.lon + (b.lon - a.lon) * t,
            ele = when {
                a.ele != null && b.ele != null -> a.ele + (b.ele - a.ele) * t
                a.ele != null -> a.ele
                else -> b.ele
            }
        )
    }

    fun forwardLength(sA: Double, sB: Double): Double? {
        if (!closed && sB < sA) return null
        return if (closed) {
            ((sB - sA) % totalLength + totalLength) % totalLength
        } else {
            sB - sA
        }
    }

    fun backwardLength(sA: Double, sB: Double): Double? {
        if (!closed && sB > sA) return null
        return if (closed) {
            ((sA - sB) % totalLength + totalLength) % totalLength
        } else {
            sA - sB
        }
    }

    private fun elevationAt(s: Double): Double? {
        val vertex = pointAt(s, wrap = closed)
        return vertex.ele
    }

    private class SegmentGrid(private val vertices: List<RefVertex>) {
        private val cellSize = 100.0
        private val minLat = vertices.minOf { it.lat }
        private val minLon = vertices.minOf { it.lon }
        private val meanLat = vertices.sumOf { it.lat } / vertices.size
        private val metersPerDegreeLat = 111320.0
        private val metersPerDegreeLon = max(1.0, 111320.0 * cos(Math.toRadians(meanLat)))
        private val cells = HashMap<Long, MutableList<Int>>()

        init {
            for (index in 0 until vertices.size - 1) {
                val a = vertices[index]
                val b = vertices[index + 1]
                val ax = x(a.lon)
                val ay = y(a.lat)
                val bx = x(b.lon)
                val by = y(b.lat)
                val minCellX = floor(min(ax, bx) / cellSize).toInt()
                val maxCellX = floor(max(ax, bx) / cellSize).toInt()
                val minCellY = floor(min(ay, by) / cellSize).toInt()
                val maxCellY = floor(max(ay, by) / cellSize).toInt()
                for (cx in minCellX..maxCellX) {
                    for (cy in minCellY..maxCellY) {
                        cells.getOrPut(key(cx, cy)) { mutableListOf() }.add(index)
                    }
                }
            }
        }

        fun query(lat: Double, lon: Double, radius: Double): Set<Int> {
            val px = x(lon)
            val py = y(lat)
            val minCellX = floor((px - radius) / cellSize).toInt()
            val maxCellX = floor((px + radius) / cellSize).toInt()
            val minCellY = floor((py - radius) / cellSize).toInt()
            val maxCellY = floor((py + radius) / cellSize).toInt()
            val result = HashSet<Int>()
            for (cx in minCellX..maxCellX) {
                for (cy in minCellY..maxCellY) {
                    cells[key(cx, cy)]?.let { result.addAll(it) }
                }
            }
            return result
        }

        private fun x(lon: Double): Double = (lon - minLon) * metersPerDegreeLon
        private fun y(lat: Double): Double = (lat - minLat) * metersPerDegreeLat

        private fun key(cx: Int, cy: Int): Long {
            return (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        }
    }
}

class ReferencePath(paths: List<List<RefVertex>>) {
    val paths: List<RefPath> = paths
        .map { RefPath(it) }
        .filter { it.vertices.size >= 2 }
    val totalLength: Double = this.paths.sumOf { it.totalLength }
    val segmentCount: Int = this.paths.sumOf { it.vertices.size - 1 }

    init {
        if (this.paths.isEmpty()) throw IllegalArgumentException("reference has no usable points")
    }

    fun nearest(lat: Double, lon: Double, maxDistance: Double): Projection? {
        var best: Projection? = null
        for (pathIndex in paths.indices) {
            val candidates = paths[pathIndex].candidates(lat, lon, maxDistance)
            for (candidate in candidates) {
                val withPath = candidate.copy(pathIndex = pathIndex)
                if (best == null || withPath.distance < best.distance) best = withPath
            }
        }
        return best
    }

    fun nearestCandidates(
        lat: Double,
        lon: Double,
        maxDistance: Double,
        maxCandidates: Int = 12
    ): List<Projection> {
        val all = ArrayList<Projection>()
        for (pathIndex in paths.indices) {
            val candidates = paths[pathIndex].candidates(lat, lon, maxDistance)
            for (candidate in candidates) {
                all.add(candidate.copy(pathIndex = pathIndex))
            }
        }
        if (all.isEmpty()) return emptyList()
        val sorted = all.sortedBy { it.distance }
        val result = ArrayList<Projection>()
        for (candidate in sorted) {
            if (result.any {
                    it.pathIndex == candidate.pathIndex && abs(it.s - candidate.s) < 12.0
                }
            ) {
                continue
            }
            result.add(candidate)
            if (result.size >= maxCandidates) break
        }
        return result
    }

    fun pointAt(pathIndex: Int, s: Double, wrap: Boolean = true): RefVertex {
        return paths[pathIndex].pointAt(s, wrap)
    }
}
