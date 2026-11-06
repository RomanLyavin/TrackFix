package com.trackfix.app.core

import java.time.Duration
import java.time.Instant
import java.util.Locale

private const val FIT_EPOCH_SECONDS = 631065600L
private const val REFERENCE_JUMP_SPLIT_METERS = 250.0
private const val REFERENCE_TIME_SPLIT_SECONDS = 120L
private val TIMER_STOP_EVENT_TYPES = setOf(1, 4, 8, 9)

data class FitRecord(
    val timestamp: Instant?,
    val lat: Double?,
    val lon: Double?,
    val ele: Double?,
    val speedMetersPerSecond: Double?,
    val cumulativeDistanceMeters: Double?,
    val heartRate: Int?,
    val cadence: Int?,
    val power: Int?,
    val temperature: Double?,
    val gradePercent: Double?,
    val gpsAccuracyMeters: Double?,
    val verticalSpeedMetersPerSecond: Double?,
    val segmentIndex: Int
) {
    fun toTrackPoint(): TrackPoint = TrackPoint(
        lat = lat,
        lon = lon,
        ele = ele,
        time = timestamp,
        speedMetersPerSecond = speedMetersPerSecond,
        cumulativeDistanceMeters = cumulativeDistanceMeters
    )
}

data class FitEvent(
    val timestamp: Instant?,
    val event: Int?,
    val eventType: Int?
)

data class FitSession(
    val startTime: Instant?,
    val sport: Int?,
    val subSport: Int?,
    val totalDistanceMeters: Double?
)

data class FitFile(
    val records: List<FitRecord>,
    val events: List<FitEvent>,
    val session: FitSession?
) {
    fun trackPoints(): List<TrackPoint> = records.map { it.toTrackPoint() }
}

private data class FieldDefinition(
    val number: Int,
    val size: Int,
    val baseType: Int
)

private data class MessageDefinition(
    val globalNumber: Int,
    val bigEndian: Boolean,
    val fields: List<FieldDefinition>,
    val developerFields: List<FieldDefinition>
)

private data class ParsedMessage(
    val endOffset: Int,
    val numericValues: Map<Int, Long>,
    val compressedSpeedDistance: Long?,
    val timestampSeconds: Long?
)

/**
 * Small dependency-free FIT activity parser. It only decodes what TrackFix
 * needs for a track: position, time, altitude, distance, speed and common
 * sensor fields. Other messages and developer fields are read and skipped so
 * the data message boundaries stay correct.
 */
object FitCodec {

    fun looksLikeFit(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val headerSize = bytes[0].toInt() and 0xFF
        if (headerSize < 12 || headerSize > bytes.size) return false
        return bytes[8] == '.'.code.toByte() &&
            bytes[9] == 'F'.code.toByte() &&
            bytes[10] == 'I'.code.toByte() &&
            bytes[11] == 'T'.code.toByte()
    }

