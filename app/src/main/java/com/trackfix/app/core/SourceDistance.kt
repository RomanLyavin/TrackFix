package com.trackfix.app.core

import java.time.Duration
import java.time.Instant

/**
 * Estimates how far the recorder actually travelled between source points.
 *
 * The source often contains more reliable movement data than the GPS
 * coordinates: a cumulative `<distance>` field, a speed extension, or both.
 * The estimate is used to distribute the reference route in proportion to the
 * source effort instead of spreading it uniformly over time. This keeps
 * intervals (fast/slow blocks) in the correct proportion.
 *
 * Priority for every interval:
 * 1. the delta of the cumulative distance when it is monotonic and plausible;
 * 2. speed integrated over the interval (trapezoid of the two endpoints);
 * 3. the nearest known speed rate when one or both endpoints are missing;
 * 4. the supplied fallback speed, which turns the result into a time-based
 *    distribution when the file has no speed or distance data at all.
 */
object SourceDistance {
    private const val MAX_INTERVAL_METERS = 5000.0

    /**
     * A cumulative-distance field or a speed field can contain a bad spike at
     * the very start of a recording. Neither must be trusted as travelled
     * distance: on the 10 November track the first two seconds claimed 180 m
     * (90 m/s), which pushed the leading repair more than 200 m away from the
     * start line. 35 m/s is above any realistic running or cycling speed.
     */
    private const val MAX_PLAUSIBLE_SPEED = 35.0

    fun medianSpeed(points: List<TrackPoint>): Double? {
        val speeds = points.mapNotNull { validSpeed(it.speedMetersPerSecond) }.sorted()
        if (speeds.isEmpty()) return null
        val middle = speeds.size / 2
        return if (speeds.size % 2 == 1) {
            speeds[middle]
        } else {
            (speeds[middle - 1] + speeds[middle]) / 2.0
        }
    }

    /**
     * Distance for every interval. Index `i` is the distance between points
     * `i - 1` and `i`; index zero is always zero.
     */
    fun intervalMeters(points: List<TrackPoint>, fallbackSpeed: Double): DoubleArray {
        val result = DoubleArray(points.size)
        if (points.size < 2) return result

        val seconds = DoubleArray(points.size)
        val raw = DoubleArray(points.size)
        val hasRaw = BooleanArray(points.size)

        for (index in 1 until points.size) {
            val intervalSeconds = secondsBetween(
                points[index - 1].time,
                points[index].time
            ) ?: continue
            if (intervalSeconds <= 0.0) continue
            seconds[index] = intervalSeconds

            val startDistance = points[index - 1].cumulativeDistanceMeters
            val endDistance = points[index].cumulativeDistanceMeters
            if (startDistance != null && endDistance != null) {
                val delta = endDistance - startDistance
                if (delta >= 0.0 &&
                    delta <= MAX_INTERVAL_METERS &&
                    delta <= intervalSeconds * MAX_PLAUSIBLE_SPEED
                ) {
                    raw[index] = delta
                    hasRaw[index] = true
                    continue
                }
            }

            val startSpeed = validSpeed(points[index - 1].speedMetersPerSecond)
            val endSpeed = validSpeed(points[index].speedMetersPerSecond)
            val speed = when {
                startSpeed != null && endSpeed != null -> (startSpeed + endSpeed) / 2.0
                startSpeed != null -> startSpeed
                endSpeed != null -> endSpeed
                else -> null
            }
            if (speed != null && speed <= MAX_PLAUSIBLE_SPEED) {
                raw[index] = speed * intervalSeconds
                hasRaw[index] = true
            }
        }

        // A missing speed must not look like a stop. Fill gaps with the rate of
        // the nearest interval that has real data, preferring the closer one.
        for (index in 1 until points.size) {
            if (hasRaw[index] || seconds[index] <= 0.0) continue
            var previous = -1
            for (candidate in index - 1 downTo 1) {
                if (hasRaw[candidate] && seconds[candidate] > 0.0) {
                    previous = candidate
                    break
                }
            }
            var next = -1
            for (candidate in index + 1 until points.size) {
                if (hasRaw[candidate] && seconds[candidate] > 0.0) {
                    next = candidate
                    break
                }
            }
            val rate = when {
                previous >= 0 && next >= 0 -> {
                    if (index - previous <= next - index) {
                        raw[previous] / seconds[previous]
                    } else {
                        raw[next] / seconds[next]
                    }
                }
                previous >= 0 -> raw[previous] / seconds[previous]
                next >= 0 -> raw[next] / seconds[next]
                else -> fallbackSpeed
            }
            raw[index] = rate * seconds[index]
        }

        for (index in 1 until points.size) {
            result[index] = if (seconds[index] > 0.0) {
                raw[index].coerceAtLeast(0.0)
            } else {
                0.0
            }
        }
        return result
    }

    fun prefixMeters(points: List<TrackPoint>, fallbackSpeed: Double): DoubleArray {
        val intervals = intervalMeters(points, fallbackSpeed)
        val prefix = DoubleArray(points.size)
        for (index in 1 until points.size) {
            prefix[index] = prefix[index - 1] + intervals[index]
        }
        return prefix
    }

    fun validSpeed(speed: Double?): Double? =
        speed?.takeIf { it.isFinite() && it >= 0.0 }

    fun secondsBetween(first: Instant?, second: Instant?): Double? {
        if (first == null || second == null) return null
        return Duration.between(first, second).toMillis() / 1000.0
    }
}
