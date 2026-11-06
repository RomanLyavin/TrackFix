package com.trackfix.app.core

import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6371008.8

    fun isValidCoordinate(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        if (!lat.isFinite() || !lon.isFinite()) return false
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return false
        // A lot of broken GPX files use 0/0 as a placeholder.
        return !(abs(lat) < 1e-7 && abs(lon) < 1e-7)
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(p1) * cos(p2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    fun speedMetersPerSecond(a: TrackPoint, b: TrackPoint): Double? {
        val ta = a.time ?: return null
        val tb = b.time ?: return null
        val seconds = Duration.between(ta, tb).toMillis() / 1000.0
        if (seconds <= 0.0) return null
        return distanceMeters(a.lat!!, a.lon!!, b.lat!!, b.lon!!) / seconds
    }

    fun formatDistance(meters: Double): String {
        return if (meters < 1000.0) {
            String.format(Locale("ru", "RU"), "%.0f м", meters)
        } else {
            String.format(Locale("ru", "RU"), "%.2f км", meters / 1000.0)
        }
    }

    fun formatDuration(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    fun formatDuration(points: List<TrackPoint>): String? {
        val first = points.firstOrNull { it.time != null }?.time ?: return null
        val last = points.lastOrNull { it.time != null }?.time ?: return null
        if (last.isBefore(first)) return null
        return formatDuration(Duration.between(first, last).seconds)
    }

    fun distanceOf(points: List<TrackPoint>): Double {
        var total = 0.0
        var previous: TrackPoint? = null
        for (point in points) {
            if (!point.hasCoordinates) continue
            val p = previous
            if (p != null && p.hasCoordinates) {
                total += distanceMeters(p.lat!!, p.lon!!, point.lat!!, point.lon!!)
            }
            previous = point
        }
        return total
    }

    /**
     * Projects a point onto a straight segment using a local equirectangular
     * approximation. Returns t in [0, 1] and the distance in meters.
     */
    fun projectOnSegment(
        lat: Double,
        lon: Double,
        aLat: Double,
        aLon: Double,
        bLat: Double,
        bLon: Double
    ): SegmentProjection {
        val midLat = (aLat + bLat) / 2.0
        val mPerDegLat = 111132.92 - 559.82 * cos(2 * Math.toRadians(midLat)) +
            1.175 * cos(4 * Math.toRadians(midLat)) - 0.0023 * cos(6 * Math.toRadians(midLat))
        val mPerDegLon = 111412.84 * cos(Math.toRadians(midLat)) -
            93.5 * cos(3 * Math.toRadians(midLat)) + 0.118 * cos(5 * Math.toRadians(midLat))

        val x = (lon - aLon) * mPerDegLon
        val y = (lat - aLat) * mPerDegLat
        val x2 = (bLon - aLon) * mPerDegLon
        val y2 = (bLat - aLat) * mPerDegLat
        val lengthSq = x2 * x2 + y2 * y2
        val t = if (lengthSq <= 1e-12) 0.0 else ((x * x2 + y * y2) / lengthSq).coerceIn(0.0, 1.0)
        val closestLat = aLat + (bLat - aLat) * t
        val closestLon = aLon + (bLon - aLon) * t
        val px = x - t * x2
        val py = y - t * y2
        return SegmentProjection(t = t, distance = hypot(px, py), lat = closestLat, lon = closestLon)
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }
}

data class SegmentProjection(
    val t: Double,
    val distance: Double,
    val lat: Double,
    val lon: Double
)

data class TrackPoint(
    val lat: Double?,
    val lon: Double?,
    val ele: Double?,
    val time: Instant?,
    val speedMetersPerSecond: Double? = null,
    val cumulativeDistanceMeters: Double? = null
) {
    val hasCoordinates: Boolean get() = Geo.isValidCoordinate(lat, lon)
}
