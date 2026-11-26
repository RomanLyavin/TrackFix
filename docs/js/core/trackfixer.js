// Port of app/src/main/java/com/trackfix/app/core/TrackFixer.kt,
// SourceDistance.kt and NoGpsReconstructor.kt (TrackFix 1.7.9).
// Browser ES module, no dependencies except the sibling geo.js module.

import { Geo } from './geo.js';

export const FIX_WARNINGS = Object.freeze({
  LARGE_CHANGES: 'LARGE_CHANGES',
  NO_REFERENCE_COVERAGE: 'NO_REFERENCE_COVERAGE',
  REFERENCE_SEGMENTS: 'REFERENCE_SEGMENTS',
  NO_GPS_RECONSTRUCTED: 'NO_GPS_RECONSTRUCTED',
  NOTHING_TO_FIX: 'NOTHING_TO_FIX'
});

const FROZEN_MAX_MOVE_METERS = 0.5;
const FROZEN_MIN_SENSOR_DISTANCE_METERS = 20.0;
const TRANSITION_DISTANCE_WEIGHT = 1.0;
const CANDIDATE_DISTANCE_WEIGHT = 2.0;
const PHASE_TOLERANCE_METERS = 120.0;
const PHASE_WEIGHT = 0.1;
const FIRST_POINT_SEARCH_RADIUS_METERS = 600.0;
const DISTANCE_ALIGNMENT_CAP_METERS = 50.0;
const DISTANCE_ALIGNMENT_PHASE_STEPS = 200;
// A long, smooth run away from the reference is a real detour, not a glitch.
// Only short shifted runs may be flattened onto the reference in normal mode.
const MAX_REPAIRED_FAR_RUN_POINTS = 60;

const DEFAULT_SETTINGS = Object.freeze({
  maxDeviationMeters: 30.0,
  fillMissingElevation: true,
  followReference: false,
  maxSpeedMetersPerSecond: 60.0
});

// ---------------------------------------------------------------------------
// Small Kotlin helpers
// ---------------------------------------------------------------------------

