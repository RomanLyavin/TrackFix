package com.trackfix.app.core

data class TrackInfo(
    val points: Int,
    val durationSeconds: Long?,
    val distanceMeters: Double
)

data class ReferenceInfo(
    val points: Int,
    val distanceMeters: Double,
    val segments: Int
)

data class ProcessResult(
    val outputBytes: ByteArray,
    val suggestedName: String,
    val stats: FixStats,
    val warnings: Set<FixWarning>,
    val referenceLengthMeters: Double
)

object FixEngine {
    fun inspectOriginal(bytes: ByteArray): TrackInfo {
        val file = parseTrack(bytes, reference = false)
        val points = file.trackPoints()
        val duration = points.firstOrNull { it.time != null }?.time?.let { first ->
            points.lastOrNull { it.time != null }?.time?.let { last ->
                if (last.isBefore(first)) null else java.time.Duration.between(first, last).seconds
            }
        }
        return TrackInfo(
            points = points.size,
            durationSeconds = duration,
            distanceMeters = Geo.distanceOf(points)
        )
    }

    fun inspectReference(bytes: ByteArray): ReferenceInfo {
        val file = parseTrack(bytes, reference = true)
        val paths = GpxCodec.referencePaths(file)
        val reference = try {
            ReferencePath(paths)
        } catch (e: Exception) {
            throw GpxException("reference has fewer than two valid points", e)
        }
        return ReferenceInfo(
            points = file.points.count { Geo.isValidCoordinate(it.lat, it.lon) },
            distanceMeters = reference.totalLength,
            segments = reference.paths.size
        )
    }

    fun process(
        originalBytes: ByteArray,
        originalName: String,
        referenceBytes: ByteArray,
        settings: FixSettings
    ): ProcessResult {
        val original = parseTrack(originalBytes, reference = false, sourceName = originalName)
        val referenceFile = parseTrack(referenceBytes, reference = true)
        if (original.points.size < 2) throw GpxException("original track is too short")
        val paths = GpxCodec.referencePaths(referenceFile)
        val reference = try {
            ReferencePath(paths)
        } catch (e: Exception) {
            throw GpxException("reference has fewer than two valid points", e)
        }

        val points = original.trackPoints()
        val hasAnyCoordinate = points.any { it.hasCoordinates }
        val result = if (hasAnyCoordinate) {
            if (settings.overlayOnReference) {
                // Overlay mode: use reference coordinates with source sensors
                overlayOnReference(points, reference, settings)
            } else {
                TrackFixer(reference, settings).fix(points)
            }
        } else {
            // A recording without a single GPS fix cannot be repaired by
            // matching points to the reference. Rebuild the whole route from
            // the reference and the source moving time instead.
            val rebuilt = NoGpsReconstructor.reconstruct(
                points,
                reference,
                settings.fillMissingElevation
            )
            GpxCodec.removeDistanceExtensions(original)
            rebuilt
        }
        GpxCodec.applyFixes(original, result)
        GpxCodec.applyInsertedPoints(original, result.inserted)
        val output = GpxCodec.serialize(original)
        val name = GpxCodec.suggestedName(originalName, "_fixed")

        return ProcessResult(
            outputBytes = output,
            suggestedName = name,
            stats = result.stats,
            warnings = result.warnings,
            referenceLengthMeters = reference.totalLength
        )
    }

    internal fun parseTrackFile(bytes: ByteArray, sourceName: String? = null): GpxFile {
        return parseTrack(bytes, reference = false, sourceName = sourceName)
    }

