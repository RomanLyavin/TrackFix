/**
 * Dependency-free FIT activity decoder for the browser web core.
 * Ported from app/src/main/java/com/trackfix/app/core/FitCodec.kt.
 *
 * Only two exports are public: parseFit() and fitToGpx(). No DOM, no npm,
 * no build step. DataView works on the original Uint8Array view, so byte
 * offsets and unaligned reads are handled explicitly.
 */

const GPX_NAMESPACE_URI = 'http://www.topografix.com/GPX/1/1';
const FIT_EPOCH_MS = 631065600000; // 1989-12-31T00:00:00Z
const MAX_FILE_BYTES = 30 * 1024 * 1024;
const MAX_INT32 = 0x7fffffff;
const EARTH_RADIUS_M = 6371008.8;
const REFERENCE_JUMP_SPLIT_METERS = 250.0;
const REFERENCE_TIME_SPLIT_SECONDS = 120;
const TIMER_STOP_EVENT_TYPES = new Set([1, 4, 8, 9]);

const TIMESTAMP_FIELD = 253;
const COMPRESSED_SPEED_DISTANCE_FIELD = 8;

const GLOBAL_SESSION = 18;
const GLOBAL_RECORD = 20;
const GLOBAL_EVENT = 21;

const FIELD_LAT = 0;
const FIELD_LON = 1;
const FIELD_ALTITUDE = 2;
const FIELD_HEART_RATE = 3;
const FIELD_CADENCE = 4;
const FIELD_DISTANCE = 5;
const FIELD_SPEED = 6;
const FIELD_POWER = 7;
const FIELD_GRADE = 9;
const FIELD_TEMPERATURE = 13;
const FIELD_GPS_ACCURACY = 31;
const FIELD_VERTICAL_SPEED = 32;
const FIELD_ENHANCED_SPEED = 73;
const FIELD_ENHANCED_ALTITUDE = 78;

const EVENT_FIELD_EVENT = 0;
const EVENT_FIELD_TYPE = 1;

const SESSION_START_TIME = 2;
const SESSION_SPORT = 5;
const SESSION_SUB_SPORT = 6;
const SESSION_TOTAL_DISTANCE = 9;

const BASE_TYPE_ENUM = 0x00;
const BASE_TYPE_SINT8 = 0x01;
const BASE_TYPE_UINT8 = 0x02;
const BASE_TYPE_SINT16 = 0x03;
const BASE_TYPE_UINT16 = 0x04;
const BASE_TYPE_SINT32 = 0x05;
const BASE_TYPE_UINT32 = 0x06;
const BASE_TYPE_STRING = 0x07;
const BASE_TYPE_FLOAT32 = 0x08;
const BASE_TYPE_FLOAT64 = 0x09;
const BASE_TYPE_UINT8Z = 0x0a;
const BASE_TYPE_UINT16Z = 0x0b;
const BASE_TYPE_UINT32Z = 0x0c;
const BASE_TYPE_BYTE = 0x0d;
const BASE_TYPE_SINT64 = 0x0e;
const BASE_TYPE_UINT64 = 0x0f;
const BASE_TYPE_UINT64Z = 0x10;

/** Same name/shape as the other web-core errors, but kept module-local. */
class FitException extends Error {
  constructor(message) {
    super(message);
    this.name = 'GpxException';
    this.reason = message;
  }
}

function fail(message) {
  throw new FitException(message);
}

// ---------------------------------------------------------------------------
// Binary helpers
// ---------------------------------------------------------------------------

function asBytes(bytes) {
  if (bytes instanceof Uint8Array) return bytes;
  if (bytes instanceof ArrayBuffer) return new Uint8Array(bytes);
  if (ArrayBuffer.isView(bytes)) {
    return new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  }
  return fail('not a FIT file');
}

function viewOf(bytes) {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
}

function looksLikeFit(view) {
  if (view.byteLength < 12) return false;
  const headerSize = view.getUint8(0);
  if (headerSize < 12 || headerSize > view.byteLength) return false;
  return (
    view.getUint8(8) === 0x2e && // '.'
    view.getUint8(9) === 0x46 && // 'F'
    view.getUint8(10) === 0x49 && // 'I'
    view.getUint8(11) === 0x54 // 'T'
  );
}

/**
 * Kotlin readRaw(): 1/2/4/8 bytes go through DataView; 3-byte fields (the
 * compressed speed/distance record) are assembled explicitly. The result is
 * BigInt so the 64-bit invalid-value checks match Kotlin's Long semantics.
 */
