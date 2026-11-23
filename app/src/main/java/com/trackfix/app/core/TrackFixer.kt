package com.trackfix.app.core

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val FROZEN_MAX_MOVE_METERS = 0.5
private const val FROZEN_MIN_SENSOR_DISTANCE_METERS = 20.0
private const val TRANSITION_DISTANCE_WEIGHT = 1.0
private const val CANDIDATE_DISTANCE_WEIGHT = 2.0
private const val PHASE_TOLERANCE_METERS = 120.0
private const val PHASE_WEIGHT = 0.1
private const val FIRST_POINT_SEARCH_RADIUS_METERS = 600.0
private const val DISTANCE_ALIGNMENT_CAP_METERS = 50.0
private const val DISTANCE_ALIGNMENT_PHASE_STEPS = 200

data class FixSettings(
    val maxDeviationMeters: Double = 30.0,
    val fillMissingElevation: Boolean = true,
    /**
     * "Follow the reference": every coordinate is taken from the reference
     * route, while time, elevation and all sensor values stay as in the source
     * file. The position along the reference comes from the same alignment the
     * regular repair uses, so repeated laps and self-crossings stay on the
     * correct branch.
     */
    val followReference: Boolean = false
)

enum class FixWarning {
    LARGE_CHANGES,
    NO_REFERENCE_COVERAGE,
    REFERENCE_SEGMENTS,
    NO_GPS_RECONSTRUCTED,
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
    val trailingFixed: Int = 0,
    val reconstructedCoordinates: Int = 0,
    val insertedPoints: Int = 0
)

/**
 * A new track point that must be inserted after an existing source point.
 * Used only when a recording has long gaps and the route would otherwise be
 * drawn as a straight chord across the reference loop.
 */
data class InsertedPoint(
    val afterIndex: Int,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val time: Instant
)

class FixResult(
    val lat: Array<Double?>,
    val lon: Array<Double?>,
    val ele: Array<Double?>,
    val coordinateChanged: BooleanArray,
    val elevationFilled: BooleanArray,
    val stats: FixStats,
    val warnings: Set<FixWarning>,
    val inserted: List<InsertedPoint> = emptyList()
)

private data class PathChoice(
    val pathIndex: Int,
    val direction: Int,
    val length: Double,
    val start: Projection,
    val end: Projection
)

/**
 * Continuous position of every source point along the reference. The raw
 * nearest projection is ambiguous when the reference passes the same place
 * more than once (a loop, several laps, or a self-crossing). The alignment
 * below follows the source distance and continuity instead, so a point is
 * assigned to the correct lap even when another lap is geometrically closer.
 */
private data class ProgressAlignment(
    val s: Array<Double?>,
    val pathIndex: IntArray,
    val direction: Int,
    val scale: Double
)

private data class DirectionAlignment(
    val progress: DoubleArray,
    val direction: Int,
    val cost: Double
)

/**
 * Global linear alignment between the recorder's travelled distance and the
 * reference. It is deliberately simple: one phase and one scale for the whole
 * track. That is enough to keep every point on the correct branch of a loop,
 * while local candidate projection can still refine the position.
 */