    /**
     * Overlay mode: use the reference route with the original sensors.
     * The coordinates are from the reference, the distance/speed/time
     * data is from the original file. This is for when the original GPS
     * signal was unstable but the sensor data (distance, speed, time)
     * is reliable.
     */
    private fun overlayOnReference(
        points: List<TrackPoint>,
        reference: ReferencePath,
        settings: FixSettings
    ): FixResult {
        // Use a simple distance-based alignment to map the original sensor
        // data onto the reference route. The reference is assumed to be
        // the correct path; only coordinates are changed, all other values
        // (time, speed, distance) are preserved from the original.
        val n = points.size
        val lat = Array<Double?>(n) { points[it].lat }
        val lon = Array<Double?>(n) { points[it].lon }
        val ele = Array<Double?>(n) { points[it].ele }
        val coordinateChanged = BooleanArray(n)
        val elevationFilled = BooleanArray(n)
        val warnings = mutableSetOf<FixWarning>()
        val stats = FixStats(
            totalPoints = n,
            fixedCoordinates = 0,
            missingCoordinates = 0,
            filledElevations = 0,
            problemBlocks = 0,
            unresolvedPoints = 0,
            maxDeviationBefore = 0.0,
            maxDeviationCapped = false,
            maxJumpBefore = 0.0,
            distanceBefore = 0.0,
            distanceAfter = 0.0,
            speedLimit = settings.maxDeviationMeters.coerceIn(1.0, 500.0),
            frozenRuns = 0,
            frozenCoordinates = 0,
            leadingFixed = 0,
            trailingFixed = 0,
            reconstructedCoordinates = 0,
            insertedPoints = 0
        )
        
        // Compute distance from the start of the original track
        val sourcePrefix = SourceDistance.prefixMeters(points, 3.0)
        val totalSource = sourcePrefix.last() - sourcePrefix.first()
        if (totalSource < 1.0) return FixResult(
            lat = lat,
            lon = lon,
            ele = ele,
            coordinateChanged = coordinateChanged,
            elevationFilled = elevationFilled,
            stats = stats,
            warnings = warnings
        )
        
        // Align the reference to match the source distance
        val path = reference.paths[0]
        val scale = path.totalLength / totalSource
        val baseScale = scale.coerceIn(0.25, 4.0)
        var phase = 0.0
        
        // Find the best phase that aligns the track to the reference
        val coarseStep = max(5.0, path.totalLength / 200.0)
        var bestCost = Double.POSITIVE_INFINITY
        var bestPhase = 0.0
        var step = 0.0
        while (step < path.totalLength) {
            val cost = distanceAlignmentCost(
                path, points, sourcePrefix, step, baseScale
            )
            if (cost < bestCost) {
                bestCost = cost
                bestPhase = step
            }
            step += coarseStep
        }
        phase = bestPhase
        
        // Apply the alignment and map each point to its position on the reference
        for (i in points.indices) {
            val s = phase + (sourcePrefix[i] - sourcePrefix.first()) * baseScale
            val vertex = path.pointAt(s, wrap = path.closed)
            if (vertex.lat.isFinite() && vertex.lon.isFinite()) {
                lat[i] = vertex.lat
                lon[i] = vertex.lon
                coordinateChanged[i] = true
            }
        }
        
        return FixResult(
            lat = lat,
            lon = lon,
            ele = ele,
            coordinateChanged = coordinateChanged,
            elevationFilled = elevationFilled,
            stats = stats.copy(distanceAfter = path.totalLength),
            warnings = warnings
        )
    }

    private fun distanceAlignmentCost(
        path: RefPath,
        points: List<TrackPoint>,
        sourcePrefix: DoubleArray,
        phase: Double,
        scale: Double
    ): Double {
        var cost = 0.0
        val firstDistance = sourcePrefix.first()
        for (index in points.indices) {
            val s = phase + (sourcePrefix[index] - firstDistance) * scale
            val vertex = path.pointAt(s, wrap = path.closed)
            val distance = Geo.distanceMeters(
                points[index].lat!!, points[index].lon!!,
                vertex.lat, vertex.lon
            )
            cost += min(distance, 50.0) // Cap the cost to avoid outliers
        }
        return cost
    }

    /**
     * Both GPX and FIT are accepted in either slot. FIT is converted to an
     * in-memory GPX document first, so the rest of the pipeline stays the same.
     */
    private fun parseTrack(
        bytes: ByteArray,
        reference: Boolean,
        sourceName: String? = null
    ): GpxFile {
        if (!FitCodec.looksLikeFit(bytes)) {
            return GpxCodec.parse(bytes, reference = reference)
        }
        val fit = FitCodec.parse(bytes)
        return FitCodec.toGpx(fit, reference = reference, sourceName = sourceName)
    }
}