    fun parse(bytes: ByteArray): FitFile {
        if (bytes.isEmpty()) throw GpxException("empty FIT file")
        if (bytes.size > GpxCodec.MAX_FILE_BYTES) throw GpxException("file too large")
        if (!looksLikeFit(bytes)) throw GpxException("not a FIT file")

        val headerSize = u8(bytes, 0)
        val dataSize = readRaw(bytes, 4, 4, bigEndian = false)
        if (dataSize <= 0L || dataSize > Int.MAX_VALUE) {
            throw GpxException("invalid FIT data size")
        }
        val dataStart = headerSize
        val dataEnd = dataStart + dataSize.toInt()
        if (dataEnd > bytes.size) throw GpxException("truncated FIT file")

        val definitions = HashMap<Int, MessageDefinition>()
        val records = ArrayList<FitRecord>()
        val events = ArrayList<FitEvent>()
        var session: FitSession? = null

        var segmentIndex = 0
        var segmentHasRecords = false
        var compressedTimestamp = 0L
        var compressedDistance = 0L
        var offset = dataStart

        while (offset < dataEnd) {
            val header = u8(bytes, offset)
            offset++

            if (header and 0x80 != 0) {
                // Compressed timestamp header: bits 5-6 select the local
                // message type and bits 0-4 carry the low timestamp bits.
                val localType = (header shr 5) and 0x03
                val timeOffset = (header and 0x1F).toLong()
                val definition = definitions[localType]
                    ?: throw GpxException("FIT data message without definition")
                val headerTimestamp = applyCompressedTimestamp(
                    timeOffset,
                    compressedTimestamp,
                    5
                )
                val parsed = readDataMessage(bytes, offset, dataEnd, definition)
                offset = parsed.endOffset
                val explicitTimestamp = parsed.timestampSeconds
                if (explicitTimestamp != null) {
                    compressedTimestamp = explicitTimestamp
                } else {
                    compressedTimestamp = headerTimestamp
                }
                val timestamp = instantOrNull(explicitTimestamp ?: headerTimestamp)

                when (definition.globalNumber) {
                    GLOBAL_RECORD -> {
                        val result = buildRecord(
                            parsed,
                            timestamp,
                            segmentIndex,
                            compressedDistance
                        )
                        records.add(result.record)
                        compressedDistance = result.compressedDistance
                        segmentHasRecords = true
                    }
                    GLOBAL_EVENT -> {
                        val event = buildEvent(parsed, timestamp)
                        events.add(event)
                        if (isTimerStop(event) && segmentHasRecords) {
                            segmentIndex++
                            segmentHasRecords = false
                        }
                    }
                    GLOBAL_SESSION -> if (session == null) {
                        session = buildSession(parsed)
                    }
                }
            } else {
                val localType = header and 0x0F
                if (header and 0x40 != 0) {
                    val hasDeveloperFields = header and 0x20 != 0
                    offset = readDefinition(
                        bytes,
                        offset,
                        dataEnd,
                        localType,
                        hasDeveloperFields,
                        definitions
                    )
                } else {
                    val definition = definitions[localType]
                        ?: throw GpxException("FIT data message without definition")
                    val parsed = readDataMessage(bytes, offset, dataEnd, definition)
                    offset = parsed.endOffset
                    val explicitTimestamp = parsed.timestampSeconds
                    if (explicitTimestamp != null) {
                        compressedTimestamp = explicitTimestamp
                    }
                    val timestamp = instantOrNull(explicitTimestamp)

                    when (definition.globalNumber) {
                        GLOBAL_RECORD -> {
                            val result = buildRecord(
                                parsed,
                                timestamp,
                                segmentIndex,
                                compressedDistance
                            )
                            records.add(result.record)
                            compressedDistance = result.compressedDistance
                            segmentHasRecords = true
                        }
                        GLOBAL_EVENT -> {
                            val event = buildEvent(parsed, timestamp)
                            events.add(event)
                            if (isTimerStop(event) && segmentHasRecords) {
                                segmentIndex++
                                segmentHasRecords = false
                            }
                        }
                        GLOBAL_SESSION -> if (session == null) {
                            session = buildSession(parsed)
                        }
                    }
                }
            }

            if (offset > dataEnd) throw GpxException("FIT message exceeds data size")
        }

        if (records.isEmpty()) throw GpxException("FIT file has no track records")
        return FitFile(records = records, events = events, session = session)
    }

    /**
     * Converts FIT records back to a normal GPX DOM so the existing repair and
     * serialization pipeline can be reused. Sensor fields are written to the
     * same extensions the GPX recordings use.
     */
    fun toGpx(file: FitFile, reference: Boolean = false, sourceName: String? = null): GpxFile {
        val xml = StringBuilder(64 * 1024)
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        xml.append("<gpx version=\"1.1\" creator=\"TrackFix\"")
        xml.append(" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        xml.append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v2\">")

        val firstTime = file.records.firstOrNull { it.timestamp != null }?.timestamp
        if (firstTime != null) {
            xml.append("<metadata><time>").append(firstTime).append("</time></metadata>")
        }

        xml.append("<trk>")
        val name = sourceName?.let { baseName(it) }
        if (!name.isNullOrBlank()) {
            xml.append("<name>").append(escapeXml(name)).append("</name>")
        }
        sportName(file.session?.sport)?.let {
            xml.append("<type>").append(it).append("</type>")
        }

        var openSegment = false
        var currentSegment = Int.MIN_VALUE
        var previous: FitRecord? = null
        for (record in file.records) {
            val split = !openSegment ||
                record.segmentIndex != currentSegment ||
                (reference && referenceSplitNeeded(previous, record))
            if (split) {
                if (openSegment) xml.append("</trkseg>")
                xml.append("<trkseg>")
                openSegment = true
                currentSegment = record.segmentIndex
            }
            appendPoint(xml, record)
            previous = record
        }
        if (openSegment) xml.append("</trkseg>")
        xml.append("</trk>")
        xml.append("</gpx>")

        return GpxCodec.parse(
            xml.toString().toByteArray(Charsets.UTF_8),
            reference = false
        )
    }

