// Port of app/src/main/java/com/trackfix/app/core/MergeEngine.kt.
// Browser ES module, no build step and no npm dependencies.

import { Geo } from './geo.js';
import { baseName, serializeGpx } from './gpx.js';
import { parseTrack } from './fixengine.js';

// Not exported: the contract exposes merge() only. The reason is kept on the
// Error so the UI can map it to the existing strings.xml messages.
const REASON = Object.freeze({
  TOO_FEW_FILES: 'TOO_FEW_FILES',
  NO_TIME: 'NO_TIME',
  NO_COORDINATES: 'NO_COORDINATES',
  EMPTY_RESULT: 'EMPTY_RESULT',
  PARSE_ERROR: 'PARSE_ERROR',
  TRIMMED: 'TRIMMED'
});

class MergeError extends Error {
  constructor(reason, message) {
    super(message || reason);
    this.name = 'MergeError';
    this.reason = reason;
  }
}

function finiteTime(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function distanceOf(file) {
  const points = file.points || [];
  let total = 0;
  let previous = null;
  for (const point of points) {
    if (previous && (previous.segmentIndex == null ? 0 : previous.segmentIndex) ===
        (point.segmentIndex == null ? 0 : point.segmentIndex) &&
        Geo.isValidCoordinate(previous.lat, previous.lon) &&
        Geo.isValidCoordinate(point.lat, point.lon)) {
      total += Geo.distanceMeters(
        previous.lat, previous.lon, point.lat, point.lon
      );
    }
    previous = point;
  }
  return total;
}

function inspect(file, name) {
  const points = file && Array.isArray(file.points) ? file.points : [];
  const times = points
    .map((point) => finiteTime(point.timeMs))
    .filter((time) => time != null);
  if (times.length === 0) {
    throw new MergeError(REASON.NO_TIME, 'no time in ' + name);
  }
  if (!points.some((point) => Geo.isValidCoordinate(point.lat, point.lon))) {
    throw new MergeError(REASON.NO_COORDINATES, 'no coordinates in ' + name);
  }
  return {
    points: points.length,
    startTime: Math.min(...times),
    endTime: Math.max(...times),
    distanceMeters: distanceOf(file)
  };
}

/**
 * Cumulative distance is source-local: each file starts it from zero, so a
 * merged file must drop the old <distance> extension. Only children of
 * <extensions> are removed; every other original XML is kept byte for byte.
 */
function removeStaleDistance(innerXml) {
  if (typeof innerXml !== 'string' || !innerXml) return innerXml;
  const extension = /(?:<)?(?:[\w.-]+:)?extensions\b/i;
  if (!extension.test(innerXml)) return innerXml;
  return innerXml.replace(
    /<([\w.-]+:)?extensions\b([^>]*)>([\s\S]*?)<\/([\w.-]+:)?extensions\s*>/gi,
    (whole, openPrefix, attributes, content) => {
      const cleaned = content
        .replace(/<(?:[\w.-]+:)?distance\b[^>]*\/>/gi, '')
        .replace(
          /<([\w.-]+:)?distance\b[^>]*>[\s\S]*?<\/([\w.-]+:)?distance\s*>/gi,
          ''
        );
      if (!cleaned.trim()) return '';
      return '<' + (openPrefix || '') + 'extensions' + attributes + '>' +
        cleaned + '</' + (openPrefix || '') + 'extensions>';
    }
  );
}

function nameOf(item) {
  return item && typeof item.name === 'string' ? item.name : '';
}

/** Chronological merge of two or more GPX/FIT tracks. */
export function merge(items) {
  if (!Array.isArray(items) || items.length < 2) {
    throw new MergeError(REASON.TOO_FEW_FILES);
  }

  const skippedSources = [];
  const metas = [];
  items.forEach((item, originalIndex) => {
    const name = nameOf(item);
    let file;
    try {
      file = parseTrack(item && item.bytes, name);
    } catch (error) {
      skippedSources.push(name + ' [' + REASON.PARSE_ERROR + ']');
      return;
    }
    try {
      metas.push({
        originalIndex,
        item,
        name,
        file,
        info: inspect(file, name)
      });
    } catch (error) {
      skippedSources.push(
        name + ' [' + (error && error.reason ? error.reason : REASON.PARSE_ERROR) + ']'
      );
    }
  });

  if (metas.length === 0) throw new MergeError(REASON.EMPTY_RESULT);

  metas.sort((a, b) =>
    a.info.startTime - b.info.startTime || a.originalIndex - b.originalIndex
  );
  const base = metas[0];
  // The activity type belongs to the workout. Keep the earliest source's
  // value (1.7.9 fix) instead of letting a later FIT overwrite it.
  const trackType = typeof base.file.type === 'string' && base.file.type.trim()
    ? base.file.type.trim()
    : null;
  const mergedBase = baseName(base.name) || 'track';

  const outputPoints = [];
  let cutoff = null;
  let trimmedPoints = 0;
  let contributedSources = 0;
  let firstTime = null;
  let lastTime = null;
  let totalDistance = 0;
  let previousPoint = null;
  let segmentCount = 0;

  for (const meta of metas) {
    const points = meta.file.points || [];
    const firstIndex = cutoff == null
      ? 0
      : points.findIndex((point) => {
          const time = finiteTime(point.timeMs);
          return time != null && time > cutoff;
        });

    if (firstIndex < 0) {
      trimmedPoints += points.length;
      skippedSources.push(meta.name + ' [' + REASON.TRIMMED + ']');
      continue;
    }
    if (firstIndex > 0) trimmedPoints += firstIndex;

    let keptInSource = 0;
    let currentSourceSegment = null;
    let hasSegment = false;
    previousPoint = null;

    for (let index = firstIndex; index < points.length; index++) {
      const point = points[index];
      const sourceSegment = point.segmentIndex == null ? 0 : point.segmentIndex;
      if (!hasSegment || sourceSegment !== currentSourceSegment) {
        hasSegment = true;
        currentSourceSegment = sourceSegment;
        previousPoint = null;
        segmentCount++;
      }

      outputPoints.push({
        ...point,
        index: outputPoints.length,
        segmentIndex: segmentCount - 1,
        innerXml: removeStaleDistance(point.innerXml)
      });
      keptInSource++;

      const time = finiteTime(point.timeMs);
      if (time != null) {
        if (firstTime == null || time < firstTime) firstTime = time;
        if (lastTime == null || time > lastTime) lastTime = time;
        if (cutoff == null || time > cutoff) cutoff = time;
      }

      if (Geo.isValidCoordinate(point.lat, point.lon)) {
        if (previousPoint && Geo.isValidCoordinate(previousPoint.lat, previousPoint.lon)) {
          totalDistance += Geo.distanceMeters(
            previousPoint.lat, previousPoint.lon, point.lat, point.lon
          );
        }
        previousPoint = point;
      }
    }
    if (keptInSource > 0) contributedSources++;
  }

  if (outputPoints.length === 0) throw new MergeError(REASON.EMPTY_RESULT);

  const merged = {
    namespaceUri: base.file.namespaceUri == null ? null : base.file.namespaceUri,
    type: trackType,
    name: mergedBase + ' (merged)',
    points: outputPoints
  };

  return {
    outputText: serializeGpx(merged),
    suggestedName: mergedBase + '_merged.gpx',
    inputSourceCount: items.length,
    contributedSources,
    totalPoints: outputPoints.length,
    trimmedPoints,
    skippedSources,
    startTimeMs: firstTime,
    endTimeMs: lastTime,
    distanceMeters: totalDistance,
    segmentCount
  };
}
