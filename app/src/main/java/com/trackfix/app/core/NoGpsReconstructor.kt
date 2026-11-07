package com.trackfix.app.core

import java.time.Duration
import java.time.Instant

/**
 * Rebuilds a track that has no GPS fixes at all.
 *
 * The reference route is traversed at a constant average speed: the whole
 * reference length divided by the moving time of the source track. Intervals
 * where both neighbouring source points are pauses (speed/cadence are zero)
 * do not advance the position, so pauses stay in place. Point count, time,
 * sensors and existing elevation are preserved.
 */
object NoGpsReconstructor {
    private const val PAUSE_SPEED_METERS_PER_SECOND = 0.05

    fun reconstruct(
        input: List<TrackPoint>,
        reference: ReferencePath,
        fillMissingElevation: Boolean
    ): FixResult {
        if (input.size < 2) throw GpxException("track is too short")
        val path = reference.paths.maxByOrNull { it.totalLength }
            ?: throw GpxException("reference has no usable path")
        if (path.totalLength <= 1.0) throw GpxException("reference is too short")

        val movingSeconds = movingSeconds(input)
        if (movingSeconds <= 0.0) throw GpxException("track has no moving intervals")
        val averageSpeed = path.totalLength / movingSeconds

        val n = input.size
        val lat = arrayOfNulls<Double>(n)
        val lon = arrayOfNulls<Double>(n)
        val ele = Array<Double?>(n) { input[it].ele }
        val coordinateChanged = BooleanArray(n) { true }
        val elevationFilled = BooleanArray(n)
        val warnings = mutableSetOf(FixWarning.NO_GPS_RECONSTRUCTED)
        if (reference.paths.size > 1) warnings.add(FixWarning.REFERENCE_SEGMENTS)

        var s = 0.0
        apply(path, 0, s, ele, lat, lon, elevationFilled, fillMissingElevation)
        for (index in 1 until n) {
            val seconds = secondsBetween(input[index - 1].time, input[index].time)
            val moving = !(isPause(input[index - 1]) && isPause(input[index]))
            if (moving && seconds != null && seconds > 0.0) {
                s = (s + averageSpeed * seconds).coerceAtMost(path.totalLength)
            }
            apply(
                path,
                index,
                s,
                ele,
                lat,
                lon,
                elevationFilled,
                fillMissingElevation
            )
        }

        return FixResult(
            lat = lat,
            lon = lon,
            ele = ele,
            coordinateChanged = coordinateChanged,
            elevationFilled = elevationFilled,
            stats = FixStats(
                totalPoints = n,
                fixedCoordinates = n,
                missingCoordinates = n,
                filledElevations = elevationFilled.count { it },
                problemBlocks = 1,
                unresolvedPoints = 0,
                maxDeviationBefore = 0.0,
                maxDeviationCapped = false,
                maxJumpBefore = 0.0,
                distanceBefore = Geo.distanceOf(input),
                distanceAfter = path.totalLength,
                speedLimit = averageSpeed,
                reconstructedCoordinates = n
            ),
            warnings = warnings
        )
    }

    private fun apply(
        path: RefPath,
        index: Int,
        s: Double,
        ele: Array<Double?>,
        lat: Array<Double?>,
        lon: Array<Double?>,
        elevationFilled: BooleanArray,
        fillMissingElevation: Boolean
    ) {
        val vertex = path.pointAt(s, wrap = false)
        lat[index] = vertex.lat
        lon[index] = vertex.lon
        if (fillMissingElevation && ele[index] == null && vertex.ele != null) {
            ele[index] = vertex.ele
            elevationFilled[index] = true
        }
    }

    private fun movingSeconds(input: List<TrackPoint>): Double {
        var total = 0.0
        for (index in 1 until input.size) {
            val seconds = secondsBetween(input[index - 1].time, input[index].time) ?: continue
            if (seconds <= 0.0) continue
            if (!isPause(input[index - 1]) || !isPause(input[index])) {
                total += seconds
            }
        }
        return total
    }

    private fun isPause(point: TrackPoint): Boolean {
        val speed = point.speedMetersPerSecond
        if (speed != null && speed.isFinite()) {
            return speed <= PAUSE_SPEED_METERS_PER_SECOND
        }
        val cadence = point.cadence
        return cadence != null && cadence <= 0
    }

    private fun secondsBetween(first: Instant?, second: Instant?): Double? {
        if (first == null || second == null) return null
        return Duration.between(first, second).toMillis() / 1000.0
    }
}
