package com.trackfix.app.core

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val FROZEN_MAX_MOVE_METERS = 0.5
private const val FROZEN_MIN_SENSOR_DISTANCE_METERS = 20.0

data class FixSettings(
    val maxDeviationMeters: Double = 30.0,
    val fillMissingElevation: Boolean = true
)

enum class FixWarning {
    LARGE_CHANGES,
    NO_REFERENCE_COVERAGE,
    REFERENCE_SEGMENTS,
    NOTHING_TO_FIX
}

data class FixStats(
    val totalPoints: Int,
    val fixedCoordinates: Int,
    val missingCoordinates: Int,
    val filledElevations: Int,
    val problemBlocks: Int,
    val unresolvedPoints: Int,
    val maxDeviationBefore: Double,
    val maxDeviationCapped: Boolean,
    val maxJumpBefore: Double,
    val distanceBefore: Double,
    val distanceAfter: Double,
    val speedLimit: Double,
    val frozenRuns: Int = 0,
    val frozenCoordinates: Int = 0,
    val leadingFixed: Int = 0,
    val trailingFixed: Int = 0
)

class FixResult(
    val lat: Array<Double?>,
    val lon: Array<Double?>,
    val ele: Array<Double?>,
    val coordinateChanged: BooleanArray,
    val elevationFilled: BooleanArray,
    val stats: FixStats,
    val warnings: Set<FixWarning>
)

private data class PathChoice(
    val pathIndex: Int,
    val direction: Int,
    val length: Double,
    val start: Projection,
    val end: Projection
)

