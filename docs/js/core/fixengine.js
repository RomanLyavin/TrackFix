// Port of app/src/main/java/com/trackfix/app/core/FixEngine.kt.
// Browser ES module, no build step and no npm dependencies.

import { Geo } from './geo.js';
import { GpxException, applyFixes, parseGpx, referencePaths, serializeGpx } from './gpx.js';
import { parseFit, fitToGpx } from './fit.js';
import { ReferencePath } from './reference.js';
import { fixTrack } from './trackfixer.js';

const MAX_FILE_BYTES = 30 * 1024 * 1024;

function toBytes(value) {
  if (typeof value === 'string') return new TextEncoder().encode(value);
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (Array.isArray(value)) return Uint8Array.from(value);
  throw new GpxException('unsupported input data');
}

function decodeUtf8(bytes) {
  return new TextDecoder('utf-8').decode(bytes);
}

/**
 * Kotlin FitCodec.looksLikeFit: header size in 12..fileSize and ".FIT" at 8.
 */
function looksLikeFit(bytes) {
  if (bytes.length < 12) return false;
  const headerSize = bytes[0];
  if (headerSize < 12 || headerSize > bytes.length) return false;
  return bytes[8] === 0x2e && bytes[9] === 0x46 &&
    bytes[10] === 0x49 && bytes[11] === 0x54;
}

function sourceNameOf(name) {
  return typeof name === 'string' ? name : '';
}

function hasExtension(name, extension) {
  return typeof name === 'string' && new RegExp(`\\.${extension}\\s*$`, 'i').test(name);
}

/**
 * Both GPX and FIT are accepted in either slot. FIT is converted to an
 * in-memory GPX document first, so the rest of the pipeline stays the same.
 */
export function parseTrack(bytes, name, { reference = false } = {}) {
  const data = toBytes(bytes);
  if (data.length === 0) throw new GpxException('empty file');
  if (data.length > MAX_FILE_BYTES) throw new GpxException('file too large');

  const sourceName = sourceNameOf(name);
  if (looksLikeFit(data) || hasExtension(sourceName, 'fit')) {
    const fit = parseFit(data);
    return fitToGpx(fit, { sourceName, reference });
  }
  return parseGpx(decodeUtf8(data), { reference, sourceName });
}

export function inspectOriginal(bytes, name) {
  const file = parseTrack(bytes, name, { reference: false });
  const points = (file && file.points) || [];
  const times = points
    .map((point) => point.timeMs)
    .filter((time) => typeof time === 'number' && Number.isFinite(time));

  let durationSeconds = null;
  if (times.length > 0) {
    const first = times[0];
    const last = times[times.length - 1];
    if (last >= first) durationSeconds = Math.floor((last - first) / 1000.0);
  }

  let distanceMeters = 0.0;
  for (let index = 1; index < points.length; index++) {
    const previous = points[index - 1];
    const current = points[index];
    if (!Geo.isValidCoordinate(previous.lat ?? null, previous.lon ?? null) ||
        !Geo.isValidCoordinate(current.lat ?? null, current.lon ?? null)) continue;
    distanceMeters += Geo.distanceMeters(previous.lat, previous.lon, current.lat, current.lon);
  }

  return {
    points: points.length,
    durationSeconds,
    distanceMeters
  };
}

function suggestedName(originalName, suffix) {
  const clean = sourceNameOf(originalName).split(/[/\\]/).pop() || '';
  const dot = clean.lastIndexOf('.');
  const base = dot >= 0 ? clean.slice(0, dot) : clean;
  return `${base || 'track'}${suffix}.gpx`;
}

export function process(originalBytes, originalName, referenceBytes, referenceName, settings) {
  const original = parseTrack(originalBytes, originalName, { reference: false });
  const referenceFile = parseTrack(referenceBytes, referenceName, { reference: true });
  if (!original.points || original.points.length < 2) {
    throw new GpxException('original track is too short');
  }

  const paths = referencePaths(referenceFile);
  let reference;
  try {
    reference = new ReferencePath(paths);
  } catch (error) {
    throw new GpxException('reference has fewer than two valid points');
  }

  const result = fixTrack(original.points, reference, settings || {});
  // applyFixes changes only lat/lon/ele. Point count, order, time, sensors,
  // <type> and every other part of the source document remain untouched.
  applyFixes(original, result);
  const outputText = serializeGpx(original);

  return {
    outputText,
    suggestedName: suggestedName(originalName, '_fixed'),
    stats: result.stats,
    warnings: result.warnings,
    referenceLengthMeters: reference.totalLength
  };
}