    private fun readDefinition(
        bytes: ByteArray,
        start: Int,
        end: Int,
        localType: Int,
        hasDeveloperFields: Boolean,
        definitions: MutableMap<Int, MessageDefinition>
    ): Int {
        if (start + 5 > end) throw GpxException("truncated FIT definition")
        val architecture = u8(bytes, start + 1)
        val bigEndian = architecture != 0
        val globalNumber = readRaw(bytes, start + 2, 2, bigEndian).toInt()
        val fieldCount = u8(bytes, start + 4)
        var offset = start + 5

        val fields = ArrayList<FieldDefinition>(fieldCount)
        repeat(fieldCount) {
            if (offset + 3 > end) throw GpxException("truncated FIT field definition")
            val number = u8(bytes, offset)
            val size = u8(bytes, offset + 1)
            val baseType = u8(bytes, offset + 2)
            if (size <= 0) {
                throw GpxException(
                    "invalid FIT field size $size for field $number " +
                        "in message $globalNumber/$localType at $offset"
                )
            }
            fields.add(FieldDefinition(number, size, baseType))
            offset += 3
        }

        val developerFields = ArrayList<FieldDefinition>()
        if (hasDeveloperFields) {
            if (offset >= end) throw GpxException("truncated FIT developer definition")
            val count = u8(bytes, offset)
            offset++
            repeat(count) {
                if (offset + 3 > end) {
                    throw GpxException("truncated FIT developer field definition")
                }
                val number = u8(bytes, offset)
                val size = u8(bytes, offset + 1)
                if (size <= 0) throw GpxException("invalid FIT developer field size")
                developerFields.add(FieldDefinition(number, size, 0))
                offset += 3
            }
        }

        definitions[localType] = MessageDefinition(
            globalNumber = globalNumber,
            bigEndian = bigEndian,
            fields = fields,
            developerFields = developerFields
        )
        return offset
    }

    private fun readDataMessage(
        bytes: ByteArray,
        start: Int,
        end: Int,
        definition: MessageDefinition
    ): ParsedMessage {
        var offset = start
        val values = HashMap<Int, Long>(definition.fields.size)
        var timestampSeconds: Long? = null
        var compressedSpeedDistance: Long? = null

        for (field in definition.fields) {
            val fieldEnd = offset + field.size
            if (fieldEnd > end) throw GpxException("truncated FIT data message")

            if (field.number == TIMESTAMP_FIELD) {
                timestampSeconds = decodeField(bytes, offset, field, definition.bigEndian)
            } else if (field.number == COMPRESSED_SPEED_DISTANCE_FIELD && field.size == 3) {
                val allInvalid = (0 until field.size).all {
                    (bytes[offset + it].toInt() and 0xFF) == 0xFF
                }
                if (!allInvalid) {
                    compressedSpeedDistance = readRaw(
                        bytes,
                        offset,
                        field.size,
                        definition.bigEndian
                    )
                }
            } else {
                val value = decodeField(bytes, offset, field, definition.bigEndian)
                if (value != null) values[field.number] = value
            }
            offset = fieldEnd
        }

        for (field in definition.developerFields) {
            val fieldEnd = offset + field.size
            if (fieldEnd > end) throw GpxException("truncated FIT developer data")
            offset = fieldEnd
        }

        return ParsedMessage(
            endOffset = offset,
            numericValues = values,
            compressedSpeedDistance = compressedSpeedDistance,
            timestampSeconds = timestampSeconds
        )
    }

    private data class RecordBuild(
        val record: FitRecord,
        val compressedDistance: Long
    )

