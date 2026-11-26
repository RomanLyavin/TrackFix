import { Geo } from './geo.js';

/** The order is also the order of the checkboxes and of the overlay rows. */
export const METRICS = [
  'MOVING_TIME',
  'DISTANCE',
  'AVERAGE_PACE',
  'AVERAGE_SPEED',
  'AVERAGE_HEART_RATE',
  'AVERAGE_POWER',
  'TOTAL_ASCENT'
];

// Filled from strings.xml by the porting script; never hand-typed.
const SHORT_LABELS = {"MOVING_TIME":"\u0412\u0420\u0415\u041C\u042F","DISTANCE":"\u0414\u0418\u0421\u0422\u0410\u041D\u0426\u0418\u042F","AVERAGE_PACE":"\u0421\u0420\u0415\u0414\u041D\u0418\u0419 \u0422\u0415\u041C\u041F","AVERAGE_SPEED":"\u0421\u0420\u0415\u0414\u041D\u042F\u042F \u0421\u041A\u041E\u0420\u041E\u0421\u0422\u042C","AVERAGE_HEART_RATE":"\u0421\u0420\u0415\u0414\u041D\u0418\u0419 \u041F\u0423\u041B\u042C\u0421","AVERAGE_POWER":"\u0421\u0420\u0415\u0414\u041D\u042F\u042F \u041C\u041E\u0429\u041D\u041E\u0421\u0422\u042C","TOTAL_ASCENT":"\u041E\u0411\u0429\u0418\u0419 \u041F\u041E\u0414\u042A\u0401\u041C"};
const UNIT_KM = '\u043A\u043C';
const UNIT_M = '\u043C';
const UNIT_SPEED = '\u043A\u043C/\u0447';
const UNIT_HR = '\u0443\u0434/\u043C\u0438\u043D';
const UNIT_POWER = '\u0412\u0442';

const MAX_MOVING_GAP_SECONDS = 30;
const MIN_MOVING_SPEED_MPS = 0.5;
const MIN_DISTANCE_FOR_PACE_METERS = 100;
const ASCENT_NOISE_THRESHOLD_METERS = 2;

const HEART_RATE_NAMES = ['heart_rate', 'heartrate', 'hr'];
const POWER_NAMES = ['power', 'watts', 'watt'];

function timeMs(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function parseDouble(value) {
  if (value == null) return null;
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value !== 'string' || !value.trim()) return null;
  const parsed = Number.parseFloat(value.trim().replace(',', '.'));
  return Number.isFinite(parsed) ? parsed : null;
}

function decodeXml(text) {
  return String(text)
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec) => String.fromCodePoint(parseInt(dec, 10)))
    .replace(/&amp;/g, '&');
}

function innerXmlValue(point, names) {
  const xml = point && typeof point.innerXml === 'string' ? point.innerXml : '';
  if (!xml) return null;
  for (const name of names) {
    const pattern = new RegExp(
      '<(?:[A-Za-z_][\\w.-]*:)?' + name +
      '\\b[^>]*>([\\s\\S]*?)<\\/(?:[A-Za-z_][\\w.-]*:)?[A-Za-z_][\\w.-]*\\s*>',
      'i'
    );
    const match = pattern.exec(xml);
    if (match) {
      const value = parseDouble(decodeXml(match[1]));
      if (value != null) return value;
    }
  }
  return null;
}

function sensorValue(point, names) {
  for (const name of names) {
    const value = parseDouble(point[name]);
    if (value != null) return value;
  }
  const camel = names.find((name) => name === 'heartrate' || name === 'heart_rate');
  if (camel === 'heartrate' || camel === 'heart_rate') {
    const value = parseDouble(point.heartRate);
    if (value != null) return value;
  }
  return innerXmlValue(point, names);
}

function totalAscent(points) {
  let hasElevation = false;
  let total = 0;
  let reference = null;
  let previousSegment = null;

  for (const point of points) {
    const segment = point.segmentIndex == null ? 0 : point.segmentIndex;
    if (segment !== previousSegment) {
      reference = null;
      previousSegment = segment;
    }
    const elevation = parseDouble(point.ele);
    if (elevation == null) continue;
    hasElevation = true;
    if (reference == null) {
      reference = elevation;
      continue;
    }
    const difference = elevation - reference;
    if (difference >= ASCENT_NOISE_THRESHOLD_METERS) {
      total += difference;
      reference = elevation;
    } else if (difference <= -ASCENT_NOISE_THRESHOLD_METERS) {
      reference = elevation;
    }
  }
  return hasElevation ? total : null;
}