function readRaw(view, offset, size, bigEndian) {
  if (size <= 0 || size > 8 || offset < 0 || offset + size > view.byteLength) {
    fail('truncated FIT value');
  }
  const littleEndian = !bigEndian;
  if (size === 1) return BigInt(view.getUint8(offset));
  if (size === 2) return BigInt(view.getUint16(offset, littleEndian));
  if (size === 4) return BigInt(view.getUint32(offset, littleEndian));
  if (size === 8) return view.getBigUint64(offset, littleEndian);

  let value = 0n;
  if (bigEndian) {
    for (let i = 0; i < size; i++) {
      value = (value << 8n) | BigInt(view.getUint8(offset + i));
    }
  } else {
    for (let i = size - 1; i >= 0; i--) {
      value = (value << 8n) | BigInt(view.getUint8(offset + i));
    }
  }
  return value;
}

function baseTypeSize(baseType) {
  switch (baseType) {
    case BASE_TYPE_ENUM:
    case BASE_TYPE_SINT8:
    case BASE_TYPE_UINT8:
    case BASE_TYPE_STRING:
    case BASE_TYPE_UINT8Z:
    case BASE_TYPE_BYTE:
      return 1;
    case BASE_TYPE_SINT16:
    case BASE_TYPE_UINT16:
    case BASE_TYPE_UINT16Z:
      return 2;
    case BASE_TYPE_SINT32:
    case BASE_TYPE_UINT32:
    case BASE_TYPE_FLOAT32:
    case BASE_TYPE_UINT32Z:
      return 4;
    case BASE_TYPE_FLOAT64:
    case BASE_TYPE_SINT64:
    case BASE_TYPE_UINT64:
    case BASE_TYPE_UINT64Z:
      return 8;
    default:
      return 0;
  }
}

function isSigned(baseType) {
  return (
    baseType === BASE_TYPE_SINT8 ||
    baseType === BASE_TYPE_SINT16 ||
    baseType === BASE_TYPE_SINT32 ||
    baseType === BASE_TYPE_SINT64
  );
}

function isInvalid(baseType, raw, size) {
  // Kotlin reads into a signed Long: all-one 64-bit values become -1.
  const kotlinRaw = BigInt.asIntN(64, raw);
  const unsignedMax = size >= 8 ? -1n : (1n << BigInt(size * 8)) - 1n;
  switch (baseType) {
    case BASE_TYPE_ENUM:
    case BASE_TYPE_UINT8:
    case BASE_TYPE_BYTE:
      return kotlinRaw === unsignedMax;
    case BASE_TYPE_SINT8:
      return kotlinRaw === 0x7fn;
    case BASE_TYPE_SINT16:
      return kotlinRaw === 0x7fffn;
    case BASE_TYPE_SINT32:
      return kotlinRaw === 0x7fffffffn;
    case BASE_TYPE_SINT64:
      return kotlinRaw === 0x7fffffffffffffffn;
    case BASE_TYPE_UINT16:
    case BASE_TYPE_UINT32:
    case BASE_TYPE_FLOAT32:
    case BASE_TYPE_FLOAT64:
    case BASE_TYPE_UINT64:
      return kotlinRaw === unsignedMax;
    case BASE_TYPE_UINT8Z:
    case BASE_TYPE_UINT16Z:
    case BASE_TYPE_UINT32Z:
    case BASE_TYPE_UINT64Z:
      return kotlinRaw === 0n;
    default:
      return true;
  }
}

/**
 * Kotlin decodeField(): strings and floats are deliberately not interpreted,
 * byte arrays are read one byte at a time, and invalid values become null.
 */
function decodeField(view, offset, field, bigEndian) {
  const baseType = field.baseType & 0x1f;
  if (
    baseType === BASE_TYPE_STRING ||
    baseType === BASE_TYPE_FLOAT32 ||
    baseType === BASE_TYPE_FLOAT64
  ) {
    return null;
  }
  const typeSize = baseTypeSize(baseType);
  if (typeSize <= 0 || field.size < typeSize) return null;

  const raw = readRaw(view, offset, typeSize, bigEndian);
  if (isInvalid(baseType, raw, typeSize)) return null;

  const signed = isSigned(baseType) ? BigInt.asIntN(typeSize * 8, raw) : raw;
  return Number(BigInt.asIntN(64, signed));
}

