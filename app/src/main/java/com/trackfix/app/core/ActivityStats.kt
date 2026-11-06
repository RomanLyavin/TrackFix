package com.trackfix.app.core

import org.w3c.dom.Element
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/**
 * The metrics that can be placed on a photo. The order is also the order in
 * which the checkboxes and the resulting overlay are shown.
 */
enum class ShareMetric {
    MOVING_TIME,
    DISTANCE,
    AVERAGE_PACE,
    AVERAGE_SPEED,
    AVERAGE_HEART_RATE,
    AVERAGE_POWER,
    TOTAL_ASCENT
}

data class ActivityStats(
    val pointCount: Int,
    val startTime: Instant?,
    val endTime: Instant?,
    val movingTimeSeconds: Long?,
    val distanceMeters: Double,
    val averagePaceSecondsPerKm: Long?,
    val averageSpeedKmh: Double?,
    val averageHeartRate: Double?,
    val averagePowerWatts: Double?,
    val totalAscentMeters: Double?
) {
    fun supports(metric: ShareMetric): Boolean {
        return when (metric) {
            ShareMetric.MOVING_TIME -> movingTimeSeconds != null && movingTimeSeconds > 0
            ShareMetric.DISTANCE -> distanceMeters > 0.0
            ShareMetric.AVERAGE_PACE -> averagePaceSecondsPerKm != null
            ShareMetric.AVERAGE_SPEED -> averageSpeedKmh != null
            ShareMetric.AVERAGE_HEART_RATE -> averageHeartRate != null
            ShareMetric.AVERAGE_POWER -> averagePowerWatts != null
            ShareMetric.TOTAL_ASCENT -> totalAscentMeters != null
        }
    }

    fun availableMetrics(): List<ShareMetric> = ShareMetric.values().filter { supports(it) }
}

/**
 * Calculates the summary of an already parsed GPX/FIT track.
 *
 * Moving time is the sum of short intervals in which the track was actually
 * moving. Long gaps (auto-pause, stops, a break between merged recordings)
 * are not counted, and the connector between two track segments is never
 * counted as distance or time.
 */
object ActivityStatsCalculator {

    /** Intervals longer than this are treated as a pause. */
    const val MAX_MOVING_GAP_SECONDS = 30L

    /** Slower intervals are treated as standing still. */
    const val MIN_MOVING_SPEED_MPS = 0.5

    /** A minimum distance is required before pace and speed become useful. */
    const val MIN_DISTANCE_FOR_PACE_METERS = 100.0

    /** Elevation fluctuations below this value are treated as noise. */
    const val ASCENT_NOISE_THRESHOLD_METERS = 2.0

    fun calculate(file: GpxFile): ActivityStats {
        val points = file.points
        var distance = 0.0
        var movingMillis = 0L
        var previous: GpxPoint? = null

        for (point in points) {
            val prev = previous
            if (prev != null && prev.segmentIndex == point.segmentIndex) {
                val hasCoordinates = Geo.isValidCoordinate(prev.lat, prev.lon) &&
                    Geo.isValidCoordinate(point.lat, point.lon)
                val stepDistance = if (hasCoordinates) {
                    Geo.distanceMeters(
                        prev.lat!!,
                        prev.lon!!,
                        point.lat!!,
                        point.lon!!
                    )
                } else {
                    null
                }
                if (stepDistance != null) distance += stepDistance

                val previousTime = prev.time
                val currentTime = point.time
                if (previousTime != null && currentTime != null) {
                    val deltaMillis = Duration.between(previousTime, currentTime).toMillis()
                    if (deltaMillis > 0L &&
                        deltaMillis <= MAX_MOVING_GAP_SECONDS * 1000L
                    ) {
                        val moving = if (stepDistance == null) {
                            // No coordinates to judge by, but the interval is
                            // short enough to be a normal recording interval.
                            true
                        } else {
                            val seconds = deltaMillis / 1000.0
                            stepDistance / seconds >= MIN_MOVING_SPEED_MPS
                        }
                        if (moving) movingMillis += deltaMillis
                    }
                }
            }
            previous = point
        }

        val movingSeconds = if (movingMillis > 0L) {
            (movingMillis / 1000.0).roundToLong()
        } else {
            null
        }

        val startTime = points.firstOrNull { it.time != null }?.time
        val endTime = points.lastOrNull { it.time != null }?.time

        val averageSpeed = if (movingSeconds != null && movingSeconds > 0L &&
            distance > 0.0
        ) {
            distance / movingSeconds * 3.6
        } else {
            null
        }

        val averagePace = if (movingSeconds != null && movingSeconds > 0L &&
            distance >= MIN_DISTANCE_FOR_PACE_METERS
        ) {
            (movingSeconds / (distance / 1000.0)).roundToLong().coerceAtLeast(1L)
        } else {
            null
        }

        val heartRates = ArrayList<Double>()
        val powers = ArrayList<Double>()
        for (point in points) {
            sensorValue(point.element, HEART_RATE_NAMES)?.let { value ->
                if (value > 0.0) heartRates.add(value)
            }
            sensorValue(point.element, POWER_NAMES)?.let { value ->
                if (value >= 0.0) powers.add(value)
            }
        }

        val averageHeartRate = if (heartRates.isNotEmpty()) {
            heartRates.average()
        } else {
            null
        }
        val averagePower = if (powers.isNotEmpty() && powers.any { it > 0.0 }) {
            powers.average()
        } else {
            null
        }

        return ActivityStats(
            pointCount = points.size,
            startTime = startTime,
            endTime = endTime,
            movingTimeSeconds = movingSeconds,
            distanceMeters = distance,
            averagePaceSecondsPerKm = averagePace,
            averageSpeedKmh = averageSpeed,
            averageHeartRate = averageHeartRate,
            averagePowerWatts = averagePower,
            totalAscentMeters = totalAscent(points)
        )
    }

    private fun totalAscent(points: List<GpxPoint>): Double? {
        var hasElevation = false
        var total = 0.0
        var reference: Double? = null
        var previousSegment = -1

        for (point in points) {
            if (point.segmentIndex != previousSegment) {
                reference = null
                previousSegment = point.segmentIndex
            }
            val elevation = point.ele ?: continue
            hasElevation = true
            val currentReference = reference
            if (currentReference == null) {
                reference = elevation
                continue
            }
            val difference = elevation - currentReference
            if (difference >= ASCENT_NOISE_THRESHOLD_METERS) {
                total += difference
                reference = elevation
            } else if (difference <= -ASCENT_NOISE_THRESHOLD_METERS) {
                reference = elevation
            }
        }

        return if (hasElevation) total else null
    }

    private fun sensorValue(element: Element, names: List<String>): Double? {
        for (name in names) {
            val namespaced = element.getElementsByTagNameNS("*", name)
            for (index in 0 until namespaced.length) {
                GpxCodec.parseDoubleOrNull(namespaced.item(index).textContent)?.let {
                    return it
                }
            }
            val plain = element.getElementsByTagName(name)
            for (index in 0 until plain.length) {
                GpxCodec.parseDoubleOrNull(plain.item(index).textContent)?.let {
                    return it
                }
            }
        }
        return null
    }

    private val HEART_RATE_NAMES = listOf("hr", "heartrate", "heart_rate")
    private val POWER_NAMES = listOf("power", "watts", "watt")
}