/** Calculates a summary of an already parsed GPX/FIT track. */
export function calculateStats(file) {
  const points = file && Array.isArray(file.points) ? file.points : [];
  let distance = 0;
  let movingMillis = 0;
  let previous = null;

  for (const point of points) {
    const prev = previous;
    if (prev && (prev.segmentIndex == null ? 0 : prev.segmentIndex) ===
        (point.segmentIndex == null ? 0 : point.segmentIndex)) {
      const hasCoordinates = Geo.isValidCoordinate(prev.lat, prev.lon) &&
        Geo.isValidCoordinate(point.lat, point.lon);
      const stepDistance = hasCoordinates
        ? Geo.distanceMeters(prev.lat, prev.lon, point.lat, point.lon)
        : null;
      if (stepDistance != null) distance += stepDistance;

      const previousTime = timeMs(prev.timeMs);
      const currentTime = timeMs(point.timeMs);
      if (previousTime != null && currentTime != null) {
        const deltaMillis = currentTime - previousTime;
        if (deltaMillis > 0 && deltaMillis <= MAX_MOVING_GAP_SECONDS * 1000) {
          const moving = stepDistance == null
            ? true
            : stepDistance / (deltaMillis / 1000) >= MIN_MOVING_SPEED_MPS;
          if (moving) movingMillis += deltaMillis;
        }
      }
    }
    previous = point;
  }

  const movingSeconds = movingMillis > 0 ? Math.round(movingMillis / 1000) : null;
  let startTime = null;
  for (const point of points) {
    startTime = timeMs(point.timeMs);
    if (startTime != null) break;
  }
  let endTime = null;
  for (let index = points.length - 1; index >= 0; index--) {
    endTime = timeMs(points[index].timeMs);
    if (endTime != null) break;
  }

  const averageSpeed = movingSeconds != null && movingSeconds > 0 && distance > 0
    ? (distance / movingSeconds) * 3.6
    : null;
  const averagePace = movingSeconds != null && movingSeconds > 0 &&
    distance >= MIN_DISTANCE_FOR_PACE_METERS
    ? Math.max(1, Math.round(movingSeconds / (distance / 1000)))
    : null;

  const heartRates = [];
  const powers = [];
  let hasPositivePower = false;
  for (const point of points) {
    const heartRate = sensorValue(point, HEART_RATE_NAMES);
    if (heartRate != null && heartRate > 0) heartRates.push(heartRate);
    const power = sensorValue(point, POWER_NAMES);
    if (power != null && power >= 0) {
      powers.push(power);
      if (power > 0) hasPositivePower = true;
    }
  }

  const averageHeartRate = heartRates.length
    ? heartRates.reduce((sum, value) => sum + value, 0) / heartRates.length
    : null;
  const averagePower = powers.length && hasPositivePower
    ? powers.reduce((sum, value) => sum + value, 0) / powers.length
    : null;

  return {
    pointCount: points.length,
    // Contract uses epoch milliseconds everywhere; both spellings are provided
    // because Kotlin call sites refer to startTime/endTime.
    startTime,
    endTime,
    startTimeMs: startTime,
    endTimeMs: endTime,
    movingTimeSeconds: movingSeconds,
    distanceMeters: distance,
    averagePaceSecondsPerKm: averagePace,
    averageSpeedKmh: averageSpeed,
    averageHeartRate,
    averagePowerWatts: averagePower,
    totalAscentMeters: totalAscent(points)
  };
}

export function supports(stats, metric) {
  if (!stats) return false;
  switch (metric) {
    case 'MOVING_TIME':
      return stats.movingTimeSeconds != null && stats.movingTimeSeconds > 0;
    case 'DISTANCE':
      return stats.distanceMeters > 0;
    case 'AVERAGE_PACE':
      return stats.averagePaceSecondsPerKm != null;
    case 'AVERAGE_SPEED':
      return stats.averageSpeedKmh != null;
    case 'AVERAGE_HEART_RATE':
      return stats.averageHeartRate != null;
    case 'AVERAGE_POWER':
      return stats.averagePowerWatts != null;
    case 'TOTAL_ASCENT':
      return stats.totalAscentMeters != null;
    default:
      return false;
  }
}

function fixed(value, digits) {
  if (!Number.isFinite(value)) return null;
  return value.toLocaleString('ru-RU', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
    useGrouping: false
  });
}

function whole(value) {
  if (!Number.isFinite(value)) return null;
  return Math.round(value).toLocaleString('ru-RU', { useGrouping: false });
}

/** Human-readable values in the same style as PhotoShareController. */
export function formatMetric(metric, stats) {
  if (!supports(stats, metric)) return '\u2014';
  switch (metric) {
    case 'MOVING_TIME':
      return Geo.formatDuration(stats.movingTimeSeconds);
    case 'DISTANCE': {
      const meters = stats.distanceMeters;
      return meters < 1000
        ? Math.round(meters).toLocaleString('ru-RU', { useGrouping: false }) + ' ' + UNIT_M
        : (meters / 1000).toLocaleString('ru-RU', {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
            useGrouping: false
          }) + ' ' + UNIT_KM;
    }
    case 'AVERAGE_PACE': {
      const seconds = stats.averagePaceSecondsPerKm;
      return Math.floor(seconds / 60) + ':' +
        String(seconds % 60).padStart(2, '0') + ' /' + UNIT_KM;
    }
    case 'AVERAGE_SPEED':
      return fixed(stats.averageSpeedKmh, 1) + ' ' + UNIT_SPEED;
    case 'AVERAGE_HEART_RATE':
      return whole(stats.averageHeartRate) + ' ' + UNIT_HR;
    case 'AVERAGE_POWER':
      return whole(stats.averagePowerWatts) + ' ' + UNIT_POWER;
    case 'TOTAL_ASCENT':
      return whole(stats.totalAscentMeters) + ' ' + UNIT_M;
    default:
      return '\u2014';
  }
}