function readDefinition(view, start, end, localType, hasDeveloperFields, definitions) {
  if (start + 5 > end) fail('truncated FIT definition');
  const architecture = view.getUint8(start + 1);
  const bigEndian = architecture !== 0;
  const globalNumber = Number(readRaw(view, start + 2, 2, bigEndian));
  const fieldCount = view.getUint8(start + 4);
  let offset = start + 5;

  const fields = [];
  for (let i = 0; i < fieldCount; i++) {
    if (offset + 3 > end) fail('truncated FIT field definition');
    const number = view.getUint8(offset);
    const size = view.getUint8(offset + 1);
    const baseType = view.getUint8(offset + 2);
    if (size <= 0) {
      fail(
        `invalid FIT field size ${size} for field ${number} ` +
          `in message ${globalNumber}/${localType} at ${offset}`
      );
    }
    fields.push({ number, size, baseType });
    offset += 3;
  }

  const developerFields = [];
  if (hasDeveloperFields) {
    if (offset >= end) fail('truncated FIT developer definition');
    const count = view.getUint8(offset);
    offset++;
    for (let i = 0; i < count; i++) {
      if (offset + 3 > end) fail('truncated FIT developer field definition');
      const number = view.getUint8(offset);
      const size = view.getUint8(offset + 1);
      if (size <= 0) fail('invalid FIT developer field size');
      // Kotlin stores developer fields with baseType 0 and only skips them.
      developerFields.push({ number, size, baseType: 0 });
      offset += 3;
    }
  }

  definitions.set(localType, { globalNumber, bigEndian, fields, developerFields });
  return offset;
}

function readDataMessage(view, start, end, definition) {
  let offset = start;
  const values = new Map();
  let timestampSeconds = null;
  let compressedSpeedDistance = null;

  for (const field of definition.fields) {
    const fieldEnd = offset + field.size;
    if (fieldEnd > end) fail('truncated FIT data message');

    if (field.number === TIMESTAMP_FIELD) {
      timestampSeconds = decodeField(view, offset, field, definition.bigEndian);
    } else if (field.number === COMPRESSED_SPEED_DISTANCE_FIELD && field.size === 3) {
      let allInvalid = true;
      for (let i = 0; i < field.size; i++) {
        if (view.getUint8(offset + i) !== 0xff) {
          allInvalid = false;
          break;
        }
      }
      if (!allInvalid) {
        compressedSpeedDistance = readRaw(view, offset, field.size, definition.bigEndian);
      }
    } else {
      const value = decodeField(view, offset, field, definition.bigEndian);
      if (value !== null) values.set(field.number, value);
    }
    offset = fieldEnd;
  }

  for (const field of definition.developerFields) {
    const fieldEnd = offset + field.size;
    if (fieldEnd > end) fail('truncated FIT developer data');
    offset = fieldEnd;
  }

  return { endOffset: offset, values, compressedSpeedDistance, timestampSeconds };
}

// ---------------------------------------------------------------------------
// Message decoding
// ---------------------------------------------------------------------------

function applyCompressedTimestamp(raw, accumulator, bits) {
  const maxValue = 1n << BigInt(bits);
  const mask = maxValue - 1n;
  let result = raw + (accumulator & ~mask);
  if (raw < (accumulator & mask)) result += maxValue;
  return result;
}

function instantOrNull(seconds) {
  if (seconds === null || seconds === undefined) return null;
  const value = Number(seconds);
  if (!Number.isFinite(value)) return null;
  const ms = FIT_EPOCH_MS + value * 1000;
  // Keep time representable as an ISO string as well as a number.
  if (!Number.isFinite(ms) || Math.abs(ms) > 8.64e15) return null;
  return ms;
}

function semicirclesToDegrees(raw) {
  return Number(raw) * (180.0 / 2147483648.0);
}

function firstValue(values, ...numbers) {
  for (const number of numbers) {
    if (values.has(number)) return values.get(number);
  }
  return null;
}

function toIntOrNull(value) {
  if (value === null || value === undefined || !Number.isFinite(value)) return null;
  return value | 0;
}