private data class DistanceAlignment(
    val phase: Double,
    val scale: Double,
    val firstDistance: Double
) {
    fun progressAt(sourceDistance: Double): Double =
        phase + (sourceDistance - firstDistance) * scale
}

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
        // Distance actually travelled according to the source sensors. It is
        // used to spread a repaired block along the reference in the same
        // proportion as the original intervals (fast/slow blocks stay
        // distinguishable), instead of using a single average speed.
        val sourceFallbackSpeed = SourceDistance.medianSpeed(input)
            ?.takeIf { it > 0.0 } ?: medianSpeed
        val sourcePrefix = SourceDistance.prefixMeters(input, sourceFallbackSpeed)
        val distanceAlignment = if (reference.paths.size == 1) {
            computeDistanceAlignment(input, valid, sourcePrefix, reference.paths[0])
        } else {
            null
        }
        val progressAlignment = computeProgress(
            input, valid, sourcePrefix, medianSpeed, threshold
        )

        if (settings.followReference) {
            return followReference(
                input, valid, lat, lon, ele, coordinateChanged, elevationFilled,
                warnings, progressAlignment, distanceAlignment, nearest, deviation,
                sourcePrefix, threshold, distanceBefore, maxJumpBefore, speedLimit
            )
        }

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
                // A huge spike cannot be real movement. Mark both ends so a
                // stale coordinate cannot survive just because it happens to
                // lie on a different part of the route.
                if (speed > 50.0) {
                    suspicious[i - 1] = true
                    suspicious[i] = true
                } else {
                    markFartherEndpoint(i - 1, i, deviation, suspicious)
                }
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
        // A point can lie a few centimetres from a parallel branch of a
        // self-overlapping reference while the travelled distance shows that
        // it belongs to another branch. Such a point is not "far" in metres,
        // but keeping it would tear the track apart. Real detours farther than
        // the deviation threshold are deliberately left alone.
        val sourceLength = sourcePrefix.last() - sourcePrefix.first()
        val sourceToReferenceRatio = if (reference.paths[0].totalLength > 1.0) {
            sourceLength / reference.paths[0].totalLength
        } else {
            0.0
        }
        // The distance alignment is only meaningful when the recording covers
        // roughly the same route length as the reference. A clean track that
        // deliberately covers just a part of the reference (or several times
        // its length) would otherwise have every point flagged as a branch
        // mismatch and rewritten.
        if (distanceAlignment != null &&
            reference.paths[0].closed &&
            reference.paths[0].repeatLapCount <= 2 &&
            sourceToReferenceRatio in 0.8..1.2
        ) {
            val path = reference.paths[0]
            for (i in 0 until n) {
                if (!valid[i] || missing[i]) continue
                val projection = nearest[i] ?: continue
                val predicted = distanceAlignment.progressAt(sourcePrefix[i])
                var difference = abs(predicted - projection.s)
                if (path.closed) {
                    difference %= path.totalLength
                    if (difference > path.totalLength * 0.5) {
                        difference = path.totalLength - difference
                    }
                }
                if (difference > max(threshold, 50.0) && deviation[i] <= threshold) {
                    bad[i] = true
                }
            }
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
            val expected = expectedDistance(
                block, input, left, right, medianSpeed, medianSpacing, sourcePrefix
            )
            val clampEdges = (block.first..block.last).any { frozenFlag[it] }

            val repaired = when {
                left != null && right != null -> repairBetween(
                    block, left, right, expected, input, lat, lon, ele,
                    coordinateChanged, elevationFilled, threshold, sourcePrefix,
                    distanceAlignment, progressAlignment
                )
                left != null -> repairOneSide(
                    block, left, 1, input, lat, lon, ele,
                    coordinateChanged, elevationFilled, medianSpeed, medianSpacing,
                    threshold, clampEdges, sourcePrefix, distanceAlignment,
                    progressAlignment, frozenFlag
                )
                right != null -> repairOneSide(
                    block, right, -1, input, lat, lon, ele,
                    coordinateChanged, elevationFilled, medianSpeed, medianSpacing,
                    threshold, clampEdges, sourcePrefix, distanceAlignment,
                    progressAlignment, frozenFlag
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

    /**
     * "Follow the reference" mode. The coordinate of every point is taken from
     * the reference route; time, elevation and all sensor values stay exactly
     * as they are in the source file.
     *
     * The position along the reference is not the plain nearest projection:
     * that would jump between branches of a self-crossing route or between
     * repeated laps. The same alignment the regular repair uses is applied
     * instead, so the overlay stays continuous and on the correct lap.
     */
    private fun followReference(
        input: List<TrackPoint>,
        valid: BooleanArray,
        lat: Array<Double?>,
        lon: Array<Double?>,
        ele: Array<Double?>,
        coordinateChanged: BooleanArray,
        elevationFilled: BooleanArray,
        warnings: MutableSet<FixWarning>,
        progress: ProgressAlignment,
        distanceAlignment: DistanceAlignment?,
        nearest: Array<Projection?>,
        deviation: DoubleArray,
        sourcePrefix: DoubleArray,
        threshold: Double,
        distanceBefore: Double,
        maxJumpBefore: Double,
        speedLimit: Double
    ): FixResult {
        val n = input.size
        var changed = 0
        var missingPlaced = 0
        var unresolved = 0

        // Where the point belongs along the reference. The reference can pass
        // the same place many times (repeated laps, a self-crossing), so the
        // plain nearest projection is ambiguous and jumps by whole laps.
        // When the recording covers roughly the same length as the reference,
        // the global distance alignment is used: it is continuous by
        // construction, and the physically close copy of the reference that
        // lies nearest to that prediction is selected. Otherwise the per-point
        // candidate alignment is used and gaps are chained along the source
        // distance, which keeps several laps of a short loop intact.
        val singlePath = reference.paths.size == 1
        val totalSource = sourcePrefix.last() - sourcePrefix.first()
        val ratio = if (singlePath && reference.paths[0].totalLength > 1.0) {
            totalSource / reference.paths[0].totalLength
        } else {
            0.0
        }
        val globalFirst = distanceAlignment != null && singlePath && ratio in 0.8..1.2

        val s = arrayOfNulls<Double>(n)
        val pathOf = IntArray(n) { -1 }
        if (globalFirst) {
            val path = reference.paths[0]
            for (index in 0 until n) {
                s[index] = distanceAlignment!!.progressAt(sourcePrefix[index])
                pathOf[index] = 0
            }
        } else {
            for (index in 0 until n) {
                val aligned = progress.s[index]
                if (aligned != null) {
                    s[index] = aligned
                    pathOf[index] = progress.pathIndex[index].coerceIn(0, reference.paths.size - 1)
                }
            }
            if (s.any { it != null }) {
                val step = progress.scale * (if (progress.direction < 0) -1.0 else 1.0)
                for (index in 1 until n) {
                    if (s[index] != null || s[index - 1] == null) continue
                    s[index] = s[index - 1]!! + (sourcePrefix[index] - sourcePrefix[index - 1]) * step
                    pathOf[index] = pathOf[index - 1]
                }
                for (index in n - 2 downTo 0) {
                    if (s[index] != null || s[index + 1] == null) continue
                    s[index] = s[index + 1]!! - (sourcePrefix[index + 1] - sourcePrefix[index]) * step
                    pathOf[index] = pathOf[index + 1]
                }
            }
        }

        for (index in 0 until n) {
            var position = s[index]
            var pathIndex = pathOf[index]
            if (position == null && distanceAlignment != null) {
                position = distanceAlignment.progressAt(sourcePrefix[index])
                pathIndex = 0
            }
            if (position == null) {
                val projection = nearest[index] ?: continue
                position = projection.s
                pathIndex = projection.pathIndex
            }
            if (pathIndex < 0) {
                unresolved++
                continue
            }
            val path = reference.paths[pathIndex]
            val vertex = path.pointAt(position, wrap = path.closed)
            if (!Geo.isValidCoordinate(vertex.lat, vertex.lon)) {
                unresolved++
                continue
            }
            if (!valid[index]) missingPlaced++
            lat[index] = vertex.lat
            lon[index] = vertex.lon
            coordinateChanged[index] = true
            changed++
            if (settings.fillMissingElevation && ele[index] == null && vertex.ele != null) {
                ele[index] = vertex.ele
                elevationFilled[index] = true
            }
        }

        val filledElevations = elevationFilled.count { it }
        val maxDeviationBefore = deviation.filter { it.isFinite() }.maxOrNull() ?: 0.0
        val maxDeviationCapped = deviation.any { !it.isFinite() }

        if (changed > n / 2 && n > 0) warnings.add(FixWarning.LARGE_CHANGES)
        if (unresolved > 0) warnings.add(FixWarning.NO_REFERENCE_COVERAGE)
        if (reference.paths.size > 1) warnings.add(FixWarning.REFERENCE_SEGMENTS)

        return FixResult(
            lat = lat,
            lon = lon,
            ele = ele,
            coordinateChanged = coordinateChanged,
            elevationFilled = elevationFilled,
            stats = FixStats(
                totalPoints = n,
                fixedCoordinates = changed,
                missingCoordinates = missingPlaced,
                filledElevations = filledElevations,
                problemBlocks = 0,
                unresolvedPoints = unresolved,
                maxDeviationBefore = maxDeviationBefore,
                maxDeviationCapped = maxDeviationCapped,
                maxJumpBefore = maxJumpBefore,
                distanceBefore = distanceBefore,
                distanceAfter = distanceOf(lat, lon),
                speedLimit = speedLimit,
                reconstructedCoordinates = changed
            ),
            warnings = warnings
        )
    }

    /**
     * Aligns the source points to the reference by dynamic programming.
     *
     * A point can have several candidate projections when the reference
     * crosses itself or contains several laps. The cost combines the geometric
     * distance to the reference with the difference between the source
     * distance travelled and the reference distance between consecutive
     * points. That makes the chosen candidate continuous even when another
     * branch is a few centimetres closer.
     */
    private fun computeProgress(
        input: List<TrackPoint>,
        valid: BooleanArray,
        sourcePrefix: DoubleArray,
        medianSpeed: Double,
        threshold: Double
    ): ProgressAlignment {
        val empty = ProgressAlignment(
            s = arrayOfNulls(input.size),
            pathIndex = IntArray(input.size) { -1 },
            direction = 1,
            scale = 1.0
        )
        if (reference.paths.size != 1) return empty
        val path = reference.paths[0]
        val radius = max(150.0, threshold * 5.0)
        val candidateLists = arrayOfNulls<List<Projection>>(input.size)
        val indices = ArrayList<Int>()
        for (index in input.indices) {
            if (!valid[index]) continue
            val lat = input[index].lat ?: continue
            val lon = input[index].lon ?: continue
            // The first GPS fix may be far from the route while the source
            // distance still shows that the recording began at the route
            // start. A wider search lets the phase prior pick that start
            // instead of locking onto a nearer parallel branch.
            val pointRadius = if (indices.isEmpty()) {
                max(radius, FIRST_POINT_SEARCH_RADIUS_METERS)
            } else {
                radius
            }
            val candidates = path.candidates(lat, lon, pointRadius)
            if (candidates.isEmpty()) continue
            candidateLists[index] = candidates
            indices.add(index)
        }
        if (indices.size < 2) return empty

        val forward = alignDirection(
            path, indices, candidateLists, input, sourcePrefix, medianSpeed, 1
        )
        val backward = alignDirection(
            path, indices, candidateLists, input, sourcePrefix, medianSpeed, -1
        )
        val chosen = when {
            forward == null -> backward
            backward == null -> forward
            forward.cost <= backward.cost -> forward
            else -> backward
        } ?: return empty

        val resultS = arrayOfNulls<Double>(input.size)
        val resultPath = IntArray(input.size) { -1 }
        for (position in indices.indices) {
            val index = indices[position]
            resultS[index] = chosen.progress[position]
            resultPath[index] = 0
        }
        val first = indices.first()
        val last = indices.last()
        val sourceSpan = sourcePrefix[last] - sourcePrefix[first]
        val scale = if (sourceSpan > 1.0) {
            val progressSpan = if (chosen.direction > 0) {
                chosen.progress.last() - chosen.progress.first()
            } else {
                chosen.progress.first() - chosen.progress.last()
            }
            (progressSpan / sourceSpan)
                .takeIf { it.isFinite() && it > 0.05 }
                ?: 1.0
        } else {
            1.0
        }
        return ProgressAlignment(
            s = resultS,
            pathIndex = resultPath,
            direction = chosen.direction,
            scale = scale.coerceIn(0.25, 4.0)
        )
    }

    /**
     * Finds one phase and one scale that map the recorder's cumulative
     * distance onto the reference. The cost is capped so a few bad GPS points
     * cannot pull the whole alignment to a wrong branch.
     */
    private fun computeDistanceAlignment(
        input: List<TrackPoint>,
        valid: BooleanArray,
        sourcePrefix: DoubleArray,
        path: RefPath
    ): DistanceAlignment? {
        val indices = ArrayList<Int>()
        for (index in input.indices) {
            if (!valid[index]) continue
            if (input[index].lat == null || input[index].lon == null) continue
            indices.add(index)
        }
        if (indices.size < 10) return null
        val first = indices.first()
        val totalSource = sourcePrefix.last() - sourcePrefix.first()
        if (totalSource < 20.0) return null
        val baseScale = path.totalLength / totalSource
        if (!baseScale.isFinite() || baseScale <= 0.0) return null
        // Keep the total source distance mapped exactly onto the reference
        // length. Letting the scale drift by several percent makes a repeated
        // stadium route gain or lose a whole lap over a long recording, even
        // though every individual point still looks close to the loop.
        val scales = doubleArrayOf(baseScale)
        val coarseStep = max(5.0, path.totalLength / DISTANCE_ALIGNMENT_PHASE_STEPS)
        var bestPhase = 0.0
        var bestScale = baseScale
        var bestCost = Double.POSITIVE_INFINITY
        var phase = 0.0
        while (phase < path.totalLength) {
            for (scale in scales) {
                val cost = distanceAlignmentCost(
                    path, indices, input, sourcePrefix,
                    sourcePrefix[first], phase, scale
                )
                if (cost < bestCost) {
                    bestCost = cost
                    bestPhase = phase
                    bestScale = scale
                }
            }
            phase += coarseStep
        }
        val refineStep = max(1.0, coarseStep / 8.0)
        var refinedPhase = bestPhase - coarseStep
        while (refinedPhase <= bestPhase + coarseStep) {
            val cost = distanceAlignmentCost(
                path, indices, input, sourcePrefix,
                sourcePrefix[first], refinedPhase, bestScale
            )
            if (cost < bestCost) {
                bestCost = cost
                bestPhase = refinedPhase
            }
            refinedPhase += refineStep
        }
        return DistanceAlignment(
            phase = bestPhase,
            scale = bestScale,
            firstDistance = sourcePrefix[first]
        )
    }

    private fun distanceAlignmentCost(
        path: RefPath,
        indices: List<Int>,
        input: List<TrackPoint>,
        sourcePrefix: DoubleArray,
        firstDistance: Double,
        phase: Double,
        scale: Double
    ): Double {
        var cost = 0.0
        for (index in indices) {
            val s = phase + (sourcePrefix[index] - firstDistance) * scale
            val vertex = path.pointAt(s, wrap = path.closed)
            cost += min(
                Geo.distanceMeters(
                    input[index].lat!!, input[index].lon!!,
                    vertex.lat, vertex.lon
                ),
                DISTANCE_ALIGNMENT_CAP_METERS
            )
        }
        return cost
    }

    /**
     * Keeps the real GPS projection on the lap selected by the distance
     * alignment. A stadium reference repeats the same loop many times, so the
     * nearest projection may be a copy several laps away. Snapping by the
     * repeat lap preserves the physical position while preventing a jump at
     * the edge of a repaired block.
     */
    private fun snapProjectionToLap(
        path: RefPath,
        predicted: Double,
        actual: Double
    ): Double {
        var difference = actual - predicted
        if (path.repeatLapCount > 1) {
            val lap = path.repeatLapLength
            if (lap > 1.0) {
                difference -= Math.round(difference / lap) * lap
            }
        } else if (path.closed && path.totalLength > 1.0) {
            difference %= path.totalLength
            if (difference > path.totalLength * 0.5) difference -= path.totalLength
            if (difference < -path.totalLength * 0.5) difference += path.totalLength
        }
        return predicted + difference
    }

    /**
     * Selects the reference copy used as a repair anchor. Several copies of a
     * repeated loop can lie within GPS noise of the same fix, so physical
     * distance alone cannot tell them apart. Keep the copies that are
     * physically close to the fix and pick the one whose progress is closest
     * to the distance alignment. This avoids both a jump to a far-away copy
     * and a silent reset to an early lap.
     */
    private fun chooseAnchorProjection(
        candidates: List<Projection>,
        predicted: Double,
        path: RefPath
    ): Projection? {
        if (candidates.isEmpty()) return null
        val nearest = candidates.minByOrNull { it.distance } ?: return null
        val physicalTolerance = max(10.0, nearest.distance + 5.0)
        val nearby = candidates.filter { it.distance <= physicalTolerance }
        return nearby.minByOrNull { candidate ->
            var difference = candidate.s - predicted
            if (path.closed && path.totalLength > 1.0) {
                difference %= path.totalLength
                if (difference > path.totalLength * 0.5) difference -= path.totalLength
                if (difference < -path.totalLength * 0.5) difference += path.totalLength
            }
            abs(difference)
        }
    }

    private fun alignDirection(
        path: RefPath,
        indices: List<Int>,
        candidateLists: Array<List<Projection>?>,
        input: List<TrackPoint>,
        sourcePrefix: DoubleArray,
        medianSpeed: Double,
        direction: Int
    ): DirectionAlignment? {
        val firstCandidates = candidateLists[indices.first()] ?: return null
        var best: DirectionAlignment? = null
        // The first candidate fixes the phase between the source distance and
        // the reference. Trying every candidate avoids guessing whether the
        // recording starts at the reference seam or at another point of a loop.
        for (firstIndex in firstCandidates.indices) {
            val alignment = alignWithPhase(
                path, indices, candidateLists, input, sourcePrefix,
                medianSpeed, direction, firstIndex
            ) ?: continue
            if (best == null || alignment.cost < best!!.cost) best = alignment
        }
        return best
    }

    private fun alignWithPhase(
        path: RefPath,
        indices: List<Int>,
        candidateLists: Array<List<Projection>?>,
        input: List<TrackPoint>,
        sourcePrefix: DoubleArray,
        medianSpeed: Double,
        direction: Int,
        forcedFirst: Int
    ): DirectionAlignment? {
        val firstCandidates = candidateLists[indices.first()] ?: return null
        if (forcedFirst !in firstCandidates.indices) return null
        val firstSource = sourcePrefix[indices.first()]
        val phase = if (direction > 0) {
            firstCandidates[forcedFirst].s - firstSource
        } else {
            firstCandidates[forcedFirst].s + firstSource
        }
        var costs = DoubleArray(firstCandidates.size) { Double.POSITIVE_INFINITY }
        var positions = DoubleArray(firstCandidates.size)
        costs[forcedFirst] = firstCandidates[forcedFirst].distance * CANDIDATE_DISTANCE_WEIGHT
        positions[forcedFirst] = firstCandidates[forcedFirst].s
        val backPointers = ArrayList<IntArray>(indices.size)
        val positionHistory = ArrayList<DoubleArray>(indices.size)
        backPointers.add(IntArray(firstCandidates.size) { -1 })
        positionHistory.add(positions.copyOf())

        for (position in 1 until indices.size) {
            val previousIndex = indices[position - 1]
            val currentIndex = indices[position]
            val previousCandidates = candidateLists[previousIndex] ?: return null
            val currentCandidates = candidateLists[currentIndex] ?: return null
            val expectedS = if (direction > 0) {
                sourcePrefix[currentIndex] + phase
            } else {
                phase - sourcePrefix[currentIndex]
            }
            val nextCosts = DoubleArray(currentCandidates.size) { Double.POSITIVE_INFINITY }
            val nextPositions = DoubleArray(currentCandidates.size)
            val back = IntArray(currentCandidates.size) { -1 }
            var expected = sourcePrefix[currentIndex] - sourcePrefix[previousIndex]
            if (expected <= 0.0) {
                val seconds = secondsBetween(input[previousIndex].time, input[currentIndex].time)
                if (seconds != null && seconds > 0.0) expected = seconds * medianSpeed
            }
            if (expected < 0.0) expected = 0.0
            for (candidateIndex in currentCandidates.indices) {
                val candidate = currentCandidates[candidateIndex]
                val phaseCost = branchPhaseCost(candidate.s, expectedS, path)
                var bestCost = Double.POSITIVE_INFINITY
                var bestPosition = 0.0
                var bestPrevious = -1
                for (previousCandidateIndex in previousCandidates.indices) {
                    val previousPosition = positions[previousCandidateIndex]
                    val delta = if (direction > 0) {
                        val base = candidate.s - previousPosition
                        if (path.closed) {
                            base + Math.round((expected - base) / path.totalLength) *
                                path.totalLength
                        } else {
                            base
                        }
                    } else {
                        val base = previousPosition - candidate.s
                        if (path.closed) {
                            base + Math.round((expected - base) / path.totalLength) *
                                path.totalLength
                        } else {
                            base
                        }
                    }
                    if (delta < -1.0) continue
                    val error = abs(delta - expected)
                    val total = costs[previousCandidateIndex] +
                        error * TRANSITION_DISTANCE_WEIGHT +
                        candidate.distance * CANDIDATE_DISTANCE_WEIGHT +
                        phaseCost
                    if (total < bestCost) {
                        bestCost = total
                        bestPrevious = previousCandidateIndex
                        bestPosition = if (direction > 0) {
                            previousPosition + delta
                        } else {
                            previousPosition - delta
                        }
                    }
                }
                if (bestPrevious >= 0) {
                    nextCosts[candidateIndex] = bestCost
                    nextPositions[candidateIndex] = bestPosition
                    back[candidateIndex] = bestPrevious
                }
            }
            costs = nextCosts
            positions = nextPositions
            backPointers.add(back)
            positionHistory.add(positions.copyOf())
        }

        var bestCandidate = -1
        var bestCost = Double.POSITIVE_INFINITY
        for (candidateIndex in costs.indices) {
            if (costs[candidateIndex] < bestCost) {
                bestCost = costs[candidateIndex]
                bestCandidate = candidateIndex
            }
        }
        if (bestCandidate < 0) return null

        val progress = DoubleArray(indices.size)
        var candidateIndex = bestCandidate
        for (position in indices.indices.reversed()) {
            progress[position] = positionHistory[position][candidateIndex]
            if (position > 0) {
                candidateIndex = backPointers[position][candidateIndex]
                if (candidateIndex < 0) return null
            }
        }
        return DirectionAlignment(progress, direction, bestCost)
    }

    /**
     * Weak absolute check: the travelled source distance predicts where on the
     * reference the point should be. It is tolerant to sensor drift, but a
     * branch thousands of metres away is heavily penalised. This is what stops
     * a point from flipping to a parallel part of a self-overlapping route just
     * because that part happens to be a few centimetres closer.
     */
    private fun branchPhaseCost(s: Double, expectedS: Double, path: RefPath): Double {
        var difference = abs(s - expectedS)
        if (path.closed) {
            difference %= path.totalLength
            if (difference > path.totalLength * 0.5) {
                difference = path.totalLength - difference
            }
        }
        return if (difference > PHASE_TOLERANCE_METERS) {
            (difference - PHASE_TOLERANCE_METERS) * PHASE_WEIGHT
        } else {
            0.0
        }
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
        medianSpacing: Double,
        sourcePrefix: DoubleArray
    ): Double {
        if (left != null && right != null) {
            if (blockSensorDistanceUsable(input, block, left, right)) {
                return (input[right].cumulativeDistanceMeters!! -
                    input[left].cumulativeDistanceMeters!!).coerceAtLeast(10.0)
            }
            val source = sourcePrefix[right] - sourcePrefix[left]
            if (source >= 10.0) return source
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
        threshold: Double,
        sourcePrefix: DoubleArray,
        distance: DistanceAlignment?,
        progress: ProgressAlignment
    ): Boolean {
        val distanceLeftS = distance?.progressAt(sourcePrefix[left])
        val distanceRightS = distance?.progressAt(sourcePrefix[right])
        if (distanceLeftS != null && distanceRightS != null) {
            val path = reference.paths[0]
            val leftProjection = reference.nearest(
                lat[left]!!, lon[left]!!, max(100.0, threshold * 2.0)
            )
            val rightProjection = reference.nearest(
                lat[right]!!, lon[right]!!, max(100.0, threshold * 2.0)
            )
            val snappedLeftS = if (leftProjection != null) {
                snapProjectionToLap(path, distanceLeftS, leftProjection.s)
            } else {
                distanceLeftS
            }
            val snappedRightS = if (rightProjection != null) {
                snapProjectionToLap(path, distanceRightS, rightProjection.s)
            } else {
                distanceRightS
            }
            // Prefer the real GPS anchors when they describe a plausible
            // movement. The distance alignment can be a hundred metres off
            // after a long recording, and forcing its absolute position would
            // create a jump at the edge of the repaired block.
            var actualSpan = if (leftProjection != null && rightProjection != null) {
                rightProjection.s - leftProjection.s
            } else {
                null
            }
            if (actualSpan != null && path.closed) {
                actualSpan %= path.totalLength
                if (actualSpan > path.totalLength * 0.5) {
                    actualSpan -= path.totalLength
                } else if (actualSpan < -path.totalLength * 0.5) {
                    actualSpan += path.totalLength
                }
            }
            val tolerance = max(150.0, expected * 0.75)
            val useActual = actualSpan != null &&
                leftProjection != null &&
                abs(abs(actualSpan) - expected) <= tolerance
            val leftS = if (useActual) leftProjection!!.s else snappedLeftS
            val span = if (useActual) actualSpan!! else snappedRightS - snappedLeftS
            val plausible = abs(abs(span) - expected) <= tolerance
            if (plausible) {
                val startTime = input[left].time
                val endTime = input[right].time
                val useDistance = blockSensorDistanceUsable(input, block, left, right)
                val startDistance = input[left].cumulativeDistanceMeters
                val endDistance = input[right].cumulativeDistanceMeters
                for (index in block) {
                    val fraction = fractionBetween(
                        index, left, right, input, startTime, endTime,
                        useDistance, startDistance, endDistance, sourcePrefix
                    )
                    val s = leftS + span * fraction
                    val vertex = path.pointAt(s, wrap = path.closed)
                    applyVertex(index, vertex, lat, lon, ele, coordinateChanged, elevationFilled)
                }
                return true
            }
        }

        val progressPathIndex = progress.pathIndex[left]
        val leftS = progress.s[left]
        val rightS = progress.s[right]
        if (progressPathIndex >= 0 &&
            progressPathIndex == progress.pathIndex[right] &&
            leftS != null &&
            rightS != null
        ) {
            val span = rightS - leftS
            val ordered = if (progress.direction > 0) span >= -1.0 else span <= 1.0
            // The DP already follows the travelled distance. If its span does
            // not match this block, the alignment is not trustworthy here and
            // the old local candidate search is used instead.
            val plausible = abs(abs(span) - expected) <= max(150.0, expected * 0.75)
            if (ordered && plausible) {
                val path = reference.paths[progressPathIndex]
                val startTime = input[left].time
                val endTime = input[right].time
                val useDistance = blockSensorDistanceUsable(input, block, left, right)
                val startDistance = input[left].cumulativeDistanceMeters
                val endDistance = input[right].cumulativeDistanceMeters
                for (index in block) {
                    val fraction = fractionBetween(
                        index, left, right, input, startTime, endTime,
                        useDistance, startDistance, endDistance, sourcePrefix
                    )
                    val s = leftS + span * fraction
                    val vertex = path.pointAt(s, wrap = path.closed)
                    applyVertex(index, vertex, lat, lon, ele, coordinateChanged, elevationFilled)
                }
                return true
            }
        }

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
                useDistance, startDistance, endDistance, sourcePrefix
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
        clampEdges: Boolean,
        sourcePrefix: DoubleArray,
        distanceAlignment: DistanceAlignment?,
        progress: ProgressAlignment,
        frozen: BooleanArray
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
            maxCandidates = 50
        )
        if (candidates.isEmpty()) return false
        val distanceAnchorS = distanceAlignment?.progressAt(sourcePrefix[anchor])
        // At the very start or end of an open reference the global distance
        // alignment can be shifted by a length mismatch and would invent
        // positions before the route start. Keep the local anchor projection
        // there, as the source distance is still reliable next to the anchor.
        val useDistanceAlignment = distanceAnchorS != null &&
            !(edgeBlock && !reference.paths[0].closed)
        // A stadium reference repeats the same physical loop many times, so
        // several copies can be within a couple of metres of one GPS fix. The
        // nearest copy may belong to an earlier lap. Choose the copy closest
        // to the distance-aligned progress among the physically close ones;
        // this keeps the join continuous and the lap count correct.
        val projection = if (useDistanceAlignment) {
            chooseAnchorProjection(candidates, distanceAnchorS!!, reference.paths[0])
        } else {
            candidates.minByOrNull { it.distance }
        } ?: return false
        val progressPathIndex = progress.pathIndex[anchor]
        val progressAnchorS = progress.s[anchor]
        val useProgress = !useDistanceAlignment &&
            progressPathIndex >= 0 &&
            progressAnchorS != null
        val path = when {
            useDistanceAlignment -> reference.paths[0]
            useProgress -> reference.paths[progressPathIndex]
            else -> reference.paths[projection.pathIndex]
        }
        val anchorS = when {
            useDistanceAlignment -> projection.s
            useProgress -> progressAnchorS!!
            else -> projection.s
        }
        val moveSign = if (useProgress) progress.direction * direction else direction
        val scale = when {
            useDistanceAlignment -> distanceAlignment!!.scale
            useProgress -> localProgressScale(
                progress, input, sourcePrefix, anchor, direction, path
            )
            else -> 1.0
        }
        val anchorTime = input[anchor].time
        val anchorDistance = input[anchor].cumulativeDistanceMeters
        val useDistance = oneSideSensorDistanceUsable(input, block, anchor, direction)
        // A leading or trailing GPS gap should begin/end at the activity's
        // start line, not wherever the accumulated source distance happens to
        // drift. This also makes a closed route finish exactly one reference
        // length after its start instead of stopping in the middle of a lap.
        val edgeCandidateS = if (useDistanceAlignment && path.closed) {
            when {
                block.first == 0 && direction < 0 -> 0.0
                block.last == input.size - 1 && direction > 0 -> path.totalLength
                else -> null
            }
        } else {
            null
        }
        // Never pull an edge block backwards just to hit the seam: only use
        // the edge target when it lies in the direction of travel.
        val edgeTargetS = if (edgeCandidateS != null &&
            (edgeCandidateS - anchorS) * direction > 0.0
        ) {
            edgeCandidateS
        } else {
            null
        }
        val edgeEndIndex = when {
            edgeTargetS == null -> -1
            block.first == 0 && direction < 0 -> block.first
            else -> block.last
        }
        val edgeSourceSpan = if (edgeEndIndex >= 0) {
            abs(sourcePrefix[edgeEndIndex] - sourcePrefix[anchor])
        } else {
            0.0
        }
        // Only accept a tiny overshoot for a non-frozen edge block. Larger
        // extrapolation would invent a route beyond the reference.
        val edgeTolerance = 1.0
        var repairedAny = false

        for (index in block) {
            val distance = if (useDistance && anchorDistance != null) {
                abs(anchorDistance - input[index].cumulativeDistanceMeters!!)
            } else {
                val source = abs(sourcePrefix[index] - sourcePrefix[anchor])
                if (source > 0.0) {
                    source
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
            }
            var targetS = if (edgeTargetS != null && edgeSourceSpan > 1.0) {
                val fraction = (
                    abs(sourcePrefix[index] - sourcePrefix[anchor]) / edgeSourceSpan
                ).coerceIn(0.0, 1.0)
                anchorS + (edgeTargetS - anchorS) * fraction
            } else if (useDistanceAlignment) {
                val ownS = distanceAlignment!!.progressAt(sourcePrefix[index])
                if (abs(ownS - anchorS) < 0.5 && distance > 1.0) {
                    anchorS + direction * distance * scale
                } else {
                    ownS
                }
            } else {
                anchorS + moveSign * distance * scale
            }
            if (!useDistanceAlignment && useProgress) {
                val ownS = progress.s[index]
                val ownPathIndex = progress.pathIndex[index]
                val followsDirection = ownS != null && if (moveSign > 0) {
                    ownS >= anchorS - 1.0
                } else {
                    ownS <= anchorS + 1.0
                }
                // A frozen run repeats one coordinate while the recorder still
                // reports movement, so its own projection would pile every
                // point onto one spot. Spread it with the travelled distance
                // instead. The same applies to points without coordinates.
                val ownProjectionUsable = ownS != null &&
                    ownPathIndex == progressPathIndex &&
                    !frozen[index] &&
                    followsDirection &&
                    abs(ownS - anchorS) <= distance * 4.5 + 300.0
                if (ownProjectionUsable) {
                    targetS = ownS
                }
            }
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
            val vertex = path.pointAt(targetS, wrap = path.closed)
            applyVertex(index, vertex, lat, lon, ele, coordinateChanged, elevationFilled)
            repairedAny = true
        }
        return repairedAny
    }

    /**
     * Estimates how many metres of reference progress correspond to one metre
     * of source distance close to the anchor. The global ratio can be skewed by
     * a long frozen section, while the neighbouring good points describe the
     * local scale of the current lap.
     */
    private fun localProgressScale(
        progress: ProgressAlignment,
        input: List<TrackPoint>,
        sourcePrefix: DoubleArray,
        anchor: Int,
        direction: Int,
        path: RefPath
    ): Double {
        val anchorS = progress.s[anchor] ?: return progress.scale
        val anchorPathIndex = progress.pathIndex[anchor]
        val step = -direction
        var index = anchor + step
        var checked = 0
        while (index in input.indices && checked < 20) {
            val s = progress.s[index]
            if (s != null && progress.pathIndex[index] == anchorPathIndex) {
                val source = abs(sourcePrefix[anchor] - sourcePrefix[index])
                var span = abs(anchorS - s)
                if (path.closed) {
                    span %= path.totalLength
                    if (span > path.totalLength * 0.5) span = path.totalLength - span
                }
                if (source >= 5.0 && span >= 1.0) {
                    return (span / source).coerceIn(0.25, 4.0)
                }
            }
            index += step
            checked++
        }
        return progress.scale
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
        endDistance: Double?,
        sourcePrefix: DoubleArray
    ): Double {
        val pointDistance = input[index].cumulativeDistanceMeters
        if (useDistance && startDistance != null && endDistance != null && pointDistance != null) {
            val total = endDistance - startDistance
            val current = pointDistance - startDistance
            if (total > 0.0) return (current / total).coerceIn(0.0, 1.0)
        }
        val sourceTotal = sourcePrefix[right] - sourcePrefix[left]
        if (sourceTotal >= 10.0) {
            val sourceCurrent = sourcePrefix[index] - sourcePrefix[left]
            return (sourceCurrent / sourceTotal).coerceIn(0.0, 1.0)
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