function finite(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function max(a, b) {
  return Math.max(a, b);
}

function min(a, b) {
  return Math.min(a, b);
}

function abs(value) {
  return Math.abs(value);
}

function clamp(value, low, high) {
  return min(high, max(low, value));
}

function secondsBetween(firstMs, secondMs) {
  if (!finite(firstMs) || !finite(secondMs)) return null;
  return (secondMs - firstMs) / 1000.0;
}

function validCoordinate(lat, lon) {
  return Geo.isValidCoordinate(lat ?? null, lon ?? null);
}

function pointSpeed(point) {
  const value = point.speed ?? point.speedMetersPerSecond ?? null;
  return finite(value) ? value : null;
}

function pointDistance(point) {
  const value = point.distance ?? point.cumulativeDistanceMeters ?? null;
  return finite(value) ? value : null;
}

function normalizePoint(source) {
  const point = source || {};
  return {
    lat: finite(point.lat) ? point.lat : null,
    lon: finite(point.lon) ? point.lon : null,
    ele: finite(point.ele) ? point.ele : null,
    timeMs: finite(point.timeMs) ? point.timeMs : null,
    speed: pointSpeed(point),
    distance: pointDistance(point),
    cadence: point.cadence ?? null,
    hr: point.hr ?? null,
    power: point.power ?? null
  };
}

function emptyStats() {
  return makeStats({
    totalPoints: 0,
    fixedCoordinates: 0,
    missingCoordinates: 0,
    filledElevations: 0,
    problemBlocks: 0,
    unresolvedPoints: 0,
    maxDeviationBefore: 0.0,
    maxDeviationCapped: false,
    maxJumpBefore: 0.0,
    distanceBefore: 0.0,
    distanceAfter: 0.0,
    speedLimit: 0.0
  });
}

function makeStats(values) {
  return {
    totalPoints: values.totalPoints,
    fixedCoordinates: values.fixedCoordinates,
    missingCoordinates: values.missingCoordinates,
    filledElevations: values.filledElevations,
    problemBlocks: values.problemBlocks,
    unresolvedPoints: values.unresolvedPoints,
    maxDeviationBefore: values.maxDeviationBefore,
    maxDeviationCapped: values.maxDeviationCapped,
    maxJumpBefore: values.maxJumpBefore,
    distanceBefore: values.distanceBefore,
    distanceAfter: values.distanceAfter,
    speedLimit: values.speedLimit,
    frozenRuns: values.frozenRuns ?? 0,
    frozenCoordinates: values.frozenCoordinates ?? 0,
    leadingFixed: values.leadingFixed ?? 0,
    trailingFixed: values.trailingFixed ?? 0,
    reconstructedCoordinates: values.reconstructedCoordinates ?? 0,
    insertedPoints: values.insertedPoints ?? 0
  };
}

function makeResult(lat, lon, ele, coordinateChanged, elevationFilled, stats, warnings) {
  return {
    lat,
    lon,
    ele,
    coordinateChanged,
    elevationFilled,
    stats,
    warnings: Array.from(warnings)
  };
}

function markFartherEndpoint(first, second, deviation, suspicious) {
  if (deviation[first] >= deviation[second]) suspicious[first] = true;
  else suspicious[second] = true;
}

function distanceOfArrays(lat, lon) {
  let total = 0.0;
  for (let index = 1; index < lat.length; index++) {
    if (!validCoordinate(lat[index - 1], lon[index - 1]) ||
        !validCoordinate(lat[index], lon[index])) continue;
    total += Geo.distanceMeters(lat[index - 1], lon[index - 1], lat[index], lon[index]);
  }
  return total;
}

function maxConsecutiveDistance(lat, lon, valid) {
  let maximum = 0.0;
  for (let index = 1; index < lat.length; index++) {
    if (!valid[index - 1] || !valid[index]) continue;
    maximum = max(
      maximum,
      Geo.distanceMeters(lat[index - 1], lon[index - 1], lat[index], lon[index])
    );
  }
  return maximum;
}

function applyVertex(index, vertex, lat, lon, ele, coordinateChanged, elevationFilled) {
  const changed = lat[index] == null ||
    lon[index] == null ||
    abs(lat[index] - vertex.lat) > 1e-9 ||
    abs(lon[index] - vertex.lon) > 1e-9;
  lat[index] = vertex.lat;
  lon[index] = vertex.lon;
  coordinateChanged[index] = coordinateChanged[index] || changed;
  if (ele[index] == null && vertex.ele != null) {
    ele[index] = vertex.ele;
    elevationFilled[index] = true;
  }
}

// ---------------------------------------------------------------------------
// SourceDistance
// ---------------------------------------------------------------------------

const SourceDistance = {
  MAX_INTERVAL_METERS: 5000.0,
  MAX_PLAUSIBLE_SPEED: 35.0,

  validSpeed(speed) {
    return finite(speed) && speed >= 0.0 ? speed : null;
  },

  medianSpeed(points) {
    const speeds = [];
    for (const point of points) {
      const speed = this.validSpeed(point.speed);
      if (speed !== null) speeds.push(speed);
    }
    if (speeds.length === 0) return null;
    speeds.sort((a, b) => a - b);
    const middle = Math.floor(speeds.length / 2);
    if (speeds.length % 2 === 1) return speeds[middle];
    return (speeds[middle - 1] + speeds[middle]) / 2.0;
  },

  /**
   * Distance for every interval. Index i is the distance between points i-1
   * and i; index zero is always zero.
   */
  intervalMeters(points, fallbackSpeed) {
    const result = new Array(points.length).fill(0.0);
    if (points.length < 2) return result;

    const seconds = new Array(points.length).fill(0.0);
    const raw = new Array(points.length).fill(0.0);
    const hasRaw = new Array(points.length).fill(false);

    for (let index = 1; index < points.length; index++) {
      const intervalSeconds = secondsBetween(points[index - 1].timeMs, points[index].timeMs);
      if (intervalSeconds === null || intervalSeconds <= 0.0) continue;
      seconds[index] = intervalSeconds;

      const startDistance = pointDistance(points[index - 1]);
      const endDistance = pointDistance(points[index]);
      if (startDistance !== null && endDistance !== null) {
        const delta = endDistance - startDistance;
        if (delta >= 0.0 &&
            delta <= this.MAX_INTERVAL_METERS &&
            delta <= intervalSeconds * this.MAX_PLAUSIBLE_SPEED) {
          raw[index] = delta;
          hasRaw[index] = true;
          continue;
        }
      }

      const startSpeed = this.validSpeed(pointSpeed(points[index - 1]));
      const endSpeed = this.validSpeed(pointSpeed(points[index]));
      let speed = null;
      if (startSpeed !== null && endSpeed !== null) speed = (startSpeed + endSpeed) / 2.0;
      else if (startSpeed !== null) speed = startSpeed;
      else if (endSpeed !== null) speed = endSpeed;
      if (speed !== null && speed <= this.MAX_PLAUSIBLE_SPEED) {
        raw[index] = speed * intervalSeconds;
        hasRaw[index] = true;
      }
    }

    // A missing speed must not look like a stop. Fill gaps with the rate of
    // the nearest interval that has real data, preferring the closer one.
    for (let index = 1; index < points.length; index++) {
      if (hasRaw[index] || seconds[index] <= 0.0) continue;
      let previous = -1;
      for (let candidate = index - 1; candidate >= 1; candidate--) {
        if (hasRaw[candidate] && seconds[candidate] > 0.0) {
          previous = candidate;
          break;
        }
      }
      let next = -1;
      for (let candidate = index + 1; candidate < points.length; candidate++) {
        if (hasRaw[candidate] && seconds[candidate] > 0.0) {
          next = candidate;
          break;
        }
      }
      let rate;
      if (previous >= 0 && next >= 0) {
        rate = index - previous <= next - index
          ? raw[previous] / seconds[previous]
          : raw[next] / seconds[next];
      } else if (previous >= 0) {
        rate = raw[previous] / seconds[previous];
      } else if (next >= 0) {
        rate = raw[next] / seconds[next];
      } else {
        rate = fallbackSpeed;
      }
      raw[index] = rate * seconds[index];
    }

    for (let index = 1; index < points.length; index++) {
      result[index] = seconds[index] > 0.0 ? max(0.0, raw[index]) : 0.0;
    }
    return result;
  },

  prefixMeters(points, fallbackSpeed) {
    const intervals = this.intervalMeters(points, fallbackSpeed);
    const prefix = new Array(points.length).fill(0.0);
    for (let index = 1; index < points.length; index++) {
      prefix[index] = prefix[index - 1] + intervals[index];
    }
    return prefix;
  }
};

// ---------------------------------------------------------------------------
// NoGpsReconstructor
// ---------------------------------------------------------------------------

const NoGpsReconstructor = {
  PAUSE_SPEED_METERS_PER_SECOND: 0.05,
  GAP_FILL_MIN_SECONDS: 15.0,
  GAP_FILL_MIN_METERS: 30.0,
  GAP_FILL_STEP_METERS: 10.0,

  reconstruct(input, reference, fillMissingElevation) {
    const n = input.length;
    if (n < 2) throw new Error('track is too short');
    let path = null;
    for (const candidate of reference.paths || []) {
      if (path === null || candidate.totalLength > path.totalLength) path = candidate;
    }
    if (!path) throw new Error('reference has no usable path');
    if (path.totalLength <= 1.0) throw new Error('reference is too short');

    const movingSeconds = this.movingSeconds(input);
    if (movingSeconds <= 0.0) throw new Error('track has no moving intervals');
    const averageSpeed = path.totalLength / movingSeconds;

    const detectedSpeed = SourceDistance.medianSpeed(input);
    const fallbackSpeed = detectedSpeed !== null && detectedSpeed > 0.0 ? detectedSpeed : 1.0;
    const intervalMeters = SourceDistance.intervalMeters(input, fallbackSpeed);
    const movingWeights = new Array(n).fill(0.0);
    let totalWeight = 0.0;
    for (let index = 1; index < n; index++) {
      const seconds = secondsBetween(input[index - 1].timeMs, input[index].timeMs);
      if (seconds === null || seconds <= 0.0) continue;
      if (this.isPause(input[index - 1]) && this.isPause(input[index])) continue;
      const weight = intervalMeters[index] > 0.0
        ? intervalMeters[index]
        : seconds * fallbackSpeed;
      movingWeights[index] = weight;
      totalWeight += weight;
    }
    if (totalWeight <= 0.0) {
      // No usable speed or distance data: fall back to moving time.
      for (let index = 1; index < n; index++) {
        const seconds = secondsBetween(input[index - 1].timeMs, input[index].timeMs);
        if (seconds === null || seconds <= 0.0) continue;
        if (this.isPause(input[index - 1]) && this.isPause(input[index])) continue;
        movingWeights[index] = seconds;
        totalWeight += seconds;
      }
    }
    if (totalWeight <= 0.0) throw new Error('track has no moving intervals');
    const metersPerWeight = path.totalLength / totalWeight;

    const lat = new Array(n).fill(null);
    const lon = new Array(n).fill(null);
    const ele = input.map((point) => point.ele);
    const coordinateChanged = new Array(n).fill(true);
    const elevationFilled = new Array(n).fill(false);
    // The web FixResult contract has no `inserted` array, so gap points are
    // computed only for reference-length accounting and are not emitted.
    const warnings = new Set([FIX_WARNINGS.NO_GPS_RECONSTRUCTED]);
    if (reference.paths && reference.paths.length > 1) warnings.add(FIX_WARNINGS.REFERENCE_SEGMENTS);

    let s = 0.0;
    this.apply(path, 0, s, ele, lat, lon, elevationFilled, fillMissingElevation);
    for (let index = 1; index < n; index++) {
      const seconds = secondsBetween(input[index - 1].timeMs, input[index].timeMs);
      const moving = !(this.isPause(input[index - 1]) && this.isPause(input[index]));
      const previousS = s;
      if (moving && seconds !== null && seconds > 0.0) {
        s = min(s + movingWeights[index] * metersPerWeight, path.totalLength);
      }
      if (moving && seconds !== null) {
        this.addGapPoints(
          path, input[index - 1], input[index], index - 1, seconds,
          previousS, s, fillMissingElevation
        );
      }
      this.apply(path, index, s, ele, lat, lon, elevationFilled, fillMissingElevation);
    }

    return makeResult(
      lat, lon, ele, coordinateChanged, elevationFilled,
      makeStats({
        totalPoints: n,
        fixedCoordinates: n,
        missingCoordinates: n,
        filledElevations: elevationFilled.filter(Boolean).length,
        problemBlocks: 1,
        unresolvedPoints: 0,
        maxDeviationBefore: 0.0,
        maxDeviationCapped: false,
        maxJumpBefore: 0.0,
        distanceBefore: distanceOfArrays(input.map((p) => p.lat), input.map((p) => p.lon)),
        distanceAfter: distanceOfArrays(lat, lon),
        speedLimit: averageSpeed,
        reconstructedCoordinates: n,
        insertedPoints: 0
      }),
      warnings
    );
  },

  addGapPoints(path, previous, next, afterIndex, seconds, startS, endS, fillElevation) {
    // Kept for parity with the Kotlin source; the points are not emitted by
    // the web core because output must preserve the source point count.
    if (seconds < this.GAP_FILL_MIN_SECONDS) return [];
    const delta = endS - startS;
    if (delta < this.GAP_FILL_MIN_METERS) return [];
    if (!finite(previous.timeMs) || !finite(next.timeMs)) return [];
    const totalMillis = next.timeMs - previous.timeMs;
    if (totalMillis <= 1) return [];

    const count = Math.floor(delta / this.GAP_FILL_STEP_METERS);
    const inserted = [];
    for (let stepIndex = 1; stepIndex <= count; stepIndex++) {
      const offset = stepIndex * this.GAP_FILL_STEP_METERS;
      if (offset >= delta - 0.05) break;
      const fraction = offset / delta;
      const vertex = path.pointAt(startS + offset, false);
      const millis = clamp(Math.round(totalMillis * fraction), 1, totalMillis - 1);
      inserted.push({
        afterIndex,
        lat: vertex.lat,
        lon: vertex.lon,
        ele: fillElevation ? vertex.ele : null,
        timeMs: previous.timeMs + millis
      });
    }
    return inserted;
  },

  apply(path, index, s, ele, lat, lon, elevationFilled, fillMissingElevation) {
    const vertex = path.pointAt(s, false);
    lat[index] = vertex.lat;
    lon[index] = vertex.lon;
    if (fillMissingElevation && ele[index] == null && vertex.ele != null) {
      ele[index] = vertex.ele;
      elevationFilled[index] = true;
    }
  },

  movingSeconds(input) {
    let total = 0.0;
    for (let index = 1; index < input.length; index++) {
      const seconds = secondsBetween(input[index - 1].timeMs, input[index].timeMs);
      if (seconds === null || seconds <= 0.0) continue;
      if (!this.isPause(input[index - 1]) || !this.isPause(input[index])) total += seconds;
    }
    return total;
  },

  isPause(point) {
    const speed = point.speed;
    if (speed !== null && Number.isFinite(speed)) {
      return speed <= this.PAUSE_SPEED_METERS_PER_SECOND;
    }
    return point.cadence !== null && point.cadence <= 0;
  }
};

// ---------------------------------------------------------------------------
// TrackFixer
// ---------------------------------------------------------------------------

class DistanceAlignment {
  constructor(phase, scale, firstDistance) {
    this.phase = phase;
    this.scale = scale;
    this.firstDistance = firstDistance;
  }

  progressAt(sourceDistance) {
    return this.phase + (sourceDistance - this.firstDistance) * this.scale;
  }
}

class TrackFixer {
  constructor(reference, settings) {
    this.reference = reference;
    this.settings = settings;
  }

  fix(sourceInput) {
    const input = sourceInput.map(normalizePoint);
    const n = input.length;
    const lat = input.map((point) => point.lat);
    const lon = input.map((point) => point.lon);
    const ele = input.map((point) => point.ele);
    const coordinateChanged = new Array(n).fill(false);
    const elevationFilled = new Array(n).fill(false);
    const warnings = new Set();

    // A recording without a single GPS fix cannot be repaired by matching
    // points to the reference. Rebuild the whole route from the reference and
    // the source moving time instead (FixEngine.no-GPS branch).
    const hasAnyCoordinate = input.some((point) => validCoordinate(point.lat, point.lon));
    if (!hasAnyCoordinate && n >= 2) {
      return NoGpsReconstructor.reconstruct(input, this.reference, this.settings.fillMissingElevation);
    }

    if (n === 0) {
      return makeResult(lat, lon, ele, coordinateChanged, elevationFilled, emptyStats(),
        new Set([FIX_WARNINGS.NOTHING_TO_FIX]));
    }

    const valid = input.map((point) => validCoordinate(point.lat, point.lon));
    const threshold = clamp(this.settings.maxDeviationMeters, 1.0, 500.0);
    const searchRadius = max(300.0, threshold * 4.0);
    const distanceBefore = distanceOfArrays(lat, lon);
    const maxJumpBefore = maxConsecutiveDistance(lat, lon, valid);

    const nearest = new Array(n).fill(null);
    const deviation = new Array(n).fill(Number.POSITIVE_INFINITY);
    for (let index = 0; index < n; index++) {
      if (!valid[index]) continue;
      const projection = this.reference.nearest(lat[index], lon[index], searchRadius);
      nearest[index] = projection;
      if (projection !== null) deviation[index] = projection.distance;
    }

    const speeds = [];
    const spacings = [];
    for (let index = 1; index < n; index++) {
      if (!valid[index - 1] || !valid[index]) continue;
      const distance = Geo.distanceMeters(lat[index - 1], lon[index - 1], lat[index], lon[index]);
      if (distance < 5000.0) {
        spacings.push(distance);
        const seconds = secondsBetween(input[index - 1].timeMs, input[index].timeMs);
        if (seconds !== null && seconds > 0.0 && distance > 0.5) {
          const speed = distance / seconds;
          if (speed < this.settings.maxSpeedMetersPerSecond) speeds.push(speed);
        }
      }
    }
    const medianSpeed = Geo.median(speeds) ?? 3.0;
    const speedLimit = clamp(medianSpeed * 3.0, 10.0, 45.0);
    const medianSpacing = Geo.median(spacings.filter((value) => value > 0.5)) ?? 5.0;
    // Distance actually travelled according to the source sensors. It is used
    // to spread a repaired block along the reference in the same proportion
    // as the original intervals (fast/slow blocks stay distinguishable).
    const sourceMedianSpeed = SourceDistance.medianSpeed(input);
    const sourceFallbackSpeed = sourceMedianSpeed !== null && sourceMedianSpeed > 0.0
      ? sourceMedianSpeed
      : medianSpeed;
    const sourcePrefix = SourceDistance.prefixMeters(input, sourceFallbackSpeed);
    const distanceAlignment = this.reference.paths.length === 1
      ? this.computeDistanceAlignment(input, valid, sourcePrefix, this.reference.paths[0])
      : null;
    const progressAlignment = this.computeProgress(input, valid, sourcePrefix, medianSpeed, threshold);

    if (this.settings.followReference) {
      return this.followReference(
        input, valid, lat, lon, ele, coordinateChanged, elevationFilled, warnings,
        progressAlignment, distanceAlignment, nearest, deviation, sourcePrefix,
        threshold, distanceBefore, maxJumpBefore, speedLimit
      );
    }

    const suspicious = new Array(n).fill(false);
    for (let index = 1; index < n; index++) {
      if (!valid[index - 1] || !valid[index]) continue;
      const distance = Geo.distanceMeters(lat[index - 1], lon[index - 1], lat[index], lon[index]);
      if (distance < 10.0) continue;
      const seconds = secondsBetween(input[index - 1].timeMs, input[index].timeMs);
      if (seconds === null || seconds <= 0.0) {
        markFartherEndpoint(index - 1, index, deviation, suspicious);
        continue;
      }
      const speed = distance / seconds;
      if (speed > speedLimit) {
        // A huge spike cannot be real movement. Mark both ends so a stale
        // coordinate cannot survive just because it lies on another branch.
        if (speed > 50.0) {
          suspicious[index - 1] = true;
          suspicious[index] = true;
        } else {
          markFartherEndpoint(index - 1, index, deviation, suspicious);
        }
      }
    }

    const far = new Array(n).fill(false);
    for (let index = 0; index < n; index++) {
      far[index] = valid[index] && deviation[index] > threshold;
    }
    const farRunSize = new Array(n).fill(0);
    let farStart = 0;
    while (farStart < n) {
      if (!far[farStart]) {
        farStart++;
        continue;
      }
      let farEnd = farStart;
      while (farEnd + 1 < n && far[farEnd + 1]) farEnd++;
      const size = farEnd - farStart + 1;
      for (let index = farStart; index <= farEnd; index++) farRunSize[index] = size;
      farStart = farEnd + 1;
    }

    const allFrozenRanges = this.detectFrozenRuns(input, valid, nearest);
    // An interior frozen segment inside a long off-reference section is part
    // of that detour. Only a frozen segment on the route, or a lost tail that
    // has no later GPS fix, may be reconstructed.
    const frozenRanges = allFrozenRanges.filter((range) =>
      range.end === n - 1 ||
      (Number.isFinite(deviation[range.start]) && deviation[range.start] <= threshold * 1.5)
    );
    const frozenFlag = new Array(n).fill(false);
    for (const range of frozenRanges) {
      for (let index = range.start; index <= range.end; index++) frozenFlag[index] = true;
    }
    const bad = new Array(n).fill(false);

    // A teleport produces a short shifted run. Keep repairing such runs as a
    // whole; a much longer smooth run is a real detour and is left alone.
    farStart = 0;
    while (farStart < n) {
      if (!far[farStart]) {
        farStart++;
        continue;
      }
      let farEnd = farStart;
      while (farEnd + 1 < n && far[farEnd + 1]) farEnd++;
      let anySuspicious = false;
      for (let index = farStart; index <= farEnd; index++) {
        if (suspicious[index]) {
          anySuspicious = true;
          break;
        }
      }
      if (farRunSize[farStart] <= MAX_REPAIRED_FAR_RUN_POINTS && anySuspicious) {
        for (let index = farStart; index <= farEnd; index++) bad[index] = true;
      }
      farStart = farEnd + 1;
    }

    for (let index = 0; index < n; index++) {
      if (!valid[index]) bad[index] = true;
      // A speed spike alone is normal GPS noise when the point still lies on
      // the reference. A long smooth run away from the reference is a real
      // detour and must not be flattened because of one spike in it.
      if (suspicious[index] && far[index] && farRunSize[index] <= MAX_REPAIRED_FAR_RUN_POINTS) {
        bad[index] = true;
      }
    }

    // A point can lie a few centimetres from a parallel branch of a
    // self-overlapping reference while the travelled distance shows that it
    // belongs to another branch. Wide branch rewriting is gated as in 1.7.3.
    const sourceLength = sourcePrefix[n - 1] - sourcePrefix[0];
    const firstPath = this.reference.paths[0];
    const sourceToReferenceRatio = firstPath && firstPath.totalLength > 1.0
      ? sourceLength / firstPath.totalLength
      : 0.0;
    if (distanceAlignment !== null &&
        firstPath && firstPath.closed &&
        firstPath.repeatLapCount <= 2 &&
        sourceToReferenceRatio >= 0.8 && sourceToReferenceRatio <= 1.2) {
      const path = firstPath;
      for (let index = 0; index < n; index++) {
        if (!valid[index]) continue;
        const projection = nearest[index];
        if (!projection) continue;
        const predicted = distanceAlignment.progressAt(sourcePrefix[index]);
        let difference = abs(predicted - projection.s);
        if (path.closed) {
          difference %= path.totalLength;
          if (difference > path.totalLength * 0.5) difference = path.totalLength - difference;
        }
        if (difference > max(threshold, 50.0) && deviation[index] <= threshold) {
          bad[index] = true;
        }
      }
    }
    for (const range of frozenRanges) {
      for (let index = range.start; index <= range.end; index++) bad[index] = true;
    }

    // If one point of a teleport is marked, the entire shifted run must be
    // replaced. A plain missing-coordinate block must not pull a neighbouring
    // far run into the repair: that run can be a real offset/detour.
    let changed = true;
    while (changed) {
      changed = false;
      for (let index = 0; index < n; index++) {
        if (!bad[index]) continue;
        const glitch = suspicious[index] || frozenFlag[index];
        if (!glitch && !valid[index]) continue;
        if (index > 0 && !bad[index - 1]) {
          const neighbor = index - 1;
          if (far[neighbor] && farRunSize[neighbor] <= MAX_REPAIRED_FAR_RUN_POINTS) {
            bad[neighbor] = true;
            changed = true;
          }
        }
        if (index < n - 1 && !bad[index + 1]) {
          const neighbor = index + 1;
          if (far[neighbor] && farRunSize[neighbor] <= MAX_REPAIRED_FAR_RUN_POINTS) {
            bad[neighbor] = true;
            changed = true;
          }
        }
      }
    }

    const blocks = [];
    let index = 0;
    while (index < n) {
      if (!bad[index]) {
        index++;
        continue;
      }
      let end = index;
      while (end + 1 < n && bad[end + 1]) end++;
      blocks.push({ start: index, end });
      index = end + 1;
    }

    const unresolvedFlags = new Array(n).fill(false);

    for (const block of blocks) {
      const left = this.findAnchor(block.start - 1, -1, valid, deviation, threshold) ??
        (block.end === n - 1
          ? this.findEdgeAnchor(block.start - 1, -1, valid, deviation, threshold)
          : null);
      const right = this.findAnchor(block.end + 1, 1, valid, deviation, threshold) ??
        (block.start === 0
          ? this.findEdgeAnchor(block.end + 1, 1, valid, deviation, threshold)
          : null);
      const expected = this.expectedDistance(
        block, input, left, right, medianSpeed, medianSpacing, sourcePrefix
      );
      let clampEdges = false;
      for (let i = block.start; i <= block.end; i++) {
        if (frozenFlag[i]) {
          clampEdges = true;
          break;
        }
      }

      let repaired;
      if (left !== null && right !== null) {
        repaired = this.repairBetween(
          block, left, right, expected, input, lat, lon, ele,
          coordinateChanged, elevationFilled, threshold, sourcePrefix,
          distanceAlignment, progressAlignment
        );
      } else if (left !== null) {
        repaired = this.repairOneSide(
          block, left, 1, input, lat, lon, ele,
          coordinateChanged, elevationFilled, medianSpeed, medianSpacing,
          threshold, clampEdges, sourcePrefix, distanceAlignment,
          progressAlignment, frozenFlag
        );
      } else if (right !== null) {
        repaired = this.repairOneSide(
          block, right, -1, input, lat, lon, ele,
          coordinateChanged, elevationFilled, medianSpeed, medianSpacing,
          threshold, clampEdges, sourcePrefix, distanceAlignment,
          progressAlignment, frozenFlag
        );
      } else {
        repaired = this.repairWithoutAnchors(
          block, input, lat, lon, ele, coordinateChanged, elevationFilled, threshold
        );
      }
      if (!repaired) {
        for (let i = block.start; i <= block.end; i++) unresolvedFlags[i] = true;
      }
    }

    if (this.settings.fillMissingElevation) {
      for (let i = 0; i < n; i++) {
        if (ele[i] !== null || !validCoordinate(lat[i], lon[i])) continue;
        const projection = this.reference.nearest(
          lat[i], lon[i], max(100.0, threshold * 3.0)
        );
        if (projection && projection.ele !== null && projection.ele !== undefined) {
          ele[i] = projection.ele;
          elevationFilled[i] = true;
        }
      }
    }

    // Count and validate results.
    let fixedCoordinates = 0;
    let missingCoordinates = 0;
    let unresolved = 0;
    const filledElevations = elevationFilled.filter(Boolean).length;
    let leadingFixed = 0;
    let trailingFixed = 0;
    for (const block of blocks) {
      let changedInBlock = 0;
      for (let i = block.start; i <= block.end; i++) {
        if (coordinateChanged[i]) changedInBlock++;
      }
      if (block.start === 0) leadingFixed = changedInBlock;
      if (block.end === n - 1) trailingFixed = changedInBlock;
    }
    for (let i = 0; i < n; i++) {
      if (coordinateChanged[i]) {
        fixedCoordinates++;
        if (!valid[i]) missingCoordinates++;
      }
      if (bad[i]) {
        const postValid = validCoordinate(lat[i], lon[i]);
        const postDeviation = postValid
          ? this.reference.nearest(lat[i], lon[i], max(threshold * 2.0, 100.0))?.distance ?? null
          : null;
        if (!postValid || postDeviation === null || postDeviation > threshold * 1.5) {
          unresolvedFlags[i] = true;
        }
      }
      if (unresolvedFlags[i]) unresolved++;
    }

    const finiteDeviations = deviation.filter((value) => Number.isFinite(value));
    const maxDeviationBefore = finiteDeviations.length
      ? finiteDeviations.reduce((a, b) => max(a, b), 0.0)
      : 0.0;
    const maxDeviationCapped = deviation.some((value) => !Number.isFinite(value));
    const distanceAfter = distanceOfArrays(lat, lon);

    if (fixedCoordinates > Math.floor(n / 2) && n > 0) warnings.add(FIX_WARNINGS.LARGE_CHANGES);
    if (unresolved > 0) warnings.add(FIX_WARNINGS.NO_REFERENCE_COVERAGE);
    if (this.reference.paths.length > 1) warnings.add(FIX_WARNINGS.REFERENCE_SEGMENTS);
    if (blocks.length === 0) warnings.add(FIX_WARNINGS.NOTHING_TO_FIX);

    return makeResult(
      lat, lon, ele, coordinateChanged, elevationFilled,
      makeStats({
        totalPoints: n,
        fixedCoordinates,
        missingCoordinates,
        filledElevations,
        problemBlocks: blocks.length,
        unresolvedPoints: unresolved,
        maxDeviationBefore,
        maxDeviationCapped,
        maxJumpBefore,
        distanceBefore,
        distanceAfter,
        speedLimit,
        frozenRuns: frozenRanges.length,
        frozenCoordinates: frozenRanges.reduce((sum, range) => sum + (range.end - range.start + 1), 0),
        leadingFixed,
        trailingFixed
      }),
      warnings
    );
  }

  /**
   * "Follow the reference" mode. The coordinate of every point is taken from
   * the reference route; time, elevation and all sensor values stay as in the
   * source file.
   */
  followReference(
    input, valid, lat, lon, ele, coordinateChanged, elevationFilled, warnings,
    progress, distanceAlignment, nearest, deviation, sourcePrefix,
    threshold, distanceBefore, maxJumpBefore, speedLimit
  ) {
    const n = input.length;
    let changed = 0;
    let missingPlaced = 0;
    let unresolved = 0;

    const singlePath = this.reference.paths.length === 1;
    const totalSource = sourcePrefix[n - 1] - sourcePrefix[0];
    const ratio = singlePath && this.reference.paths[0].totalLength > 1.0
      ? totalSource / this.reference.paths[0].totalLength
      : 0.0;
    const globalFirst = distanceAlignment !== null && singlePath && ratio >= 0.8 && ratio <= 1.2;

    const s = new Array(n).fill(null);
    const pathOf = new Array(n).fill(-1);
    if (globalFirst) {
      for (let index = 0; index < n; index++) {
        s[index] = distanceAlignment.progressAt(sourcePrefix[index]);
        pathOf[index] = 0;
      }
    } else {
      for (let index = 0; index < n; index++) {
        const aligned = progress.s[index];
        if (aligned !== null) {
          s[index] = aligned;
          pathOf[index] = clamp(progress.pathIndex[index], 0, this.reference.paths.length - 1);
        }
      }
      if (s.some((value) => value !== null)) {
        const step = progress.scale * (progress.direction < 0 ? -1.0 : 1.0);
        for (let index = 1; index < n; index++) {
          if (s[index] !== null || s[index - 1] === null) continue;
          s[index] = s[index - 1] + (sourcePrefix[index] - sourcePrefix[index - 1]) * step;
          pathOf[index] = pathOf[index - 1];
        }
        for (let index = n - 2; index >= 0; index--) {
          if (s[index] !== null || s[index + 1] === null) continue;
          s[index] = s[index + 1] - (sourcePrefix[index + 1] - sourcePrefix[index]) * step;
          pathOf[index] = pathOf[index + 1];
        }
      }
    }

    for (let index = 0; index < n; index++) {
      let position = s[index];
      let pathIndex = pathOf[index];
      if (position === null && distanceAlignment !== null) {
        position = distanceAlignment.progressAt(sourcePrefix[index]);
        pathIndex = 0;
      }
      if (position === null) {
        const projection = nearest[index];
        if (!projection) continue;
        position = projection.s;
        pathIndex = projection.pathIndex;
      }
      if (pathIndex < 0) {
        unresolved++;
        continue;
      }
      const path = this.reference.paths[pathIndex];
      if (!path) {
        unresolved++;
        continue;
      }
      const vertex = path.pointAt(position, path.closed);
      if (!validCoordinate(vertex.lat, vertex.lon)) {
        unresolved++;
        continue;
      }
      if (!valid[index]) missingPlaced++;
      lat[index] = vertex.lat;
      lon[index] = vertex.lon;
      coordinateChanged[index] = true;
      changed++;
      if (this.settings.fillMissingElevation && ele[index] === null && vertex.ele != null) {
        ele[index] = vertex.ele;
        elevationFilled[index] = true;
      }
    }

    const filledElevations = elevationFilled.filter(Boolean).length;
    const finiteDeviations = deviation.filter((value) => Number.isFinite(value));
    const maxDeviationBefore = finiteDeviations.length
      ? finiteDeviations.reduce((a, b) => max(a, b), 0.0)
      : 0.0;
    const maxDeviationCapped = deviation.some((value) => !Number.isFinite(value));

    if (changed > Math.floor(n / 2) && n > 0) warnings.add(FIX_WARNINGS.LARGE_CHANGES);
    if (unresolved > 0) warnings.add(FIX_WARNINGS.NO_REFERENCE_COVERAGE);
    if (this.reference.paths.length > 1) warnings.add(FIX_WARNINGS.REFERENCE_SEGMENTS);

    return makeResult(
      lat, lon, ele, coordinateChanged, elevationFilled,
      makeStats({
        totalPoints: n,
        fixedCoordinates: changed,
        missingCoordinates: missingPlaced,
        filledElevations,
        problemBlocks: 0,
        unresolvedPoints: unresolved,
        maxDeviationBefore,
        maxDeviationCapped,
        maxJumpBefore,
        distanceBefore,
        distanceAfter: distanceOfArrays(lat, lon),
        speedLimit,
        reconstructedCoordinates: changed
      }),
      warnings
    );
  }

  /**
   * Aligns the source points to the reference by dynamic programming. The
   * cost combines the geometric distance and the difference between the
   * source distance travelled and the reference distance between points.
   */
  computeProgress(input, valid, sourcePrefix, medianSpeed, threshold) {
    const n = input.length;
    const empty = {
      s: new Array(n).fill(null),
      pathIndex: new Array(n).fill(-1),
      direction: 1,
      scale: 1.0
    };
    if (this.reference.paths.length !== 1) return empty;
    const path = this.reference.paths[0];
    const radius = max(150.0, threshold * 5.0);
    const candidateLists = new Array(n).fill(null);
    const indices = [];
    for (let index = 0; index < n; index++) {
      if (!valid[index]) continue;
      const lat = input[index].lat;
      const lon = input[index].lon;
      if (!finite(lat) || !finite(lon)) continue;
      // The first GPS fix may be far from the route while the source distance
      // still shows that the recording began at the route start.
      const pointRadius = indices.length === 0
        ? max(radius, FIRST_POINT_SEARCH_RADIUS_METERS)
        : radius;
      const candidates = path.candidates(lat, lon, pointRadius);
      if (!candidates || candidates.length === 0) continue;
      candidateLists[index] = candidates;
      indices.push(index);
    }
    if (indices.length < 2) return empty;

    const forward = this.alignDirection(path, indices, candidateLists, input, sourcePrefix, medianSpeed, 1);
    const backward = this.alignDirection(path, indices, candidateLists, input, sourcePrefix, medianSpeed, -1);
    let chosen;
    if (forward === null || backward === null) chosen = forward ?? backward;
    else chosen = forward.cost <= backward.cost ? forward : backward;
    if (!chosen) return empty;

    const resultS = new Array(n).fill(null);
    const resultPath = new Array(n).fill(-1);
    for (let position = 0; position < indices.length; position++) {
      const index = indices[position];
      resultS[index] = chosen.progress[position];
      resultPath[index] = 0;
    }
    const first = indices[0];
    const last = indices[indices.length - 1];
    const sourceSpan = sourcePrefix[last] - sourcePrefix[first];
    let scale = 1.0;
    if (sourceSpan > 1.0) {
      const progressSpan = chosen.direction > 0
        ? chosen.progress[chosen.progress.length - 1] - chosen.progress[0]
        : chosen.progress[0] - chosen.progress[chosen.progress.length - 1];
      const candidate = progressSpan / sourceSpan;
      scale = finite(candidate) && candidate > 0.05 ? candidate : 1.0;
    }
    return {
      s: resultS,
      pathIndex: resultPath,
      direction: chosen.direction,
      scale: clamp(scale, 0.25, 4.0)
    };
  }

  /**
   * Finds one phase and one scale that map the recorder's cumulative distance
   * onto the reference. The scale is exactly reference/totalSource: letting it
   * drift makes a repeated stadium route gain or lose a whole lap.
   */
  computeDistanceAlignment(input, valid, sourcePrefix, path) {
    const indices = [];
    for (let index = 0; index < input.length; index++) {
      if (!valid[index]) continue;
      if (input[index].lat === null || input[index].lon === null) continue;
      indices.push(index);
    }
    if (indices.length < 10) return null;
    const first = indices[0];
    const totalSource = sourcePrefix[sourcePrefix.length - 1] - sourcePrefix[0];
    if (totalSource < 20.0) return null;
    const baseScale = path.totalLength / totalSource;
    if (!finite(baseScale) || baseScale <= 0.0) return null;
    const scales = [baseScale];
    const coarseStep = max(5.0, path.totalLength / DISTANCE_ALIGNMENT_PHASE_STEPS);
    let bestPhase = 0.0;
    let bestScale = baseScale;
    let bestCost = Number.POSITIVE_INFINITY;
    let phase = 0.0;
    while (phase < path.totalLength) {
      for (const scale of scales) {
        const cost = this.distanceAlignmentCost(
          path, indices, input, sourcePrefix, sourcePrefix[first], phase, scale
        );
        if (cost < bestCost) {
          bestCost = cost;
          bestPhase = phase;
          bestScale = scale;
        }
      }
      phase += coarseStep;
    }
    const refineStep = max(1.0, coarseStep / 8.0);
    let refinedPhase = bestPhase - coarseStep;
    while (refinedPhase <= bestPhase + coarseStep) {
      const cost = this.distanceAlignmentCost(
        path, indices, input, sourcePrefix, sourcePrefix[first], refinedPhase, bestScale
      );
      if (cost < bestCost) {
        bestCost = cost;
        bestPhase = refinedPhase;
      }
      refinedPhase += refineStep;
    }
    return new DistanceAlignment(bestPhase, bestScale, sourcePrefix[first]);
  }

  distanceAlignmentCost(path, indices, input, sourcePrefix, firstDistance, phase, scale) {
    let cost = 0.0;
    for (const index of indices) {
      const s = phase + (sourcePrefix[index] - firstDistance) * scale;
      const vertex = path.pointAt(s, path.closed);
      cost += min(
        Geo.distanceMeters(input[index].lat, input[index].lon, vertex.lat, vertex.lon),
        DISTANCE_ALIGNMENT_CAP_METERS
      );
    }
    return cost;
  }

  /**
   * Keeps the real GPS projection on the lap selected by the distance
   * alignment, preventing jumps at the edge of a repaired block.
   */
  snapProjectionToLap(path, predicted, actual) {
    let difference = actual - predicted;
    if (path.repeatLapCount > 1) {
      const lap = path.repeatLapLength;
      if (lap > 1.0) difference -= Math.round(difference / lap) * lap;
    } else if (path.closed && path.totalLength > 1.0) {
      difference %= path.totalLength;
      if (difference > path.totalLength * 0.5) difference -= path.totalLength;
      if (difference < -path.totalLength * 0.5) difference += path.totalLength;
    }
    return predicted + difference;
  }

  /**
   * Selects the reference copy used as a repair anchor among physically close
   * copies of a repeated loop using the distance-aligned progress.
   */
  chooseAnchorProjection(candidates, predicted, path) {
    if (!candidates || candidates.length === 0) return null;
    let nearest = candidates[0];
    for (const candidate of candidates) {
      if (candidate.distance < nearest.distance) nearest = candidate;
    }
    const physicalTolerance = max(10.0, nearest.distance + 5.0);
    const nearby = candidates.filter((candidate) => candidate.distance <= physicalTolerance);
    let best = null;
    let bestDifference = Number.POSITIVE_INFINITY;
    for (const candidate of nearby) {
      let difference = candidate.s - predicted;
      if (path && path.closed && path.totalLength > 1.0) {
        difference %= path.totalLength;
        if (difference > path.totalLength * 0.5) difference -= path.totalLength;
        if (difference < -path.totalLength * 0.5) difference += path.totalLength;
      }
      if (abs(difference) < bestDifference) {
        bestDifference = abs(difference);
        best = candidate;
      }
    }
    return best;
  }

  alignDirection(path, indices, candidateLists, input, sourcePrefix, medianSpeed, direction) {
    const firstCandidates = candidateLists[indices[0]];
    if (!firstCandidates) return null;
    let best = null;
    for (let firstIndex = 0; firstIndex < firstCandidates.length; firstIndex++) {
      const alignment = this.alignWithPhase(
        path, indices, candidateLists, input, sourcePrefix,
        medianSpeed, direction, firstIndex
      );
      if (!alignment) continue;
      if (best === null || alignment.cost < best.cost) best = alignment;
    }
    return best;
  }

  alignWithPhase(path, indices, candidateLists, input, sourcePrefix, medianSpeed, direction, forcedFirst) {
    const firstCandidates = candidateLists[indices[0]];
    if (!firstCandidates || forcedFirst < 0 || forcedFirst >= firstCandidates.length) return null;
    const firstSource = sourcePrefix[indices[0]];
    const phase = direction > 0
      ? firstCandidates[forcedFirst].s - firstSource
      : firstCandidates[forcedFirst].s + firstSource;

    let costs = new Array(firstCandidates.length).fill(Number.POSITIVE_INFINITY);
    let positions = new Array(firstCandidates.length).fill(0.0);
    costs[forcedFirst] = firstCandidates[forcedFirst].distance * CANDIDATE_DISTANCE_WEIGHT;
    positions[forcedFirst] = firstCandidates[forcedFirst].s;
    const backPointers = [new Array(firstCandidates.length).fill(-1)];
    const positionHistory = [positions.slice()];

    for (let position = 1; position < indices.length; position++) {
      const previousIndex = indices[position - 1];
      const currentIndex = indices[position];
      const previousCandidates = candidateLists[previousIndex];
      const currentCandidates = candidateLists[currentIndex];
      if (!previousCandidates || !currentCandidates) return null;
      const expectedS = direction > 0
        ? sourcePrefix[currentIndex] + phase
        : phase - sourcePrefix[currentIndex];

      const nextCosts = new Array(currentCandidates.length).fill(Number.POSITIVE_INFINITY);
      const nextPositions = new Array(currentCandidates.length).fill(0.0);
      const back = new Array(currentCandidates.length).fill(-1);
      let expected = sourcePrefix[currentIndex] - sourcePrefix[previousIndex];
      if (expected <= 0.0) {
        const seconds = secondsBetween(input[previousIndex].timeMs, input[currentIndex].timeMs);
        if (seconds !== null && seconds > 0.0) expected = seconds * medianSpeed;
      }
      if (expected < 0.0) expected = 0.0;

      for (let candidateIndex = 0; candidateIndex < currentCandidates.length; candidateIndex++) {
        const candidate = currentCandidates[candidateIndex];
        const phaseCost = this.branchPhaseCost(candidate.s, expectedS, path);
        let bestCost = Number.POSITIVE_INFINITY;
        let bestPosition = 0.0;
        let bestPrevious = -1;
        for (let previousCandidateIndex = 0;
             previousCandidateIndex < previousCandidates.length;
             previousCandidateIndex++) {
          const previousPosition = positions[previousCandidateIndex];
          let delta;
          if (direction > 0) {
            const base = candidate.s - previousPosition;
            delta = path.closed
              ? base + Math.round((expected - base) / path.totalLength) * path.totalLength
              : base;
          } else {
            const base = previousPosition - candidate.s;
            delta = path.closed
              ? base + Math.round((expected - base) / path.totalLength) * path.totalLength
              : base;
          }
          if (delta < -1.0) continue;
          const error = abs(delta - expected);
          const total = costs[previousCandidateIndex] +
            error * TRANSITION_DISTANCE_WEIGHT +
            candidate.distance * CANDIDATE_DISTANCE_WEIGHT +
            phaseCost;
          if (total < bestCost) {
            bestCost = total;
            bestPrevious = previousCandidateIndex;
            bestPosition = direction > 0
              ? previousPosition + delta
              : previousPosition - delta;
          }
        }
        if (bestPrevious >= 0) {
          nextCosts[candidateIndex] = bestCost;
          nextPositions[candidateIndex] = bestPosition;
          back[candidateIndex] = bestPrevious;
        }
      }
      costs = nextCosts;
      positions = nextPositions;
      backPointers.push(back);
      positionHistory.push(positions.slice());
    }

    let bestCandidate = -1;
    let bestCost = Number.POSITIVE_INFINITY;
    for (let candidateIndex = 0; candidateIndex < costs.length; candidateIndex++) {
      if (costs[candidateIndex] < bestCost) {
        bestCost = costs[candidateIndex];
        bestCandidate = candidateIndex;
      }
    }
    if (bestCandidate < 0) return null;

    const progress = new Array(indices.length).fill(0.0);
    let candidateIndex = bestCandidate;
    for (let position = indices.length - 1; position >= 0; position--) {
      progress[position] = positionHistory[position][candidateIndex];
      if (position > 0) {
        candidateIndex = backPointers[position][candidateIndex];
        if (candidateIndex < 0) return null;
      }
    }
    return { progress, direction, cost: bestCost };
  }

  /**
   * Weak absolute check: the travelled source distance predicts where on the
   * reference the point should be.
   */
  branchPhaseCost(s, expectedS, path) {
    let difference = abs(s - expectedS);
    if (path.closed) {
      difference %= path.totalLength;
      if (difference > path.totalLength * 0.5) difference = path.totalLength - difference;
    }
    return difference > PHASE_TOLERANCE_METERS
      ? (difference - PHASE_TOLERANCE_METERS) * PHASE_WEIGHT
      : 0.0;
  }

  /**
   * Find runs where the GPS coordinate stopped updating although the rider
   * kept moving.
   */
  detectFrozenRuns(input, valid, nearest) {
    const result = [];
    let index = 0;
    while (index < input.length) {
      if (!valid[index]) {
        index++;
        continue;
      }
      let end = index;
      while (end + 1 < input.length &&
          valid[end + 1] &&
          Geo.distanceMeters(
            input[end].lat, input[end].lon,
            input[end + 1].lat, input[end + 1].lon
          ) <= FROZEN_MAX_MOVE_METERS) {
        end++;
      }
      if (end - index >= 2 && this.frozenRunShowsMovement(input, index, end, valid, nearest)) {
        result.push({ start: index, end });
      }
      index = end + 1;
    }
    return result;
  }

  frozenRunShowsMovement(input, start, end, valid, nearest) {
    const duration = secondsBetween(input[start].timeMs, input[end].timeMs);
    const startDistance = pointDistance(input[start]);
    const endDistance = pointDistance(input[end]);
    const sensorDistance = startDistance !== null && endDistance !== null
      ? endDistance - startDistance
      : null;
    const speeds = [];
    for (let index = start; index <= end; index++) {
      const speed = pointSpeed(input[index]);
      if (speed !== null) speeds.push(speed);
    }
    const averageSpeed = speeds.length
      ? speeds.reduce((sum, value) => sum + value, 0.0) / speeds.length
      : null;
    const referenceGap = this.routeGap(start, end, valid, nearest);

    if (sensorDistance !== null && sensorDistance >= FROZEN_MIN_SENSOR_DISTANCE_METERS) return true;
    if (sensorDistance !== null && sensorDistance >= 5.0 &&
        averageSpeed !== null && averageSpeed >= 1.5 &&
        referenceGap !== null && referenceGap >= 20.0) {
      return true;
    }
    if (averageSpeed !== null && averageSpeed >= 2.0 &&
        duration !== null && duration >= 5.0 &&
        referenceGap !== null && referenceGap >= 20.0) {
      return true;
    }
    // Last resort for files without speed or distance extensions.
    return sensorDistance === null && averageSpeed === null &&
      duration !== null && duration >= 15.0 &&
      referenceGap !== null && referenceGap >= 50.0;
  }

  routeGap(start, end, valid, nearest) {
    const leftIndex = start > 0 && valid[start - 1] ? start - 1 : start;
    const rightIndex = end + 1 < valid.length && valid[end + 1] ? end + 1 : end;
    const left = nearest[leftIndex];
    const right = nearest[rightIndex];
    if (!left || !right) return null;
    if (left.pathIndex !== right.pathIndex) return null;
    const path = this.reference.paths[left.pathIndex];
    if (!path) return null;
    if (!path.closed) return abs(right.s - left.s);
    const forward = path.forwardLength(left.s, right.s);
    const backward = path.backwardLength(left.s, right.s);
    if (forward === null || forward === undefined ||
        backward === null || backward === undefined) return null;
    return min(forward, backward);
  }

  findAnchor(start, step, valid, deviation, threshold) {
    let index = start;
    let checked = 0;
    while (index >= 0 && index < valid.length && checked < 40) {
      if (valid[index] && deviation[index] <= threshold * 1.5) return index;
      index += step;
      checked++;
    }
    return null;
  }

  /**
   * Fallback for a block that touches the very start or end of the track.
   * The last real fix before a lost tail can itself be far from the reference.
   */
  findEdgeAnchor(start, step, valid, deviation, threshold) {
    let index = start;
    let checked = 0;
    const limit = max(300.0, threshold * 10.0);
    while (index >= 0 && index < valid.length && checked < 200) {
      if (valid[index] && Number.isFinite(deviation[index]) && deviation[index] <= limit) {
        return index;
      }
      index += step;
      checked++;
    }
    return null;
  }

  expectedDistance(block, input, left, right, medianSpeed, medianSpacing, sourcePrefix) {
    if (left !== null && right !== null) {
      if (this.blockSensorDistanceUsable(input, block, left, right)) {
        return max(pointDistance(input[right]) - pointDistance(input[left]), 10.0);
      }
      const source = sourcePrefix[right] - sourcePrefix[left];
      if (source >= 10.0) return source;
      const seconds = secondsBetween(input[left].timeMs, input[right].timeMs);
      if (seconds !== null && seconds > 0.0) return max(seconds * medianSpeed, 10.0);
    }
    const anchorCount = (left !== null ? 1 : 0) + (right !== null ? 1 : 0);
    const pointCount = (block.end - block.start + 1) + anchorCount;
    return max(pointCount * medianSpacing, 10.0);
  }

  blockSensorDistanceUsable(input, block, left, right) {
    const startDistance = pointDistance(input[left]);
    const endDistance = pointDistance(input[right]);
    if (startDistance === null || endDistance === null) return false;
    if (endDistance <= startDistance + 1.0) return false;
    let previous = startDistance;
    for (let index = block.start; index <= block.end; index++) {
      const distance = pointDistance(input[index]);
      if (distance === null) return false;
      if (distance < startDistance || distance > endDistance || distance < previous) return false;
      previous = distance;
    }
    return true;
  }

  oneSideSensorDistanceUsable(input, block, anchor, direction) {
    const anchorDistance = pointDistance(input[anchor]);
    if (anchorDistance === null) return false;
    let previous = anchorDistance;
    if (direction > 0) {
      for (let index = block.start; index <= block.end; index++) {
        const distance = pointDistance(input[index]);
        if (distance === null) return false;
        if (distance < previous) return false;
        previous = distance;
      }
    } else {
      for (let index = block.end; index >= block.start; index--) {
        const distance = pointDistance(input[index]);
        if (distance === null) return false;
        if (distance > previous) return false;
        previous = distance;
      }
    }
    return true;
  }

  repairBetween(
    block, left, right, expected, input, lat, lon, ele,
    coordinateChanged, elevationFilled, threshold, sourcePrefix,
    distance, progress
  ) {
    const distanceLeftS = distance ? distance.progressAt(sourcePrefix[left]) : null;
    const distanceRightS = distance ? distance.progressAt(sourcePrefix[right]) : null;
    if (distanceLeftS !== null && distanceRightS !== null) {
      const path = this.reference.paths[0];
      const leftProjection = this.reference.nearest(
        lat[left], lon[left], max(100.0, threshold * 2.0)
      );
      const rightProjection = this.reference.nearest(
        lat[right], lon[right], max(100.0, threshold * 2.0)
      );
      const snappedLeftS = leftProjection
        ? this.snapProjectionToLap(path, distanceLeftS, leftProjection.s)
        : distanceLeftS;
      const snappedRightS = rightProjection
        ? this.snapProjectionToLap(path, distanceRightS, rightProjection.s)
        : distanceRightS;

      // Prefer the real GPS anchors when they describe a plausible movement.
      let actualSpan = leftProjection && rightProjection
        ? rightProjection.s - leftProjection.s
        : null;
      if (actualSpan !== null && path.closed) {
        actualSpan %= path.totalLength;
        if (actualSpan > path.totalLength * 0.5) actualSpan -= path.totalLength;
        else if (actualSpan < -path.totalLength * 0.5) actualSpan += path.totalLength;
      }
      const tolerance = max(150.0, expected * 0.75);
      const useActual = actualSpan !== null && leftProjection !== null &&
        abs(abs(actualSpan) - expected) <= tolerance;
      const leftS = useActual ? leftProjection.s : snappedLeftS;
      const span = useActual ? actualSpan : snappedRightS - snappedLeftS;
      const plausible = abs(abs(span) - expected) <= tolerance;
      if (plausible) {
        const startTime = input[left].timeMs;
        const endTime = input[right].timeMs;
        const useDistance = this.blockSensorDistanceUsable(input, block, left, right);
        const startDistance = pointDistance(input[left]);
        const endDistance = pointDistance(input[right]);
        for (let index = block.start; index <= block.end; index++) {
          const fraction = this.fractionBetween(
            index, left, right, input, startTime, endTime,
            useDistance, startDistance, endDistance, sourcePrefix
          );
          const s = leftS + span * fraction;
          const vertex = path.pointAt(s, path.closed);
          applyVertex(index, vertex, lat, lon, ele, coordinateChanged, elevationFilled);
        }
        return true;
      }
    }

    const progressPathIndex = progress.pathIndex[left];
    const leftS = progress.s[left];
    const rightS = progress.s[right];
    if (progressPathIndex >= 0 &&
        progressPathIndex === progress.pathIndex[right] &&
        leftS !== null && rightS !== null) {
      const span = rightS - leftS;
      const ordered = progress.direction > 0 ? span >= -1.0 : span <= 1.0;
      const plausible = abs(abs(span) - expected) <= max(150.0, expected * 0.75);
      if (ordered && plausible) {
        const path = this.reference.paths[progressPathIndex];
        const startTime = input[left].timeMs;
        const endTime = input[right].timeMs;
        const useDistance = this.blockSensorDistanceUsable(input, block, left, right);
        const startDistance = pointDistance(input[left]);
        const endDistance = pointDistance(input[right]);
        for (let index = block.start; index <= block.end; index++) {
          const fraction = this.fractionBetween(
            index, left, right, input, startTime, endTime,
            useDistance, startDistance, endDistance, sourcePrefix
          );
          const s = leftS + span * fraction;
          const vertex = path.pointAt(s, path.closed);
          applyVertex(index, vertex, lat, lon, ele, coordinateChanged, elevationFilled);
        }
        return true;
      }
    }

    const leftCandidates = this.reference.nearestCandidates(
      lat[left], lon[left], max(100.0, threshold * 2.0), 8
    );
    const rightCandidates = this.reference.nearestCandidates(
      lat[right], lon[right], max(100.0, threshold * 2.0), 8
    );
    if (leftCandidates.length === 0 || rightCandidates.length === 0) return false;

    let best = null;
    let bestScore = Number.POSITIVE_INFINITY;
    for (const leftProjection of leftCandidates) {
      for (const rightProjection of rightCandidates) {
        if (leftProjection.pathIndex !== rightProjection.pathIndex) continue;
        const path = this.reference.paths[leftProjection.pathIndex];
        if (!path) continue;
        for (const direction of [1, -1]) {
          const baseLength = direction > 0
            ? path.forwardLength(leftProjection.s, rightProjection.s)
            : path.backwardLength(leftProjection.s, rightProjection.s);
          if (baseLength === null || baseLength === undefined) continue;

          const lengths = [baseLength];
          // A closed reference lap can be traversed more than once.
          if (path.closed) {
            if (expected > path.totalLength * 0.45) lengths.push(baseLength + path.totalLength);
            if (expected > path.totalLength * 1.45) lengths.push(baseLength + path.totalLength * 2.0);
          }

          for (const length of lengths) {
            const score = abs(length - expected) / max(50.0, expected) +
              0.01 * (leftProjection.distance + rightProjection.distance);
            if (score < bestScore) {
              bestScore = score;
              best = {
                pathIndex: leftProjection.pathIndex,
                direction,
                length,
                start: leftProjection,
                end: rightProjection
              };
            }
          }
        }
      }
    }

    if (!best) return false;
    if (best.length > expected * 2.5 + 300.0) return false;

    const path = this.reference.paths[best.pathIndex];
    const startTime = input[left].timeMs;
    const endTime = input[right].timeMs;
    const useDistance = this.blockSensorDistanceUsable(input, block, left, right);
    const startDistance = pointDistance(input[left]);
    const endDistance = pointDistance(input[right]);
    for (let index = block.start; index <= block.end; index++) {
      const fraction = this.fractionBetween(
        index, left, right, input, startTime, endTime,
        useDistance, startDistance, endDistance, sourcePrefix
      );
      const s = best.start.s + best.direction * best.length * fraction;
      const vertex = path.pointAt(s, path.closed);
      applyVertex(index, vertex, lat, lon, ele, coordinateChanged, elevationFilled);
    }
    return true;
  }

  repairOneSide(
    block, anchor, direction, input, lat, lon, ele,
    coordinateChanged, elevationFilled, medianSpeed, medianSpacing,
    threshold, clampEdges, sourcePrefix, distanceAlignment,
    progress, frozen
  ) {
    const edgeBlock = block.start === 0 || block.end === input.length - 1;
    const searchRadius = edgeBlock
      ? max(300.0, threshold * 10.0)
      : max(100.0, threshold * 2.0);
    const candidates = this.reference.nearestCandidates(
      lat[anchor], lon[anchor], searchRadius, 50
    );
    if (candidates.length === 0) return false;

    const distanceAnchorS = distanceAlignment
      ? distanceAlignment.progressAt(sourcePrefix[anchor])
      : null;
    const firstPath = this.reference.paths[0];
    // At the very start or end of an open reference the global distance
    // alignment can be shifted and would invent positions before the route.
    const useDistanceAlignment = distanceAnchorS !== null &&
      !(edgeBlock && firstPath && !firstPath.closed);
    const projection = useDistanceAlignment
      ? this.chooseAnchorProjection(candidates, distanceAnchorS, firstPath)
      : candidates.reduce((best, candidate) =>
          best === null || candidate.distance < best.distance ? candidate : best, null);
    if (!projection) return false;

    const progressPathIndex = progress.pathIndex[anchor];
    const progressAnchorS = progress.s[anchor];
    const useProgress = !useDistanceAlignment &&
      progressPathIndex >= 0 && progressAnchorS !== null;
    const path = useDistanceAlignment
      ? firstPath
      : useProgress
        ? this.reference.paths[progressPathIndex]
        : this.reference.paths[projection.pathIndex];
    if (!path) return false;
    const anchorS = useProgress ? progressAnchorS : projection.s;
    const moveSign = useProgress ? progress.direction * direction : direction;
    const scale = useDistanceAlignment
      ? distanceAlignment.scale
      : useProgress
        ? this.localProgressScale(progress, input, sourcePrefix, anchor, direction, path)
        : 1.0;

    const anchorTime = input[anchor].timeMs;
    const anchorDistance = pointDistance(input[anchor]);
    const useDistance = this.oneSideSensorDistanceUsable(input, block, anchor, direction);

    // A leading or trailing GPS gap should begin/end at the activity's start
    // line, not wherever the accumulated source distance happens to drift.
    const edgeCandidateS = useDistanceAlignment && path.closed
      ? (block.start === 0 && direction < 0
          ? 0.0
          : block.end === input.length - 1 && direction > 0
            ? path.totalLength
            : null)
      : null;
    // Never pull an edge block backwards just to hit the seam.
    const edgeTargetS = edgeCandidateS !== null &&
      (edgeCandidateS - anchorS) * direction > 0.0
      ? edgeCandidateS
      : null;
    const edgeEndIndex = edgeTargetS === null
      ? -1
      : block.start === 0 && direction < 0
        ? block.start
        : block.end;
    const edgeSourceSpan = edgeEndIndex >= 0
      ? abs(sourcePrefix[edgeEndIndex] - sourcePrefix[anchor])
      : 0.0;
    const edgeTolerance = 1.0;
    let repairedAny = false;

    for (let index = block.start; index <= block.end; index++) {
      let distance;
      if (useDistance && anchorDistance !== null) {
        distance = abs(anchorDistance - pointDistance(input[index]));
      } else {
        const source = abs(sourcePrefix[index] - sourcePrefix[anchor]);
        if (source > 0.0) {
          distance = source;
        } else if (direction > 0) {
          const seconds = secondsBetween(anchorTime, input[index].timeMs);
          distance = seconds !== null && seconds > 0
            ? seconds * medianSpeed
            : (index - anchor) * medianSpacing;
        } else {
          const seconds = secondsBetween(input[index].timeMs, anchorTime);
          distance = seconds !== null && seconds > 0
            ? seconds * medianSpeed
            : (anchor - index) * medianSpacing;
        }
      }

      let targetS;
      if (edgeTargetS !== null && edgeSourceSpan > 1.0) {
        const fraction = clamp(
          abs(sourcePrefix[index] - sourcePrefix[anchor]) / edgeSourceSpan, 0.0, 1.0
        );
        targetS = anchorS + (edgeTargetS - anchorS) * fraction;
      } else if (useDistanceAlignment) {
        const ownS = distanceAlignment.progressAt(sourcePrefix[index]);
        targetS = abs(ownS - anchorS) < 0.5 && distance > 1.0
          ? anchorS + direction * distance * scale
          : ownS;
      } else {
        targetS = anchorS + moveSign * distance * scale;
      }

      if (!useDistanceAlignment && useProgress) {
        const ownS = progress.s[index];
        const ownPathIndex = progress.pathIndex[index];
        const followsDirection = ownS !== null &&
          (moveSign > 0 ? ownS >= anchorS - 1.0 : ownS <= anchorS + 1.0);
        // A frozen run repeats one coordinate while the recorder still reports
        // movement, so spread it with travelled distance instead.
        const ownProjectionUsable = ownS !== null &&
          ownPathIndex === progressPathIndex &&
          !frozen[index] &&
          followsDirection &&
          abs(ownS - anchorS) <= distance * 4.5 + 300.0;
        if (ownProjectionUsable) targetS = ownS;
      }

      // Do not invent coordinates far beyond an open reference.
      if (!path.closed) {
        if (targetS < 0.0) {
          if (block.start === 0 && (clampEdges || targetS >= -edgeTolerance)) targetS = 0.0;
          else continue;
        } else if (targetS > path.totalLength) {
          if (block.end === input.length - 1 &&
              (clampEdges || targetS <= path.totalLength + edgeTolerance)) {
            targetS = path.totalLength;
          } else {
            continue;
          }
        }
      }

      const vertex = path.pointAt(targetS, path.closed);
      applyVertex(index, vertex, lat, lon, ele, coordinateChanged, elevationFilled);
      repairedAny = true;
    }
    return repairedAny;
  }

  /**
   * Estimates how many metres of reference progress correspond to one metre
   * of source distance close to the anchor.
   */
  localProgressScale(progress, input, sourcePrefix, anchor, direction, path) {
    const anchorS = progress.s[anchor];
    if (anchorS === null) return progress.scale;
    const anchorPathIndex = progress.pathIndex[anchor];
    const step = -direction;
    let index = anchor + step;
    let checked = 0;
    while (index >= 0 && index < input.length && checked < 20) {
      const s = progress.s[index];
      if (s !== null && progress.pathIndex[index] === anchorPathIndex) {
        const source = abs(sourcePrefix[anchor] - sourcePrefix[index]);
        let span = abs(anchorS - s);
        if (path.closed) {
          span %= path.totalLength;
          if (span > path.totalLength * 0.5) span = path.totalLength - span;
        }
        if (source >= 5.0 && span >= 1.0) {
          return clamp(span / source, 0.25, 4.0);
        }
      }
      index += step;
      checked++;
    }
    return progress.scale;
  }

  repairWithoutAnchors(
    block, input, lat, lon, ele, coordinateChanged, elevationFilled, threshold
  ) {
    let repairedAny = false;
    for (let index = block.start; index <= block.end; index++) {
      const projection = validCoordinate(lat[index], lon[index])
        ? this.reference.nearest(lat[index], lon[index], max(500.0, threshold * 5.0))
        : null;
      if (projection) {
        applyVertex(
          index,
          { lat: projection.lat, lon: projection.lon, ele: projection.ele },
          lat, lon, ele, coordinateChanged, elevationFilled
        );
        repairedAny = true;
      }
    }

    // Fill points with no coordinates between two repaired neighbours in a
    // straight line. This is only a last-resort fallback.
    let index = block.start;
    while (index <= block.end) {
      if (validCoordinate(lat[index], lon[index])) {
        index++;
        continue;
      }
      let end = index;
      while (end <= block.end && !validCoordinate(lat[end], lon[end])) end++;
      const before = index - 1 >= 0 && validCoordinate(lat[index - 1], lon[index - 1])
        ? index - 1
        : null;
      const after = end <= block.end && validCoordinate(lat[end], lon[end]) ? end : null;
      if (before !== null && after !== null) {
        for (let k = index; k < end; k++) {
          const fraction = (k - before) / (after - before);
          lat[k] = lat[before] + (lat[after] - lat[before]) * fraction;
          lon[k] = lon[before] + (lon[after] - lon[before]) * fraction;
          coordinateChanged[k] = true;
          repairedAny = true;
        }
      }
      index = end;
    }
    return repairedAny;
  }

  fractionBetween(
    index, left, right, input, startTime, endTime,
    useDistance, startDistance, endDistance, sourcePrefix
  ) {
    const pointDistanceValue = pointDistance(input[index]);
    if (useDistance && startDistance !== null && endDistance !== null &&
        pointDistanceValue !== null) {
      const total = endDistance - startDistance;
      const current = pointDistanceValue - startDistance;
      if (total > 0.0) return clamp(current / total, 0.0, 1.0);
    }
    const sourceTotal = sourcePrefix[right] - sourcePrefix[left];
    if (sourceTotal >= 10.0) {
      return clamp((sourcePrefix[index] - sourcePrefix[left]) / sourceTotal, 0.0, 1.0);
    }
    if (startTime !== null && endTime !== null && input[index].timeMs !== null) {
      const total = (endTime - startTime) / 1000.0;
      const current = (input[index].timeMs - startTime) / 1000.0;
      if (total > 0.0) return clamp(current / total, 0.0, 1.0);
    }
    return clamp((index - left) / (right - left), 0.0, 1.0);
  }
}

/**
 * Port of TrackFixer.fix(). The no-GPS case is handled here as in FixEngine
 * so callers get the reconstructed route without needing a second export.
 */
export function fixTrack(input, reference, settings = {}) {
  const resolved = {
    maxDeviationMeters: finite(settings.maxDeviationMeters)
      ? settings.maxDeviationMeters
      : DEFAULT_SETTINGS.maxDeviationMeters,
    fillMissingElevation: settings.fillMissingElevation === undefined
      ? DEFAULT_SETTINGS.fillMissingElevation
      : Boolean(settings.fillMissingElevation),
    followReference: settings.followReference === undefined
      ? DEFAULT_SETTINGS.followReference
      : Boolean(settings.followReference),
    maxSpeedMetersPerSecond: finite(settings.maxSpeedMetersPerSecond)
      ? settings.maxSpeedMetersPerSecond
      : DEFAULT_SETTINGS.maxSpeedMetersPerSecond
  };
  return new TrackFixer(reference, resolved).fix(input || []);
}