function buildRecord(message, timestamp, segmentIndex, compressedDistance) {
  const values = message.values;
  const latValue = firstValue(values, FIELD_LAT);
  const lonValue = firstValue(values, FIELD_LON);
  const altitude = firstValue(values, FIELD_ENHANCED_ALTITUDE, FIELD_ALTITUDE);
  const speed = firstValue(values, FIELD_ENHANCED_SPEED, FIELD_SPEED);
  const distance = firstValue(values, FIELD_DISTANCE);

  let compressedSpeed = null;
  let newCompressedDistance = compressedDistance;
  if (message.compressedSpeedDistance !== null) {
    const compressed = message.compressedSpeedDistance;
    const speedRaw = compressed & 0xfffn;
    const distanceRaw = (compressed >> 12n) & 0xfffn;
    compressedSpeed = Number(speedRaw) / 100.0;
    newCompressedDistance = applyCompressedTimestamp(distanceRaw, compressedDistance, 12);
  }

  const record = {
    lat: latValue === null ? null : semicirclesToDegrees(latValue),
    lon: lonValue === null ? null : semicirclesToDegrees(lonValue),
    ele: altitude === null ? null : altitude / 5.0 - 500.0,
    timeMs: timestamp,
    speed: speed !== null ? speed / 1000.0 : compressedSpeed,
    distance:
      distance !== null
        ? distance / 100.0
        : message.compressedSpeedDistance !== null
          ? Number(newCompressedDistance) / 16.0
          : null,
    cadence: toIntOrNull(firstValue(values, FIELD_CADENCE)),
    hr: toIntOrNull(firstValue(values, FIELD_HEART_RATE)),
    power: toIntOrNull(firstValue(values, FIELD_POWER)),
    temperature: firstValue(values, FIELD_TEMPERATURE),
    grade: firstValue(values, FIELD_GRADE) === null
      ? null
      : firstValue(values, FIELD_GRADE) / 100.0,
    gpsAccuracy: firstValue(values, FIELD_GPS_ACCURACY),
    verticalSpeed: firstValue(values, FIELD_VERTICAL_SPEED) === null
      ? null
      : firstValue(values, FIELD_VERTICAL_SPEED) / 1000.0,
    segmentIndex
  };
  return { record, compressedDistance: newCompressedDistance };
}

function buildEvent(message, timestamp) {
  return {
    timestamp,
    event: toIntOrNull(firstValue(message.values, EVENT_FIELD_EVENT)),
    eventType: toIntOrNull(firstValue(message.values, EVENT_FIELD_TYPE))
  };
}

function isTimerStop(event) {
  return event.event === 0 && TIMER_STOP_EVENT_TYPES.has(event.eventType);
}

function buildSession(message) {
  const values = message.values;
  const startTime = firstValue(values, SESSION_START_TIME);
  return {
    startTime: startTime === null ? null : instantOrNull(BigInt(Math.trunc(startTime))),
    sport: toIntOrNull(firstValue(values, SESSION_SPORT)),
    subSport: toIntOrNull(firstValue(values, SESSION_SUB_SPORT)),
    totalDistanceMeters: firstValue(values, SESSION_TOTAL_DISTANCE) === null
      ? null
      : firstValue(values, SESSION_TOTAL_DISTANCE) / 100.0
  };
}

function sportName(sport) {
  switch (sport) {
    case 1:
      return 'Running';
    case 2:
      return 'Biking';
    case 5:
      return 'Swimming';
    case 6:
      return 'Walking';
    default:
      return null;
  }
}

/**
 * Decode a FIT activity.
 * @param {Uint8Array} bytes
 * @returns {{ records: object[], sport: string|null }}
 */