    private fun buildRecord(
        message: ParsedMessage,
        timestamp: Instant?,
        segmentIndex: Int,
        compressedDistance: Long
    ): RecordBuild {
        val values = message.numericValues
        val lat = values[FIELD_LAT]?.let { semicirclesToDegrees(it) }
        val lon = values[FIELD_LON]?.let { semicirclesToDegrees(it) }
        val altitude = values[FIELD_ENHANCED_ALTITUDE] ?: values[FIELD_ALTITUDE]
        val speed = values[FIELD_ENHANCED_SPEED] ?: values[FIELD_SPEED]
        val distance = values[FIELD_DISTANCE]

        var compressedSpeed: Double? = null
        var newCompressedDistance = compressedDistance
        val compressed = message.compressedSpeedDistance
        if (compressed != null) {
            val speedRaw = compressed and 0xFFF
            val distanceRaw = (compressed shr 12) and 0xFFF
            compressedSpeed = speedRaw / 100.0
            newCompressedDistance = applyCompressedTimestamp(distanceRaw, compressedDistance, 12)
        }

        return RecordBuild(
            record = FitRecord(
                timestamp = timestamp,
                lat = lat,
                lon = lon,
                ele = altitude?.let { it / 5.0 - 500.0 },
                speedMetersPerSecond = speed?.let { it / 1000.0 } ?: compressedSpeed,
                cumulativeDistanceMeters = distance?.let { it / 100.0 } ?: run {
                    if (compressed != null) newCompressedDistance / 16.0 else null
                },
                heartRate = values[FIELD_HEART_RATE]?.toInt(),
                cadence = values[FIELD_CADENCE]?.toInt(),
                power = values[FIELD_POWER]?.toInt(),
                temperature = values[FIELD_TEMPERATURE]?.toDouble(),
                gradePercent = values[FIELD_GRADE]?.let { it / 100.0 },
                gpsAccuracyMeters = values[FIELD_GPS_ACCURACY]?.toDouble(),
                verticalSpeedMetersPerSecond = values[FIELD_VERTICAL_SPEED]?.let {
                    it / 1000.0
                },
                segmentIndex = segmentIndex
            ),
            compressedDistance = newCompressedDistance
        )
    }

    private fun buildEvent(message: ParsedMessage, timestamp: Instant?): FitEvent {
        return FitEvent(
            timestamp = timestamp,
            event = message.numericValues[EVENT_FIELD_EVENT]?.toInt(),
            eventType = message.numericValues[EVENT_FIELD_TYPE]?.toInt()
        )
    }

    private fun isTimerStop(event: FitEvent): Boolean {
        return event.event == 0 && event.eventType in TIMER_STOP_EVENT_TYPES
    }

    private fun buildSession(message: ParsedMessage): FitSession {
        val values = message.numericValues
        return FitSession(
            startTime = values[SESSION_START_TIME]?.let { instantOrNull(it) },
            sport = values[SESSION_SPORT]?.toInt(),
            subSport = values[SESSION_SUB_SPORT]?.toInt(),
            totalDistanceMeters = values[SESSION_TOTAL_DISTANCE]?.let { it / 100.0 }
        )
    }

    private fun appendPoint(xml: StringBuilder, record: FitRecord) {
        xml.append("<trkpt")
        if (record.lat != null && record.lon != null) {
            xml.append(" lat=\"")
                .append(GpxCodec.formatCoordinate(record.lat))
                .append("\" lon=\"")
                .append(GpxCodec.formatCoordinate(record.lon))
                .append('"')
        }
        xml.append('>')

        record.ele?.let {
            xml.append("<ele>").append(GpxCodec.formatElevation(it)).append("</ele>")
        }
        record.timestamp?.let {
            xml.append("<time>").append(it).append("</time>")
        }

        val hasDistance = record.cumulativeDistanceMeters != null
        val hasPower = record.power != null
        val hasTpx = record.heartRate != null ||
            record.cadence != null ||
            record.speedMetersPerSecond != null ||
            record.temperature != null
        if (hasDistance || hasPower || hasTpx) {
            xml.append("<extensions>")
            if (hasDistance) {
                xml.append("<distance>")
                    .append(formatNumber(record.cumulativeDistanceMeters!!, 2))
                    .append("</distance>")
            }
            if (hasPower) {
                xml.append("<power>").append(record.power!!).append("</power>")
            }
            if (hasTpx) {
                xml.append("<gpxtpx:TrackPointExtension>")
                record.heartRate?.let {
                    xml.append("<gpxtpx:hr>").append(it).append("</gpxtpx:hr>")
                }
                record.cadence?.let {
                    xml.append("<gpxtpx:cad>").append(it).append("</gpxtpx:cad>")
                }
                record.speedMetersPerSecond?.let {
                    xml.append("<gpxtpx:speed>")
                        .append(formatNumber(it, 3))
                        .append("</gpxtpx:speed>")
                }
                record.temperature?.let {
                    xml.append("<gpxtpx:atemp>")
                        .append(formatNumber(it, 1))
                        .append("</gpxtpx:atemp>")
                }
                xml.append("</gpxtpx:TrackPointExtension>")
            }
            xml.append("</extensions>")
        }
        xml.append("</trkpt>")
    }

