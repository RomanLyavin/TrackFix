# Web core module contract

All modules are browser ES modules. They must not import npm packages or use a build step.

## Common data shapes

- **time**: epoch milliseconds (`number`) or `null`. Never a `Date` in returned data.
- **TrackPoint**: `{ lat, lon, ele, timeMs, speed, distance, cadence, hr, power }`.
- **GpxPoint** extends TrackPoint with `{ index, segmentIndex, timeText, innerXml }`.
  `innerXml` is the serialized original XML inside `<trkpt>` (for example `<time>…</time><extensions>…</extensions>`).
- **GpxFile**: `{ points: GpxPoint[], namespaceUri: string|null, type: string|null, name: string|null }`.
- **RefVertex**: `{ lat, lon, ele }`.
- **Projection**: `{ pathIndex, s, distance, lat, lon, ele }`.

## `core/geo.js`

```js
export const Geo = {
  isValidCoordinate(lat, lon): boolean,
  distanceMeters(lat1, lon1, lat2, lon2): number,
  projectOnSegment(lat, lon, aLat, aLon, bLat, bLon): { t, distance, lat, lon },
  median(values: number[]): number|null,
  formatDistance(meters): string,
  formatDuration(seconds): string,
  secondsBetween(aMs, bMs): number|null
};
export function trackPoint(o): TrackPoint; // fills missing fields with null
```

## `core/gpx.js`

```js
export class GpxException extends Error { constructor(reason, message) }
export function parseGpx(text, { reference = false, sourceName = '' } = {}): GpxFile;
export function serializeGpx(file): string;
export function applyFixes(file, result): GpxFile;
export function referencePaths(file): RefVertex[][];
export function baseName(name): string;
export function detectTrackType(text): string|null;
```

`applyFixes` must change only `lat`, `lon` and, when requested, `ele`. It must preserve `innerXml`, point count, order and time. `result` has arrays `lat`, `lon`, `ele`, `coordinateChanged`, `elevationFilled`.

## `core/reference.js`

```js
export class RefPath {
  constructor(rawVertices: RefVertex[]);
  vertices, cumulative, totalLength, closed, repeatLapLength, repeatLapCount;
  candidates(lat, lon, maxDistance): Projection[]; // pathIndex = -1
  nearest(lat, lon, maxDistance): Projection|null;
  nearestCandidates(lat, lon, maxDistance, maxCandidates = 12): Projection[];
  pointAt(s, wrap = true): RefVertex;
  elevationAt(s): number|null;
  forwardLength(sA, sB): number|null;
  backwardLength(sA, sB): number|null;
}
export class ReferencePath {
  constructor(paths: RefVertex[][]);
  paths: RefPath[];
  totalLength: number;
  segmentCount: number;
  nearest(lat, lon, maxDistance): Projection|null;
  nearestCandidates(lat, lon, maxDistance, maxCandidates = 12): Projection[];
  pointAt(pathIndex, s, wrap = true): RefVertex;
}
```

Port `app/src/main/java/com/trackfix/app/core/ReferencePath.kt` exactly, including grid clustering, lap detection and wrapping.

## `core/fit.js`

```js
export function parseFit(bytes: Uint8Array): { records: TrackPoint[], sport: string|null };
export function fitToGpx(file, { sourceName = '', reference = false } = {}): GpxFile;
```

Port `app/src/main/java/com/trackfix/app/core/FitCodec.kt`. `fitToGpx` must produce a `GpxFile` compatible with `core/gpx.js` and write `<type>` from the FIT sport when available.

## `core/trackfixer.js`

```js
export const FIX_WARNINGS = { ... }; // names from Kotlin FixWarning
export function fixTrack(input: TrackPoint[], reference: ReferencePath, settings): FixResult;

FixSettings = {
  maxDeviationMeters: number = 30,
  fillMissingElevation: boolean = true,
  followReference: boolean = false,
  maxSpeedMetersPerSecond: number = 60
};

FixResult = {
  lat: (number|null)[], lon: (number|null)[], ele: (number|null)[],
  coordinateChanged: boolean[], elevationFilled: boolean[],
  stats: FixStats, warnings: string[]
};
```

Port `app/src/main/java/com/trackfix/app/core/TrackFixer.kt` exactly, including 1.7.9 fixes (`MAX_REPAIRED_FAR_RUN_POINTS`, frozen filtering, wider edge anchor).

## `core/fixengine.js`

```js
export function parseTrack(bytes, name, { reference = false } = {}): GpxFile;
export function inspectOriginal(bytes, name): { points, durationSeconds, distanceMeters };
export function process(originalBytes, originalName, referenceBytes, referenceName, settings): ProcessResult;

ProcessResult = {
  outputText: string, suggestedName: string, stats: FixStats,
  warnings: string[], referenceLengthMeters: number
};
```

`parseTrack` dispatches by extension (`fit`) or file signature. `process` preserves the source type and writes GPX.

## `core/merge.js`

```js
export function merge(items: { name, bytes: Uint8Array }[]): MergeResult;

MergeResult = {
  outputText, suggestedName, inputSourceCount, contributedSources,
  totalPoints, trimmedPoints, skippedSources, startTimeMs, endTimeMs,
  distanceMeters, segmentCount
};
```

Port `app/src/main/java/com/trackfix/app/core/MergeEngine.kt`, including preservation of `<type>` from the earliest source.

## `core/activity.js`

```js
export const METRICS = ['MOVING_TIME','DISTANCE','AVERAGE_PACE','AVERAGE_SPEED','AVERAGE_HEART_RATE','AVERAGE_POWER','TOTAL_ASCENT'];
export function calculateStats(file: GpxFile): ActivityStats;
export function supports(stats, metric): boolean;
export function formatMetric(metric, stats): string;
```

Port `app/src/main/java/com/trackfix/app/core/ActivityStats.kt`.

## `photo.js`

```js
export function renderPhotoCard({ image, stats, metrics, typeIcon, logo }): HTMLCanvasElement;
```

Uses canvas. Must mirror `app/src/main/java/com/trackfix/app/PhotoCardRenderer.kt`.