export function parseFit(bytes) {
  const input = asBytes(bytes);
  if (input.length === 0) fail('empty FIT file');
  if (input.length > MAX_FILE_BYTES) fail('file too large');

  const view = viewOf(input);
  if (!looksLikeFit(view)) fail('not a FIT file');

  const headerSize = view.getUint8(0);
  const dataSize = Number(readRaw(view, 4, 4, false));
  if (!Number.isSafeInteger(dataSize) || dataSize <= 0 || dataSize > MAX_INT32) {
    fail('invalid FIT data size');
  }
  const dataStart = headerSize;
  const dataEnd = dataStart + dataSize;
  if (dataEnd > view.byteLength) fail('truncated FIT file');

  const definitions = new Map();
  const records = [];
  const events = [];
  let session = null;
  let segmentIndex = 0;
  let segmentHasRecords = false;
  let compressedTimestamp = 0n;
  let compressedDistance = 0n;
  let offset = dataStart;

  const consumeMessage = (definition, parsed, timestamp) => {
    switch (definition.globalNumber) {
      case GLOBAL_RECORD: {
        const built = buildRecord(parsed, timestamp, segmentIndex, compressedDistance);
        records.push(built.record);
        compressedDistance = built.compressedDistance;
        segmentHasRecords = true;
        break;
      }
      case GLOBAL_EVENT: {
        const event = buildEvent(parsed, timestamp);
        events.push(event);
        if (isTimerStop(event) && segmentHasRecords) {
          segmentIndex++;
          segmentHasRecords = false;
        }
        break;
      }
      case GLOBAL_SESSION:
        if (session === null) session = buildSession(parsed);
        break;
      default:
        break;
    }
  };

  while (offset < dataEnd) {
    const header = view.getUint8(offset);
    offset++;

    if ((header & 0x80) !== 0) {
      // Compressed timestamp header: bits 5-6 select the local message type,
      // bits 0-4 carry the low timestamp bits.
      const localType = (header >> 5) & 0x03;
      const timeOffset = BigInt(header & 0x1f);
      const definition = definitions.get(localType);
      if (!definition) fail('FIT data message without definition');

      const headerTimestamp = applyCompressedTimestamp(timeOffset, compressedTimestamp, 5);
      const parsed = readDataMessage(view, offset, dataEnd, definition);
      offset = parsed.endOffset;

      const explicitTimestamp = parsed.timestampSeconds;
      if (explicitTimestamp !== null) {
        compressedTimestamp = BigInt(Math.trunc(explicitTimestamp));
      } else {
        compressedTimestamp = headerTimestamp;
      }
      consumeMessage(
        definition,
        parsed,
        instantOrNull(explicitTimestamp !== null ? compressedTimestamp : headerTimestamp)
      );
    } else {
      const localType = header & 0x0f;
      if ((header & 0x40) !== 0) {
        const hasDeveloperFields = (header & 0x20) !== 0;
        offset = readDefinition(
          view,
          offset,
          dataEnd,
          localType,
          hasDeveloperFields,
          definitions
        );
      } else {
        const definition = definitions.get(localType);
        if (!definition) fail('FIT data message without definition');
        const parsed = readDataMessage(view, offset, dataEnd, definition);
        offset = parsed.endOffset;

        const explicitTimestamp = parsed.timestampSeconds;
        if (explicitTimestamp !== null) {
          compressedTimestamp = BigInt(Math.trunc(explicitTimestamp));
        }
        consumeMessage(definition, parsed, instantOrNull(explicitTimestamp));
      }
    }

    if (offset > dataEnd) fail('FIT message exceeds data size');
  }

  if (records.length === 0) fail('FIT file has no track records');
  return { records, sport: session === null ? null : sportName(session.sport) };
}

// ---------------------------------------------------------------------------
// GPX conversion (string based, no DOMParser)
// ---------------------------------------------------------------------------

function baseName(name) {
  if (name === null || name === undefined) return null;
  const clean = String(name).split('/').pop().split('\\').pop();
  const base = clean.includes('.') ? clean.slice(0, clean.lastIndexOf('.')) : clean;
  return base.trim().length > 0 ? base : null;
}

function escapeXml(value) {
  const text = String(value);
  let result = '';
  for (const character of text) {
    switch (character) {
      case '&':
        result += '&amp;';
        break;
      case '<':
        result += '&lt;';
        break;
      case '>':
        result += '&gt;';
        break;
      case '"':
        result += '&quot;';
        break;
      case "'":
        result += '&apos;';
        break;
      default: {
        const code = character.codePointAt(0);
        if (code >= 0x20 || character === '\t' || character === '\n' || character === '\r') {
          result += character;
        }
      }
    }
  }
  return result;
}

function formatNumber(value, decimals) {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '0';
  const text = Number(value).toFixed(decimals);
  if (decimals === 0) return text;
  const trimmed = text.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed.length === 0 || trimmed === '-' ? '0' : trimmed;
}

function formatElevation(value) {
  return formatNumber(value, 2);
}

function formatInstant(ms) {
  if (ms === null || ms === undefined || !Number.isFinite(Number(ms))) return null;
  const date = new Date(Number(ms));
  if (Number.isNaN(date.getTime())) return null;
  // Kotlin Instant.toString() omits trailing zeros in the fractional part.
  return date.toISOString().replace(/\.(\d{3})Z$/, (_match, fraction) => {
    const trimmed = fraction.replace(/0+$/, '');
    return trimmed.length === 0 ? 'Z' : `.${trimmed}Z`;
  });
}