    private fun referenceSplitNeeded(previous: FitRecord?, current: FitRecord): Boolean {
        if (previous == null) return false
        val previousTime = previous.timestamp
        val currentTime = current.timestamp
        if (previousTime != null && currentTime != null) {
            val seconds = Duration.between(previousTime, currentTime).seconds
            if (seconds < 0L || seconds > REFERENCE_TIME_SPLIT_SECONDS) return true
        }
        if (previous.lat != null && previous.lon != null &&
            current.lat != null && current.lon != null &&
            Geo.distanceMeters(
                previous.lat, previous.lon,
                current.lat, current.lon
            ) > REFERENCE_JUMP_SPLIT_METERS
        ) {
            return true
        }
        return false
    }

    private fun decodeField(
        bytes: ByteArray,
        offset: Int,
        field: FieldDefinition,
        bigEndian: Boolean
    ): Long? {
        val baseType = field.baseType and 0x1F
        if (baseType == BASE_TYPE_STRING || baseType == BASE_TYPE_FLOAT32 ||
            baseType == BASE_TYPE_FLOAT64
        ) {
            return null
        }
        val typeSize = baseTypeSize(baseType)
        if (typeSize <= 0 || field.size < typeSize) return null
        val raw = readRaw(bytes, offset, typeSize, bigEndian)
        if (isInvalid(baseType, raw, typeSize)) return null
        return if (isSigned(baseType)) signExtend(raw, typeSize) else raw
    }

    private fun baseTypeSize(baseType: Int): Int = when (baseType) {
        BASE_TYPE_ENUM, BASE_TYPE_SINT8, BASE_TYPE_UINT8, BASE_TYPE_STRING,
        BASE_TYPE_UINT8Z, BASE_TYPE_BYTE -> 1
        BASE_TYPE_SINT16, BASE_TYPE_UINT16, BASE_TYPE_UINT16Z -> 2
        BASE_TYPE_SINT32, BASE_TYPE_UINT32, BASE_TYPE_FLOAT32, BASE_TYPE_UINT32Z -> 4
        BASE_TYPE_FLOAT64, BASE_TYPE_SINT64, BASE_TYPE_UINT64, BASE_TYPE_UINT64Z -> 8
        else -> 0
    }

    private fun isSigned(baseType: Int): Boolean {
        return baseType == BASE_TYPE_SINT8 ||
            baseType == BASE_TYPE_SINT16 ||
            baseType == BASE_TYPE_SINT32 ||
            baseType == BASE_TYPE_SINT64
    }

    private fun isInvalid(baseType: Int, raw: Long, size: Int): Boolean {
        val bits = size * 8
        val unsignedMax = if (bits >= 64) -1L else (1L shl bits) - 1L
        return when (baseType) {
            BASE_TYPE_ENUM, BASE_TYPE_UINT8, BASE_TYPE_BYTE -> raw == unsignedMax
            BASE_TYPE_SINT8 -> raw == 0x7FL
            BASE_TYPE_SINT16 -> raw == 0x7FFFL
            BASE_TYPE_SINT32 -> raw == 0x7FFFFFFFL
            BASE_TYPE_SINT64 -> raw == Long.MAX_VALUE
            BASE_TYPE_UINT16, BASE_TYPE_UINT32, BASE_TYPE_FLOAT32,
            BASE_TYPE_FLOAT64, BASE_TYPE_UINT64 -> raw == unsignedMax
            BASE_TYPE_UINT8Z, BASE_TYPE_UINT16Z, BASE_TYPE_UINT32Z,
            BASE_TYPE_UINT64Z -> raw == 0L
            else -> true
        }
    }

    private fun signExtend(value: Long, size: Int): Long {
        if (size >= 8) return value
        val shift = 64 - size * 8
        return (value shl shift) shr shift
    }

    private fun readRaw(
        bytes: ByteArray,
        offset: Int,
        size: Int,
        bigEndian: Boolean
    ): Long {
        if (size <= 0 || size > 8 || offset < 0 || offset + size > bytes.size) {
            throw GpxException("truncated FIT value")
        }
        var value = 0L
        if (bigEndian) {
            for (index in 0 until size) {
                value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
            }
        } else {
            for (index in size - 1 downTo 0) {
                value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
            }
        }
        return value
    }