class TrackFixer(
    private val reference: ReferencePath,
    private val settings: FixSettings
) {
    fun fix(input: List<TrackPoint>): FixResult {
        val n = input.size
        val lat = Array<Double?>(n) { input[it].lat }
        val lon = Array<Double?>(n) { input[it].lon }
        val ele = Array<Double?>(n) { input[it].ele }
        val coordinateChanged = BooleanArray(n)
        val elevationFilled = BooleanArray(n)
        val warnings = mutableSetOf<FixWarning>()

        if (n == 0) {
            return FixResult(
                lat = lat,
                lon = lon,
                ele = ele,
                coordinateChanged = coordinateChanged,
                elevationFilled = elevationFilled,
                stats = FixStats(0, 0, 0, 0, 0, 0, 0.0, false, 0.0, 0.0, 0.0, 0.0),
                warnings = setOf(FixWarning.NOTHING_TO_FIX)
            )
        }

        val valid = BooleanArray(n) { input[it].hasCoordinates }
        val missing = BooleanArray(n) { !valid[it] }
        val threshold = settings.maxDeviationMeters.coerceIn(1.0, 500.0)
        val searchRadius = max(300.0, threshold * 4.0)
        val distanceBefore = distanceOf(lat, lon)
        val maxJumpBefore = maxConsecutiveDistance(lat, lon, valid)

        val nearest = arrayOfNulls<Projection>(n)
        val deviation = DoubleArray(n) { Double.POSITIVE_INFINITY }
        for (i in 0 until n) {
            if (!valid[i]) continue
            val projection = reference.nearest(lat[i]!!, lon[i]!!, searchRadius)
            nearest[i] = projection
            if (projection != null) deviation[i] = projection.distance
        }

        val speeds = ArrayList<Double>()
        val spacings = ArrayList<Double>()
        for (i in 1 until n) {
            if (!valid[i - 1] || !valid[i]) continue
            val distance = Geo.distanceMeters(
                lat[i - 1]!!, lon[i - 1]!!, lat[i]!!, lon[i]!!
            )
            if (distance < 5000.0) {
                spacings.add(distance)
                val seconds = secondsBetween(input[i - 1].time, input[i].time)
                if (seconds != null && seconds > 0.0 && distance > 0.5) {
                    val speed = distance / seconds
                    if (speed < 60.0) speeds.add(speed)
                }
            }
        }
        val medianSpeed = Geo.median(speeds) ?: 3.0
        val speedLimit = (medianSpeed * 3.0).coerceIn(10.0, 45.0)
        val medianSpacing = Geo.median(spacings.filter { it > 0.5 }) ?: 5.0

        val suspicious = BooleanArray(n)
        for (i in 1 until n) {
            if (!valid[i - 1] || !valid[i]) continue
            val distance = Geo.distanceMeters(
                lat[i - 1]!!, lon[i - 1]!!, lat[i]!!, lon[i]!!
            )
            if (distance < 10.0) continue
            val seconds = secondsBetween(input[i - 1].time, input[i].time)
            if (seconds == null || seconds <= 0.0) {
                markFartherEndpoint(i - 1, i, deviation, suspicious)
                continue
            }
            val speed = distance / seconds
            if (speed > speedLimit) {
                markFartherEndpoint(i - 1, i, deviation, suspicious)
            }
        }

        val far = BooleanArray(n) { valid[it] && deviation[it] > threshold }
        val frozenRanges = detectFrozenRuns(input, valid, nearest)
        val frozenFlag = BooleanArray(n)
        for (range in frozenRanges) {
            for (index in range) frozenFlag[index] = true
        }
        val bad = BooleanArray(n)

        for (i in 0 until n) {
            if (missing[i]) bad[i] = true
            // A speed spike alone is normal GPS noise when the point still lies
            // on the reference. Only repair it when the coordinate is also far
            // away, otherwise clean tracks get nudged by a few metres.
            if (suspicious[i] && far[i]) bad[i] = true
        }
        for (range in frozenRanges) {
            for (index in range) bad[index] = true
        }

        // A run of points outside the reference is a glitch only when there is
        // a speed spike at its edge. A smooth excursion that later rejoins the
        // route can be a real detour and must not be flattened onto the
        // reference. A track that simply continues beyond the end of the
        // reference must not be "corrected" either.
        val farRunSize = IntArray(n)
        var i = 0
        while (i < n) {
            if (!far[i]) {
                i++
                continue
            }
            var j = i
            while (j + 1 < n && far[j + 1]) j++

            val size = j - i + 1
            for (k in i..j) farRunSize[k] = size
            val mark = (i..j).any { suspicious[it] }
            if (mark) {
                for (k in i..j) bad[k] = true
            }
            i = j + 1
        }

        // If one point of a teleport is marked, the entire shifted run must be
        // replaced, otherwise a long broken line would remain in the file.
        // A plain missing-coordinate block must not pull a neighbouring far
        // run into the repair: that run can be a real offset/detour.
        var changed = true
        while (changed) {
            changed = false
            for (index in 0 until n) {
                if (!bad[index]) continue
                val glitch = suspicious[index] || frozenFlag[index]
                if (!glitch && !missing[index]) continue

                if (index > 0 && !bad[index - 1]) {
                    val neighbor = index - 1
                    // A missing block may absorb only a short far run (a
                    // teleport-like glitch). A long shifted run next to a
                    // missing prefix can be a real offset and must stay.
                    if (far[neighbor] && (glitch || farRunSize[neighbor] <= 5)) {
                        bad[neighbor] = true
                        changed = true
                    }
                }
                if (index < n - 1 && !bad[index + 1]) {
                    val neighbor = index + 1
                    if (far[neighbor] && (glitch || farRunSize[neighbor] <= 5)) {
                        bad[neighbor] = true
                        changed = true
                    }
                }
            }
        }

        val blocks = ArrayList<IntRange>()
        i = 0
        while (i < n) {
            if (!bad[i]) {
                i++
                continue
            }
            var j = i
            while (j + 1 < n && bad[j + 1]) j++
            blocks.add(i..j)
            i = j + 1
        }

        val unresolvedFlags = BooleanArray(n)

        for (block in blocks) {
            val left = findAnchor(block.first - 1, -1, valid, deviation, threshold)
                ?: if (block.last == n - 1) {
                    findEdgeAnchor(block.first - 1, -1, valid, deviation, threshold)
                } else {
                    null
                }
            val right = findAnchor(block.last + 1, 1, valid, deviation, threshold)
                ?: if (block.first == 0) {
                    findEdgeAnchor(block.last + 1, 1, valid, deviation, threshold)
                } else {
                    null
                }
            val expected = expectedDistance(block, input, left, right, medianSpeed, medianSpacing)
            val clampEdges = (block.first..block.last).any { frozenFlag[it] }

            val repaired = when {
                left != null && right != null -> repairBetween(
                    block, left, right, expected, input, lat, lon, ele,
                    coordinateChanged, elevationFilled, threshold
                )
                left != null -> repairOneSide(
                    block, left, 1, input, lat, lon, ele,
                    coordinateChanged, elevationFilled, medianSpeed, medianSpacing, threshold, clampEdges
                )
                right != null -> repairOneSide(
                    block, right, -1, input, lat, lon, ele,
                    coordinateChanged, elevationFilled, medianSpeed, medianSpacing, threshold, clampEdges
                )
                else -> repairWithoutAnchors(
                    block, input, lat, lon, ele, coordinateChanged, elevationFilled, threshold
                )
            }
            if (!repaired) {
                for (index in block) unresolvedFlags[index] = true
            }
        }

        if (settings.fillMissingElevation) {
            for (index in 0 until n) {
                if (ele[index] != null || !Geo.isValidCoordinate(lat[index], lon[index])) continue
                val projection = reference.nearest(
                    lat[index]!!,
                    lon[index]!!,
                    max(100.0, threshold * 3.0)
                ) ?: continue
                if (projection.ele != null) {
                    ele[index] = projection.ele
                    elevationFilled[index] = true
                }
            }
        }

        // Count and validate results.
        var fixedCoordinates = 0
        var missingCoordinates = 0
        var unresolved = 0
        val filledElevations = elevationFilled.count { it }
        var leadingFixed = 0
        var trailingFixed = 0
        for (block in blocks) {
            val changed = (block.first..block.last).count { coordinateChanged[it] }
            if (block.first == 0) leadingFixed = changed
            if (block.last == n - 1) trailingFixed = changed
        }
        for (index in 0 until n) {
            if (coordinateChanged[index]) {
                fixedCoordinates++
                if (missing[index]) missingCoordinates++
            }
            if (bad[index]) {
                val postValid = Geo.isValidCoordinate(lat[index], lon[index])
                val postDeviation = if (postValid) {
                    reference.nearest(lat[index]!!, lon[index]!!, max(threshold * 2.0, 100.0))?.distance
                } else {
                    null
                }
                if (!postValid || postDeviation == null || postDeviation > threshold * 1.5) {
                    unresolvedFlags[index] = true
                }
            }
            if (unresolvedFlags[index]) unresolved++
        }

        val maxDeviationBefore = deviation.filter { it.isFinite() }.maxOrNull() ?: 0.0
        val maxDeviationCapped = deviation.any { !it.isFinite() }
        val distanceAfter = distanceOf(lat, lon)

        if (fixedCoordinates > n / 2 && n > 0) warnings.add(FixWarning.LARGE_CHANGES)
        if (unresolved > 0) warnings.add(FixWarning.NO_REFERENCE_COVERAGE)
        if (reference.paths.size > 1) warnings.add(FixWarning.REFERENCE_SEGMENTS)
        if (blocks.isEmpty()) warnings.add(FixWarning.NOTHING_TO_FIX)

        return FixResult(
            lat = lat,
            lon = lon,
            ele = ele,
            coordinateChanged = coordinateChanged,
            elevationFilled = elevationFilled,
            stats = FixStats(
                totalPoints = n,
                fixedCoordinates = fixedCoordinates,
                missingCoordinates = missingCoordinates,
                filledElevations = filledElevations,
                problemBlocks = blocks.size,
                unresolvedPoints = unresolved,
                maxDeviationBefore = maxDeviationBefore,
                maxDeviationCapped = maxDeviationCapped,
                maxJumpBefore = maxJumpBefore,
                distanceBefore = distanceBefore,
                distanceAfter = distanceAfter,
                speedLimit = speedLimit,
                frozenRuns = frozenRanges.size,
                frozenCoordinates = frozenRanges.sumOf { it.last - it.first + 1 },
                leadingFixed = leadingFixed,
                trailingFixed = trailingFixed
            ),
            warnings = warnings
        )
    }

    private fun markFartherEndpoint(
        first: Int,
        second: Int,
        deviation: DoubleArray,
        suspicious: BooleanArray
    ) {
        if (deviation[first] >= deviation[second]) suspicious[first] = true else suspicious[second] = true
    }

    /**
     * Find runs where the GPS coordinate stopped updating although the rider
     * kept moving. The coordinate itself is valid and often still lies on the
     * route, so it cannot be found by deviation alone. Movement is confirmed
     * with the recorder's cumulative distance/speed when available, otherwise
     * with the distance along the reference between the surrounding anchors.
     */
    private fun detectFrozenRuns(
        input: List<TrackPoint>,
        valid: BooleanArray,
        nearest: Array<Projection?>
    ): List<IntRange> {
        val result = ArrayList<IntRange>()
        var index = 0
        while (index < input.size) {
            if (!valid[index]) {
                index++
                continue
            }
            var end = index
            while (end + 1 < input.size &&
                valid[end + 1] &&
                Geo.distanceMeters(
                    input[end].lat!!, input[end].lon!!,
                    input[end + 1].lat!!, input[end + 1].lon!!
                ) <= FROZEN_MAX_MOVE_METERS
            ) {
                end++
            }
            if (end - index >= 2 && frozenRunShowsMovement(input, index, end, valid, nearest)) {
                result.add(index..end)
            }
            index = end + 1
        }
        return result
    }

    private fun frozenRunShowsMovement(
        input: List<TrackPoint>,
        start: Int,
        end: Int,
        valid: BooleanArray,
        nearest: Array<Projection?>
    ): Boolean {
        val duration = secondsBetween(input[start].time, input[end].time)
        val startDistance = input[start].cumulativeDistanceMeters
        val endDistance = input[end].cumulativeDistanceMeters
        val sensorDistance = if (startDistance != null && endDistance != null) {
            endDistance - startDistance
        } else {
            null
        }
        val speeds = (start..end).mapNotNull { input[it].speedMetersPerSecond }
        val averageSpeed = speeds.takeIf { it.isNotEmpty() }?.average()
        val referenceGap = routeGap(start, end, valid, nearest)

        if (sensorDistance != null && sensorDistance >= FROZEN_MIN_SENSOR_DISTANCE_METERS) {
            return true
        }
        if (sensorDistance != null && sensorDistance >= 5.0 &&
            averageSpeed != null && averageSpeed >= 1.5 &&
            referenceGap != null && referenceGap >= 20.0
        ) {
            return true
        }
        if (averageSpeed != null && averageSpeed >= 2.0 &&
            duration != null && duration >= 5.0 &&
            referenceGap != null && referenceGap >= 20.0
        ) {
            return true
        }
        // Last resort for files without speed or distance extensions.
        return sensorDistance == null && averageSpeed == null &&
            duration != null && duration >= 15.0 &&
            referenceGap != null && referenceGap >= 50.0
    }

    private fun routeGap(
        start: Int,
        end: Int,
        valid: BooleanArray,
        nearest: Array<Projection?>
    ): Double? {
        val leftIndex = if (start > 0 && valid[start - 1]) start - 1 else start
        val rightIndex = if (end + 1 < valid.size && valid[end + 1]) end + 1 else end
        val left = nearest[leftIndex] ?: return null
        val right = nearest[rightIndex] ?: return null
        if (left.pathIndex != right.pathIndex) return null
        val path = reference.paths[left.pathIndex]
        if (!path.closed) return abs(right.s - left.s)
        val forward = path.forwardLength(left.s, right.s) ?: return null
        val backward = path.backwardLength(left.s, right.s) ?: return null
        return min(forward, backward)
    }

    private fun findAnchor(
        start: Int,
        step: Int,
        valid: BooleanArray,
        deviation: DoubleArray,
        threshold: Double
    ): Int? {
        var index = start
        var checked = 0
        while (index in valid.indices && checked < 40) {
            if (valid[index] && deviation[index] <= threshold * 1.5) return index
            index += step
            checked++
        }
        return null
    }

    /**
     * Fallback for a block that touches the very start or end of the track.
     * The first/last GPS fix may itself be a little off the reference, but it
     * is still the only reliable anchor for reconstructing the missing edge
     * section. The anchor itself is not moved; it is only used as a starting
     * point for the projection.
     */
    private fun findEdgeAnchor(
        start: Int,
        step: Int,
        valid: BooleanArray,
        deviation: DoubleArray,
        threshold: Double
    ): Int? {
        var index = start
        var checked = 0
        val limit = max(150.0, threshold * 4.0)
        while (index in valid.indices && checked < 200) {
            if (valid[index] && deviation[index].isFinite() && deviation[index] <= limit) {
                return index
            }
            index += step
            checked++
        }
        return null
    }

    private fun expectedDistance(
        block: IntRange,
        input: List<TrackPoint>,
        left: Int?,
        right: Int?,
        medianSpeed: Double,
        medianSpacing: Double
    ): Double {
        if (left != null && right != null) {
            if (blockSensorDistanceUsable(input, block, left, right)) {
                return (input[right].cumulativeDistanceMeters!! -
                    input[left].cumulativeDistanceMeters!!).coerceAtLeast(10.0)
            }
            val seconds = secondsBetween(input[left].time, input[right].time)
            if (seconds != null && seconds > 0.0) {
                return (seconds * medianSpeed).coerceAtLeast(10.0)
            }
        }
        val anchorCount = (if (left != null) 1 else 0) + (if (right != null) 1 else 0)
        val pointCount = block.count() + anchorCount
        return (pointCount * medianSpacing).coerceAtLeast(10.0)
    }

    private fun blockSensorDistanceUsable(
        input: List<TrackPoint>,
        block: IntRange,
        left: Int,
        right: Int
    ): Boolean {
        val startDistance = input[left].cumulativeDistanceMeters ?: return false
        val endDistance = input[right].cumulativeDistanceMeters ?: return false
        if (endDistance <= startDistance + 1.0) return false
        var previous = startDistance
        for (index in block) {
            val distance = input[index].cumulativeDistanceMeters ?: return false
            if (distance < startDistance || distance > endDistance || distance < previous) return false
            previous = distance
        }
        return true
    }

    private fun oneSideSensorDistanceUsable(
        input: List<TrackPoint>,
        block: IntRange,
        anchor: Int,
        direction: Int
    ): Boolean {
        val anchorDistance = input[anchor].cumulativeDistanceMeters ?: return false
        var previous = anchorDistance
        if (direction > 0) {
            for (index in block) {
                val distance = input[index].cumulativeDistanceMeters ?: return false
                if (distance < previous) return false
                previous = distance
            }
        } else {
            for (index in block.reversed()) {
                val distance = input[index].cumulativeDistanceMeters ?: return false
                if (distance > previous) return false
                previous = distance
            }
        }
        return true
    }

    private fun repairBetween(
        block: IntRange,
        left: Int,
        right: Int,
        expected: Double,
        input: List<TrackPoint>,
        lat: Array<Double?>,
        lon: Array<Double?>,
        ele: Array<Double?>,
        coordinateChanged: BooleanArray,
        elevationFilled: BooleanArray,
        threshold: Double
    ): Boolean {
        val leftCandidates = reference.nearestCandidates(
            lat[left]!!, lon[left]!!,
            max(100.0, threshold * 2.0),
            maxCandidates = 8
        )
        val rightCandidates = reference.nearestCandidates(
            lat[right]!!, lon[right]!!,
            max(100.0, threshold * 2.0),
            maxCandidates = 8
        )
        if (leftCandidates.isEmpty() || rightCandidates.isEmpty()) return false

        var best: PathChoice? = null
        var bestScore = Double.POSITIVE_INFINITY
        for (leftProjection in leftCandidates) {
            for (rightProjection in rightCandidates) {
                if (leftProjection.pathIndex != rightProjection.pathIndex) continue
                val path = reference.paths[leftProjection.pathIndex]
                for (direction in intArrayOf(1, -1)) {
                    val baseLength = if (direction > 0) {
                        path.forwardLength(leftProjection.s, rightProjection.s)
                    } else {
                        path.backwardLength(leftProjection.s, rightProjection.s)
                    } ?: continue

                    val lengths = ArrayList<Double>()
                    lengths.add(baseLength)
                    // A closed reference lap can be traversed more than once.
                    if (path.closed) {
                        if (expected > path.totalLength * 0.45) {
                            lengths.add(baseLength + path.totalLength)
                        }
                        if (expected > path.totalLength * 1.45) {
                            lengths.add(baseLength + path.totalLength * 2.0)
                        }
                    }

                    for (length in lengths) {
                        val score = abs(length - expected) / max(50.0, expected) +
                            0.01 * (leftProjection.distance + rightProjection.distance)
                        if (score < bestScore) {
                            bestScore = score
                            best = PathChoice(
                                pathIndex = leftProjection.pathIndex,
                                direction = direction,
                                length = length,
                                start = leftProjection,
                                end = rightProjection
                            )
                        }
                    }
                }
            }
        }

        val choice = best ?: return false
        if (choice.length > expected * 2.5 + 300.0) return false

        val path = reference.paths[choice.pathIndex]
        val startTime = input[left].time
        val endTime = input[right].time
        val useDistance = blockSensorDistanceUsable(input, block, left, right)
        val startDistance = input[left].cumulativeDistanceMeters
        val endDistance = input[right].cumulativeDistanceMeters
        for (index in block) {
            val fraction = fractionBetween(
                index, left, right, input, startTime, endTime,
                useDistance, startDistance, endDistance
            )
            val s = choice.start.s + choice.direction * choice.length * fraction
            val vertex = path.pointAt(s, wrap = path.closed)
            applyVertex(index, vertex, lat, lon, ele, coordinateChanged, elevationFilled)
        }
        return true
    }

    private fun repairOneSide(
        block: IntRange,
        anchor: Int,
        direction: Int,
        input: List<TrackPoint>,
        lat: Array<Double?>,
        lon: Array<Double?>,
        ele: Array<Double?>,
        coordinateChanged: BooleanArray,
        elevationFilled: BooleanArray,
        medianSpeed: Double,
        medianSpacing: Double,
        threshold: Double,
        clampEdges: Boolean
    ): Boolean {
        val edgeBlock = block.first == 0 || block.last == input.size - 1
        val searchRadius = if (edgeBlock) {
            max(150.0, threshold * 4.0)
        } else {
            max(100.0, threshold * 2.0)
        }
        val candidates = reference.nearestCandidates(
            lat[anchor]!!, lon[anchor]!!,
            searchRadius,
            maxCandidates = 8
        )
        if (candidates.isEmpty()) return false
        val projection = candidates.minByOrNull { it.distance } ?: return false
        val path = reference.paths[projection.pathIndex]
        val anchorTime = input[anchor].time
        val anchorDistance = input[anchor].cumulativeDistanceMeters
        val useDistance = oneSideSensorDistanceUsable(input, block, anchor, direction)
        // Only accept a tiny overshoot for a non-frozen edge block. Larger
        // extrapolation would invent a route beyond the reference.
        val edgeTolerance = 1.0
        var repairedAny = false

        for (index in block) {
            val vertex: RefVertex
            var targetS: Double
            val distance = if (useDistance && anchorDistance != null) {
                abs(anchorDistance - input[index].cumulativeDistanceMeters!!)
            } else if (direction > 0) {
                val seconds = secondsBetween(anchorTime, input[index].time)
                if (seconds != null && seconds > 0) {
                    seconds * medianSpeed
                } else {
                    (index - anchor).toDouble() * medianSpacing
                }
            } else {
                val seconds = secondsBetween(input[index].time, anchorTime)
                if (seconds != null && seconds > 0) {
                    seconds * medianSpeed
                } else {
                    (anchor - index).toDouble() * medianSpacing
                }
            }
            targetS = if (direction > 0) projection.s + distance else projection.s - distance
            // Do not invent coordinates far beyond an open reference. A block
            // that touches the start/end of the whole track may be clamped to
            // that edge when the overshoot is small (rounding, GPS noise) or
            // when it is a frozen run. Everything else stays unresolved
            // instead of being piled onto the endpoint.
            if (!path.closed) {
                if (targetS < 0.0) {
                    if (block.first == 0 && (clampEdges || targetS >= -edgeTolerance)) {
                        targetS = 0.0
                    } else {
                        continue
                    }
                } else if (targetS > path.totalLength) {
                    if (block.last == input.size - 1 &&
                        (clampEdges || targetS <= path.totalLength + edgeTolerance)
                    ) {
                        targetS = path.totalLength
                    } else {
                        continue
                    }
                }
            }
            vertex = path.pointAt(targetS, wrap = path.closed)
            applyVertex(index, vertex, lat, lon, ele, coordinateChanged, elevationFilled)
            repairedAny = true
        }
        return repairedAny
    }

    private fun repairWithoutAnchors(
        block: IntRange,
        input: List<TrackPoint>,
        lat: Array<Double?>,
        lon: Array<Double?>,
        ele: Array<Double?>,
        coordinateChanged: BooleanArray,
        elevationFilled: BooleanArray,
        threshold: Double
    ): Boolean {
        var repairedAny = false
        for (index in block) {
            val projection = if (Geo.isValidCoordinate(lat[index], lon[index])) {
                reference.nearest(lat[index]!!, lon[index]!!, max(500.0, threshold * 5.0))
            } else {
                null
            }
            if (projection != null) {
                applyVertex(
                    index,
                    RefVertex(projection.lat, projection.lon, projection.ele),
                    lat,
                    lon,
                    ele,
                    coordinateChanged,
                    elevationFilled
                )
                repairedAny = true
            }
        }

        // Fill points with no coordinates between two repaired neighbours in a
        // straight line. This is only a last-resort fallback.
        var index = block.first
        while (index <= block.last) {
            if (Geo.isValidCoordinate(lat[index], lon[index])) {
                index++
                continue
            }
            var end = index
            while (end <= block.last && !Geo.isValidCoordinate(lat[end], lon[end])) end++
            val before = (index - 1).takeIf { it >= 0 && Geo.isValidCoordinate(lat[it], lon[it]) }
            val after = end.takeIf { it <= block.last && Geo.isValidCoordinate(lat[it], lon[it]) }
            if (before != null && after != null) {
                for (k in index until end) {
                    val f = (k - before).toDouble() / (after - before).toDouble()
                    lat[k] = lat[before]!! + (lat[after]!! - lat[before]!!) * f
                    lon[k] = lon[before]!! + (lon[after]!! - lon[before]!!) * f
                    coordinateChanged[k] = true
                    repairedAny = true
                }
            }
            index = end
        }
        return repairedAny
    }

    private fun fractionBetween(
        index: Int,
        left: Int,
        right: Int,
        input: List<TrackPoint>,
        startTime: Instant?,
        endTime: Instant?,
        useDistance: Boolean,
        startDistance: Double?,
        endDistance: Double?
    ): Double {
        val pointDistance = input[index].cumulativeDistanceMeters
        if (useDistance && startDistance != null && endDistance != null && pointDistance != null) {
            val total = endDistance - startDistance
            val current = pointDistance - startDistance
            if (total > 0.0) return (current / total).coerceIn(0.0, 1.0)
        }
        val pointTime = input[index].time
        if (startTime != null && endTime != null && pointTime != null) {
            val total = Duration.between(startTime, endTime).toMillis() / 1000.0
            val current = Duration.between(startTime, pointTime).toMillis() / 1000.0
            if (total > 0.0) return (current / total).coerceIn(0.0, 1.0)
        }
        return ((index - left).toDouble() / (right - left).toDouble()).coerceIn(0.0, 1.0)
    }

    private fun applyVertex(
        index: Int,
        vertex: RefVertex,
        lat: Array<Double?>,
        lon: Array<Double?>,
        ele: Array<Double?>,
        coordinateChanged: BooleanArray,
        elevationFilled: BooleanArray
    ) {
        val changed = lat[index] == null ||
            lon[index] == null ||
            abs(lat[index]!! - vertex.lat) > 1e-9 ||
            abs(lon[index]!! - vertex.lon) > 1e-9
        lat[index] = vertex.lat
        lon[index] = vertex.lon
        coordinateChanged[index] = coordinateChanged[index] || changed
        if (ele[index] == null && vertex.ele != null) {
            ele[index] = vertex.ele
            elevationFilled[index] = true
        }
    }

    private fun secondsBetween(first: Instant?, second: Instant?): Double? {
        if (first == null || second == null) return null
        return Duration.between(first, second).toMillis() / 1000.0
    }

    private fun maxConsecutiveDistance(lat: Array<Double?>, lon: Array<Double?>, valid: BooleanArray): Double {
        var maximum = 0.0
        for (index in 1 until lat.size) {
            if (!valid[index - 1] || !valid[index]) continue
            maximum = max(
                maximum,
                Geo.distanceMeters(lat[index - 1]!!, lon[index - 1]!!, lat[index]!!, lon[index]!!)
            )
        }
        return maximum
    }

    private fun distanceOf(lat: Array<Double?>, lon: Array<Double?>): Double {
        var total = 0.0
        for (index in 1 until lat.size) {
            if (!Geo.isValidCoordinate(lat[index - 1], lon[index - 1]) ||
                !Geo.isValidCoordinate(lat[index], lon[index])
            ) {
                continue
            }
            total += Geo.distanceMeters(
                lat[index - 1]!!, lon[index - 1]!!, lat[index]!!, lon[index]!!
            )
        }
        return total
    }
}
