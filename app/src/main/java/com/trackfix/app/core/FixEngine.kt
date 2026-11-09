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
            TrackFixer(reference, settings).fix(points)
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