function distanceMeters(lat1, lon1, lat2, lon2) {
  const p1 = (lat1 * Math.PI) / 180;
  const p2 = (lat2 * Math.PI) / 180;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(p1) * Math.cos(p2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function referenceSplitNeeded(previous, current) {
  if (previous === null || previous === undefined) return false;
  if (previous.timeMs !== null && previous.timeMs !== undefined &&
      current.timeMs !== null && current.timeMs !== undefined) {
    const seconds = Math.floor((current.timeMs - previous.timeMs) / 1000);
    if (seconds < 0 || seconds > REFERENCE_TIME_SPLIT_SECONDS) return true;
  }
  if (
    previous.lat !== null && previous.lat !== undefined &&
    previous.lon !== null && previous.lon !== undefined &&
    current.lat !== null && current.lat !== undefined &&
    current.lon !== null && current.lon !== undefined &&
    distanceMeters(previous.lat, previous.lon, current.lat, current.lon) >
      REFERENCE_JUMP_SPLIT_METERS
  ) {
    return true;
  }
  return false;
}

function numOrNull(value) {
  return value === null || value === undefined || !Number.isFinite(Number(value))
    ? null
    : Number(value);
}

function makePoint(record, index, segmentIndex) {
  const lat = numOrNull(record.lat);
  const lon = numOrNull(record.lon);
  const ele = numOrNull(record.ele);
  const speed = numOrNull(record.speed);
  const distance = numOrNull(record.distance);
  const cadence = numOrNull(record.cadence);
  const hr = numOrNull(record.hr);
  const power = numOrNull(record.power);
  const temperature = numOrNull(record.temperature);
  const timeMs = numOrNull(record.timeMs);
  const timeText = formatInstant(timeMs);

  let innerXml = '';
  if (ele !== null) {
    innerXml += `<ele>${escapeXml(formatElevation(ele))}</ele>`;
  }
  if (timeText !== null) {
    innerXml += `<time>${escapeXml(timeText)}</time>`;
  }

  const hasDistance = distance !== null;
  const hasPower = power !== null;
  const hasTpx = hr !== null || cadence !== null || speed !== null || temperature !== null;
  if (hasDistance || hasPower || hasTpx) {
    innerXml += '<extensions>';
    if (hasDistance) {
      innerXml += `<distance>${escapeXml(formatNumber(distance, 2))}</distance>`;
    }
    if (hasPower) {
      innerXml += `<power>${escapeXml(formatNumber(power, 0))}</power>`;
    }
    if (hasTpx) {
      innerXml += '<gpxtpx:TrackPointExtension>';
      if (hr !== null) innerXml += `<gpxtpx:hr>${escapeXml(formatNumber(hr, 0))}</gpxtpx:hr>`;
      if (cadence !== null) {
        innerXml += `<gpxtpx:cad>${escapeXml(formatNumber(cadence, 0))}</gpxtpx:cad>`;
      }
      if (speed !== null) {
        innerXml += `<gpxtpx:speed>${escapeXml(formatNumber(speed, 3))}</gpxtpx:speed>`;
      }
      if (temperature !== null) {
        innerXml += `<gpxtpx:atemp>${escapeXml(formatNumber(temperature, 1))}</gpxtpx:atemp>`;
      }
      innerXml += '</gpxtpx:TrackPointExtension>';
    }
    innerXml += '</extensions>';
  }

  return {
    index,
    segmentIndex,
    lat,
    lon,
    ele,
    timeMs,
    speed,
    distance,
    cadence,
    hr,
    power,
    timeText,
    innerXml
  };
}

/**
 * Convert decoded FIT records into the web-core GpxFile shape.
 * @param {{records: object[], sport: string|null}|Uint8Array} file
 * @param {{sourceName?: string, reference?: boolean}} options
 */
export function fitToGpx(file, { sourceName = '', reference = false } = {}) {
  const parsed = ArrayBuffer.isView(file) || file instanceof ArrayBuffer ? parseFit(file) : file;
  if (!parsed || !Array.isArray(parsed.records)) fail('invalid FIT file');

  const rawSport = parsed.sport;
  const sport = typeof rawSport === 'number' ? sportName(rawSport) : rawSport;
  const name = sourceName ? baseName(sourceName) : null;

  const points = [];
  let openSegment = false;
  let currentSegment = null;
  let emittedSegment = -1;
  let previous = null;

  for (const record of parsed.records) {
    const split =
      !openSegment ||
      record.segmentIndex !== currentSegment ||
      (reference === true && referenceSplitNeeded(previous, record));
    if (split) {
      emittedSegment++;
      currentSegment = record.segmentIndex;
      openSegment = true;
    }
    points.push(makePoint(record, points.length, emittedSegment));
    previous = record;
  }

  return {
    points,
    namespaceUri: GPX_NAMESPACE_URI,
    type: sport || null,
    name: name || null
  };
}
