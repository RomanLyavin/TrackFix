package com.trackfix.app.core

import java.time.Duration
import java.time.Instant
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Rebuilds a track that has no GPS fixes at all.
 *
 * The reference route is traversed at a constant average speed: the whole
 * reference length divided by the moving time of the source track. Intervals
 * where both neighbouring source points are pauses (speed/cadence are zero)
 * do not advance the position, so pauses stay in place. Point count, time,
 * sensors and existing elevation are preserved.
 *
 * Long recording gaps are the exception: if a moving interval lasts for many
 * seconds and advances far along the reference, intermediate points are
 * inserted so the output follows the route instead of drawing a straight chord
 * across it. Inserted points carry only coordinates, time and optional
 * reference elevation; sensor values are never invented.
 */
object NoGpsReconstructor {
    private const val PAUSE_SPEED_METERS_PER_SECOND = 0.05
    private const val GAP_FILL_MIN_SECONDS = 15.0
    private const val GAP_FILL_MIN_METERS = 30.0
    private const val GAP_FILL_STEP_METERS = 10.0

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

        // Distribute the reference length in proportion to the distance the
        // recorder actually travelled, not uniformly over time. A cumulative
        // distance field is the most accurate source; speed is the fallback.
        val fallbackSpeed = SourceDistance.medianSpeed(input)
            ?.takeIf { it > 0.0 } ?: 1.0
        val intervalMeters = SourceDistance.intervalMeters(input, fallbackSpeed)
        val movingWeights = DoubleArray(input.size)
        var totalWeight = 0.0
        for (index in 1 until input.size) {
            val seconds = secondsBetween(input[index - 1].time, input[index].time)
                ?: continue
            if (seconds <= 0.0) continue
            val moving = !(isPause(input[index - 1]) && isPause(input[index]))
            if (!moving) continue
            val weight = intervalMeters[index].takeIf { it > 0.0 }
                ?: (seconds * fallbackSpeed)
            movingWeights[index] = weight
            totalWeight += weight
        }
        if (totalWeight <= 0.0) {
            // No usable speed or distance data: fall back to moving time.
            for (index in 1 until input.size) {
                val seconds = secondsBetween(input[index - 1].time, input[index].time)
                    ?: continue
                if (seconds <= 0.0) continue
                if (isPause(input[index - 1]) && isPause(input[index])) continue
                movingWeights[index] = seconds
                totalWeight += seconds
            }
        }
        if (totalWeight <= 0.0) throw GpxException("track has no moving intervals")
        val metersPerWeight = path.totalLength / totalWeight

        val n = input.size
        val lat = arrayOfNulls<Double>(n)
        val lon = arrayOfNulls<Double>(n)
        val ele = Array<Double?>(n) { input[it].ele }
        val coordinateChanged = BooleanArray(n) { true }
        val elevationFilled = BooleanArray(n)
        val inserted = mutableListOf<InsertedPoint>()
        val warnings = mutableSetOf<FixWarning>(FixWarning.NO_GPS_RECONSTRUCTED)
        if (reference.paths.size > 1) warnings.add(FixWarning.REFERENCE_SEGMENTS)

        var s = 0.0
        apply(path, 0, s, ele, lat, lon, elevationFilled, fillMissingElevation)
        for (index in 1 until n) {
            val seconds = secondsBetween(input[index - 1].time, input[index].time)
            val moving = !(isPause(input[index - 1]) && isPause(input[index]))
            val previousS = s
            if (moving && seconds != null && seconds > 0.0) {
                s = (s + movingWeights[index] * metersPerWeight)
                    .coerceAtMost(path.totalLength)
            }
            if (moving && seconds != null) {
                addGapPoints(
                    path = path,
                    previous = input[index - 1],
                    next = input[index],
                    afterIndex = index - 1,
                    seconds = seconds,
                    startS = previousS,
                    endS = s,
                    fillElevation = fillMissingElevation,
                    output = inserted
                )
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

        val distanceAfter = distanceWithInserted(lat, lon, inserted)
        return FixResult(
            lat = lat,
            lon = lon,
            ele = ele,
            coordinateChanged = coordinateChanged,
            elevationFilled = elevationFilled,
            stats = FixStats(
                totalPoints = n + inserted.size,
                fixedCoordinates = n,
                missingCoordinates = n,
                filledElevations = elevationFilled.count { it },
                problemBlocks = 1,
                unresolvedPoints = 0,
                maxDeviationBefore = 0.0,
                maxDeviationCapped = false,
                maxJumpBefore = 0.0,
                distanceBefore = Geo.distanceOf(input),
                distanceAfter = distanceAfter,
                speedLimit = averageSpeed,
                reconstructedCoordinates = n,
                insertedPoints = inserted.size
            ),
            warnings = warnings,
            inserted = inserted
        )
    }

    /**
     * Adds intermediate points along the reference inside one long moving
     * interval. The step is measured on the reference, and time is
     * interpolated linearly between the two original points.
     */
    private fun addGapPoints(
        path: RefPath,
        previous: TrackPoint,
        next: TrackPoint,
        afterIndex: Int,
        seconds: Double,
        startS: Double,
        endS: Double,
        fillElevation: Boolean,
        output: MutableList<InsertedPoint>
    ) {
        if (seconds < GAP_FILL_MIN_SECONDS) return
        val delta = endS - startS
        if (delta < GAP_FILL_MIN_METERS) return
        val startTime = previous.time ?: return
        val endTime = next.time ?: return
        val totalMillis = Duration.between(startTime, endTime).toMillis()
        if (totalMillis <= 1L) return

        val count = floor(delta / GAP_FILL_STEP_METERS).toInt()
        for (stepIndex in 1..count) {
            val offset = stepIndex * GAP_FILL_STEP_METERS
            if (offset >= delta - 0.05) break
            val fraction = offset / delta
            val vertex = path.pointAt(startS + offset, wrap = false)
            val millis = (totalMillis * fraction).roundToLong()
                .coerceIn(1L, totalMillis - 1L)
            output.add(
                InsertedPoint(
                    afterIndex = afterIndex,
                    lat = vertex.lat,
                    lon = vertex.lon,
                    ele = if (fillElevation) vertex.ele else null,
                    time = startTime.plusMillis(millis)
                )
            )
        }
    }

    private fun distanceWithInserted(
        lat: Array<Double?>,
        lon: Array<Double?>,
        inserted: List<InsertedPoint>
    ): Double {
        if (inserted.isEmpty()) {
            return Geo.distanceOf(
                lat.indices.map { TrackPoint(lat[it], lon[it], null, null) }
            )
        }
        val byAfter = inserted.groupBy { it.afterIndex }
        var total = 0.0
        var previousLat: Double? = null
        var previousLon: Double? = null

        fun add(latValue: Double?, lonValue: Double?) {
            if (latValue == null || lonValue == null) return
            val oldLat = previousLat
            val oldLon = previousLon
            if (oldLat != null && oldLon != null) {
                total += Geo.distanceMeters(oldLat, oldLon, latValue, lonValue)
            }
            previousLat = latValue
            previousLon = lonValue
        }

        for (index in lat.indices) {
            add(lat[index], lon[index])
            byAfter[index]?.forEach { add(it.lat, it.lon) }
        }
        return total
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
