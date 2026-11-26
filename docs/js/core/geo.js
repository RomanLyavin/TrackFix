// Port of app/src/main/java/com/trackfix/app/core/Geo.kt.
// Time in the web core is always epoch milliseconds (number) or null.

const EARTH_RADIUS_M = 6371008.8;

function finite(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

export const Geo = {
  isValidCoordinate(lat, lon) {
    if (!finite(lat) || !finite(lon)) return false;
    if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return false;
    // A lot of broken GPX files use 0/0 as a placeholder.
    return !(Math.abs(lat) < 1e-7 && Math.abs(lon) < 1e-7);
  },

  distanceMeters(lat1, lon1, lat2, lon2) {
    const p1 = (lat1 * Math.PI) / 180;
    const p2 = (lat2 * Math.PI) / 180;
    const dLat = ((lat2 - lat1) * Math.PI) / 180;
    const dLon = ((lon2 - lon1) * Math.PI) / 180;
    const sLat = Math.sin(dLat / 2);
    const sLon = Math.sin(dLon / 2);
    const a = sLat * sLat + Math.cos(p1) * Math.cos(p2) * sLon * sLon;
    return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  },

  // Local equirectangular projection onto a straight segment.
  projectOnSegment(lat, lon, aLat, aLon, bLat, bLon) {
    const midLat = (aLat + bLat) / 2;
    const mPerDegLat =
      111132.92 -
      559.82 * Math.cos((2 * midLat * Math.PI) / 180) +
      1.175 * Math.cos((4 * midLat * Math.PI) / 180) -
      0.0023 * Math.cos((6 * midLat * Math.PI) / 180);
    const mPerDegLon =
      111412.84 * Math.cos((midLat * Math.PI) / 180) -
      93.5 * Math.cos((3 * midLat * Math.PI) / 180) +
      0.118 * Math.cos((5 * midLat * Math.PI) / 180);

    const x = (lon - aLon) * mPerDegLon;
    const y = (lat - aLat) * mPerDegLat;
    const x2 = (bLon - aLon) * mPerDegLon;
    const y2 = (bLat - aLat) * mPerDegLat;
    const lengthSq = x2 * x2 + y2 * y2;
    const t =
      lengthSq <= 1e-12
        ? 0
        : Math.min(1, Math.max(0, (x * x2 + y * y2) / lengthSq));
    const closestLat = aLat + (bLat - aLat) * t;
    const closestLon = aLon + (bLon - aLon) * t;
    const px = x - t * x2;
    const py = y - t * y2;
    return { t, distance: Math.hypot(px, py), lat: closestLat, lon: closestLon };
  },

  median(values) {
    if (!values || values.length === 0) return null;
    const sorted = Array.from(values).sort((a, b) => a - b);
    const middle = Math.floor(sorted.length / 2);
    return sorted.length % 2 === 0
      ? (sorted[middle - 1] + sorted[middle]) / 2
      : sorted[middle];
  },

  // Russian units are written as escapes to avoid accidental homoglyphs.
  formatDistance(meters) {
    return meters < 1000
      ? `${meters.toFixed(0)} \u043C`
      : `${(meters / 1000).toFixed(2)} \u043A\u043C`;
  },

  formatDuration(seconds) {
    const total = Math.max(0, Math.floor(finite(seconds) ? seconds : 0));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    const pad = (v) => String(v).padStart(2, '0');
    return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
  },

  secondsBetween(aMs, bMs) {
    if (!finite(aMs) || !finite(bMs)) return null;
    return (bMs - aMs) / 1000;
  }
};

function firstFinite(...values) {
  for (const value of values) {
    if (finite(value)) return value;
  }
  return null;
}

function timeFrom(value) {
  if (finite(value)) return value;
  if (value instanceof Date) {
    const ms = value.getTime();
    return Number.isFinite(ms) ? ms : null;
  }
  if (typeof value === 'string') {
    // ISO strings only; import { parseTime } is intentionally not part of the API.
    const ms = Date.parse(value.trim());
    return Number.isFinite(ms) ? ms : null;
  }
  return null;
}

/** Builds the common TrackPoint shape, filling every missing field with null. */
export function trackPoint(o) {
  const source = o && typeof o === 'object' ? o : {};
  return {
    lat: firstFinite(source.lat),
    lon: firstFinite(source.lon),
    ele: firstFinite(source.ele),
    timeMs: timeFrom(source.timeMs ?? source.time ?? source.timeMsEpoch),
    speed: firstFinite(source.speed, source.speedMetersPerSecond),
    distance: firstFinite(source.distance, source.cumulativeDistanceMeters),
    cadence: firstFinite(source.cadence, source.cad) !== null
      ? Math.trunc(firstFinite(source.cadence, source.cad))
      : null,
    hr: firstFinite(source.hr, source.heartRate, source.heartrate),
    power: firstFinite(source.power, source.watts)
  };
}