    private fun u8(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset >= bytes.size) throw GpxException("truncated FIT file")
        return bytes[offset].toInt() and 0xFF
    }

    private fun semicirclesToDegrees(raw: Long): Double {
        return raw * (180.0 / 2147483648.0)
    }

    private fun applyCompressedTimestamp(
        raw: Long,
        accumulator: Long,
        bits: Int
    ): Long {
        val maxValue = 1L shl bits
        val mask = maxValue - 1L
        var result = raw + (accumulator and mask.inv())
        if (raw < (accumulator and mask)) result += maxValue
        return result
    }

    private fun instantOrNull(seconds: Long?): Instant? {
        if (seconds == null) return null
        return try {
            Instant.ofEpochSecond(FIT_EPOCH_SECONDS + seconds)
        } catch (e: Exception) {
            null
        }
    }

    private fun sportName(sport: Int?): String? = when (sport) {
        1 -> "Running"
        2 -> "Biking"
        5 -> "Swimming"
        6 -> "Walking"
        else -> null
    }

    private fun baseName(name: String): String? {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
        val base = if (clean.contains('.')) clean.substringBeforeLast('.') else clean
        return base.takeIf { it.isNotBlank() }
    }

    private fun escapeXml(value: String): String {
        val builder = StringBuilder(value.length)
        for (character in value) {
            when (character) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&apos;")
                else -> {
                    if (character.code >= 0x20 || character == '\t' ||
                        character == '\n' || character == '\r'
                    ) {
                        builder.append(character)
                    }
                }
            }
        }
        return builder.toString()
    }

    private fun formatNumber(value: Double, decimals: Int): String {
        if (!value.isFinite()) return "0"
        val text = String.format(Locale.US, "%.${decimals}f", value)
        if (decimals == 0) return text
        val trimmed = text.trimEnd('0').trimEnd('.')
        return trimmed.ifEmpty { "0" }
    }

    private const val TIMESTAMP_FIELD = 253
    private const val COMPRESSED_SPEED_DISTANCE_FIELD = 8

    private const val GLOBAL_FILE_ID = 0
    private const val GLOBAL_SESSION = 18
    private const val GLOBAL_RECORD = 20
    private const val GLOBAL_EVENT = 21

    private const val FIELD_LAT = 0
    private const val FIELD_LON = 1
    private const val FIELD_ALTITUDE = 2
    private const val FIELD_HEART_RATE = 3
    private const val FIELD_CADENCE = 4
    private const val FIELD_DISTANCE = 5
    private const val FIELD_SPEED = 6
    private const val FIELD_POWER = 7
    private const val FIELD_GRADE = 9
    private const val FIELD_TEMPERATURE = 13
    private const val FIELD_GPS_ACCURACY = 31
    private const val FIELD_VERTICAL_SPEED = 32
    private const val FIELD_ENHANCED_SPEED = 73
    private const val FIELD_ENHANCED_ALTITUDE = 78

    private const val EVENT_FIELD_EVENT = 0
    private const val EVENT_FIELD_TYPE = 1

    private const val SESSION_START_TIME = 2
    private const val SESSION_SPORT = 5
    private const val SESSION_SUB_SPORT = 6
    private const val SESSION_TOTAL_DISTANCE = 9

    private const val BASE_TYPE_ENUM = 0x00
    private const val BASE_TYPE_SINT8 = 0x01
    private const val BASE_TYPE_UINT8 = 0x02
    private const val BASE_TYPE_SINT16 = 0x03
    private const val BASE_TYPE_UINT16 = 0x04
    private const val BASE_TYPE_SINT32 = 0x05
    private const val BASE_TYPE_UINT32 = 0x06
    private const val BASE_TYPE_STRING = 0x07
    private const val BASE_TYPE_FLOAT32 = 0x08
    private const val BASE_TYPE_FLOAT64 = 0x09
    private const val BASE_TYPE_UINT8Z = 0x0A
    private const val BASE_TYPE_UINT16Z = 0x0B
    private const val BASE_TYPE_UINT32Z = 0x0C
    private const val BASE_TYPE_BYTE = 0x0D
    private const val BASE_TYPE_SINT64 = 0x0E
    private const val BASE_TYPE_UINT64 = 0x0F
    private const val BASE_TYPE_UINT64Z = 0x10
}
