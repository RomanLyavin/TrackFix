// Port of app/src/main/java/com/trackfix/app/core/ReferencePath.kt.
import { Geo } from './geo.js';

function projection(s, distance, point) {
  return {
    pathIndex: -1,
    s,
    distance,
    lat: point.lat,
    lon: point.lon,
    ele: point.ele
  };
}

function clusterProjections(projections) {
  if (projections.length <= 1) return projections;
  const sorted = projections.slice().sort((a, b) => a.s - b.s);
  const clusters = [];
  for (const candidate of sorted) {
    const previous = clusters.length ? clusters[clusters.length - 1] : null;
    if (previous && Math.abs(previous.s - candidate.s) < 10) {
      if (candidate.distance < previous.distance) {
        clusters[clusters.length - 1] = candidate;
      }
    } else {
      clusters.push(candidate);
    }
  }
  return clusters;
}

class SegmentGrid {
  constructor(vertices) {
    this.cellSize = 100;
    this.vertices = vertices;
    if (vertices.length === 0) {
      this.cells = new Map();
      return;
    }
    this.minLat = Math.min(...vertices.map((v) => v.lat));
    this.minLon = Math.min(...vertices.map((v) => v.lon));
    const meanLat = vertices.reduce((sum, v) => sum + v.lat, 0) / vertices.length;
    this.metersPerDegreeLat = 111320;
    this.metersPerDegreeLon = Math.max(
      1,
      111320 * Math.cos((meanLat * Math.PI) / 180)
    );
    this.cells = new Map();
    for (let index = 0; index < vertices.length - 1; index++) {
      const a = vertices[index];
      const b = vertices[index + 1];
      const ax = this.x(a.lon);
      const ay = this.y(a.lat);
      const bx = this.x(b.lon);
      const by = this.y(b.lat);
      const minCellX = Math.floor(Math.min(ax, bx) / this.cellSize);
      const maxCellX = Math.floor(Math.max(ax, bx) / this.cellSize);
      const minCellY = Math.floor(Math.min(ay, by) / this.cellSize);
      const maxCellY = Math.floor(Math.max(ay, by) / this.cellSize);
      for (let cx = minCellX; cx <= maxCellX; cx++) {
        for (let cy = minCellY; cy <= maxCellY; cy++) {
          const key = SegmentGrid.key(cx, cy);
          let list = this.cells.get(key);
          if (!list) this.cells.set(key, (list = []));
          list.push(index);
        }
      }
    }
  }

  x(lon) {
    return (lon - this.minLon) * this.metersPerDegreeLon;
  }

  y(lat) {
    return (lat - this.minLat) * this.metersPerDegreeLat;
  }

  // Kotlin: (cx shl 32) xor (cy and 0xffffffffL). BigInt keeps all 64 bits.
  static key(cx, cy) {
    return (
      (BigInt(cx) << 32n) ^ (BigInt(cy) & 0xffffffffn)
    ).toString();
  }

  query(lat, lon, radius) {
    if (!this.cells.size) return [];
    const px = this.x(lon);
    const py = this.y(lat);
    const minCellX = Math.floor((px - radius) / this.cellSize);
    const maxCellX = Math.floor((px + radius) / this.cellSize);
    const minCellY = Math.floor((py - radius) / this.cellSize);
    const maxCellY = Math.floor((py + radius) / this.cellSize);
    const result = new Set();
    for (let cx = minCellX; cx <= maxCellX; cx++) {
      for (let cy = minCellY; cy <= maxCellY; cy++) {
        const list = this.cells.get(SegmentGrid.key(cx, cy));
        if (list) for (const index of list) result.add(index);
      }
    }
    return Array.from(result);
  }
}

export class RefPath {
  constructor(rawVertices) {
    const cleaned = [];
    for (const vertex of rawVertices || []) {
      const previous = cleaned.length ? cleaned[cleaned.length - 1] : null;
      if (
        previous == null ||
        Geo.distanceMeters(previous.lat, previous.lon, vertex.lat, vertex.lon) >
          0.2
      ) {
        cleaned.push({ lat: vertex.lat, lon: vertex.lon, ele: vertex.ele ?? null });
      }
    }
    this.vertices = cleaned;

    this.cumulative = new Array(cleaned.length).fill(0);
    let total = 0;
    for (let i = 1; i < cleaned.length; i++) {
      const a = cleaned[i - 1];
      const b = cleaned[i];
      total += Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon);
      this.cumulative[i] = total;
    }
    this.totalLength = total;

    // GPS drift often leaves a small gap in a recorded loop. Close it so the
    // tail of a track can wrap around to the start line.
    const endpointGap =
      cleaned.length > 2
        ? Geo.distanceMeters(
            cleaned[0].lat,
            cleaned[0].lon,
            cleaned[cleaned.length - 1].lat,
            cleaned[cleaned.length - 1].lon
          )
        : Infinity;
    const closeTolerance = Math.max(30, Math.min(75, total * 0.01));
    this.closed =
      cleaned.length > 2 && total > 200 && endpointGap <= closeTolerance;

    // A stadium reference repeats the same short lap many times. A projection
    // shifted by a whole lap is the same physical place, not a branch error.
    let lapLength = 0;
    if (cleaned.length > 20) {
      for (let i = 20; i < cleaned.length; i++) {
        if (
          this.cumulative[i] > 200 &&
          Geo.distanceMeters(
            cleaned[0].lat,
            cleaned[0].lon,
            cleaned[i].lat,
            cleaned[i].lon
          ) <= 30
        ) {
          lapLength = this.cumulative[i];
          break;
        }
      }
    }
    this.repeatLapLength = lapLength > 50 ? lapLength : total;
    this.repeatLapCount =
      lapLength > 50 ? Math.max(1, Math.round(total / lapLength)) : 1;

    this.grid = new SegmentGrid(cleaned);
  }

  candidates(lat, lon, maxDistance) {
    const segmentIndices = this.grid.query(lat, lon, maxDistance);
    if (segmentIndices.length === 0) return [];
    const result = [];
    for (const index of segmentIndices) {
      const a = this.vertices[index];
      const b = this.vertices[index + 1];
      if (!a || !b) continue;
      const p = Geo.projectOnSegment(lat, lon, a.lat, a.lon, b.lat, b.lon);
      if (p.distance <= maxDistance) {
        const s =
          this.cumulative[index] +
          p.t * (this.cumulative[index + 1] - this.cumulative[index]);
        result.push(projection(s, p.distance, { lat: p.lat, lon: p.lon, ele: this.elevationAt(s) }));
      }
    }
    return clusterProjections(result);
  }

  nearest(lat, lon, maxDistance) {
    let best = null;
    for (const candidate of this.candidates(lat, lon, maxDistance)) {
      if (best == null || candidate.distance < best.distance) best = candidate;
    }
    return best;
  }

  nearestCandidates(lat, lon, maxDistance, maxCandidates = 12) {
    const all = this.candidates(lat, lon, maxDistance);
    if (all.length === 0) return [];
    const sorted = all.slice().sort((a, b) => a.distance - b.distance);
    const result = [];
    for (const candidate of sorted) {
      if (result.some((it) => Math.abs(it.s - candidate.s) < 12)) continue;
      result.push(candidate);
      if (result.length >= maxCandidates) break;
    }
    return result;
  }

  pointAt(s, wrap = true) {
    if (this.vertices.length === 0) return { lat: 0, lon: 0, ele: null };
    if (this.vertices.length === 1 || this.totalLength <= 1e-9) {
      return this.vertices[0];
    }

    let position = s;
    if (this.closed && wrap) {
      position = ((position % this.totalLength) + this.totalLength) % this.totalLength;
    } else {
      position = Math.min(this.totalLength, Math.max(0, position));
    }

    let low = 0;
    let high = this.vertices.length - 1;
    while (low + 1 < high) {
      const middle = Math.floor((low + high) / 2);
      if (this.cumulative[middle] <= position) low = middle;
      else high = middle;
    }
    const start = this.cumulative[low];
    const end = this.cumulative[low + 1];
    const t =
      end - start <= 1e-9
        ? 0
        : Math.min(1, Math.max(0, (position - start) / (end - start)));
    const a = this.vertices[low];
    const b = this.vertices[low + 1];
    let ele = null;
    if (a.ele != null && b.ele != null) ele = a.ele + (b.ele - a.ele) * t;
    else if (a.ele != null) ele = a.ele;
    else ele = b.ele ?? null;
    return {
      lat: a.lat + (b.lat - a.lat) * t,
      lon: a.lon + (b.lon - a.lon) * t,
      ele
    };
  }

  elevationAt(s) {
    return this.pointAt(s, this.closed).ele;
  }

  forwardLength(sA, sB) {
    if (!this.closed && sB < sA) return null;
    return this.closed
      ? (((sB - sA) % this.totalLength) + this.totalLength) % this.totalLength
      : sB - sA;
  }

  backwardLength(sA, sB) {
    if (!this.closed && sB > sA) return null;
    return this.closed
      ? (((sA - sB) % this.totalLength) + this.totalLength) % this.totalLength
      : sA - sB;
  }
}

export class ReferencePath {
  constructor(paths) {
    this.paths = (paths || [])
      .map((raw) => new RefPath(raw))
      .filter((path) => path.vertices.length >= 2);
    if (this.paths.length === 0) {
      throw new Error('reference has no usable points');
    }
    this.totalLength = this.paths.reduce((sum, path) => sum + path.totalLength, 0);
    this.segmentCount = this.paths.reduce(
      (sum, path) => sum + (path.vertices.length - 1),
      0
    );
  }

  nearest(lat, lon, maxDistance) {
    let best = null;
    for (let pathIndex = 0; pathIndex < this.paths.length; pathIndex++) {
      for (const candidate of this.paths[pathIndex].candidates(lat, lon, maxDistance)) {
        const withPath = { ...candidate, pathIndex };
        if (best == null || withPath.distance < best.distance) best = withPath;
      }
    }
    return best;
  }

  nearestCandidates(lat, lon, maxDistance, maxCandidates = 12) {
    const all = [];
    for (let pathIndex = 0; pathIndex < this.paths.length; pathIndex++) {
      for (const candidate of this.paths[pathIndex].candidates(lat, lon, maxDistance)) {
        all.push({ ...candidate, pathIndex });
      }
    }
    if (all.length === 0) return [];
    const result = [];
    for (const candidate of all.sort((a, b) => a.distance - b.distance)) {
      if (
        result.some(
          (it) => it.pathIndex === candidate.pathIndex && Math.abs(it.s - candidate.s) < 12
        )
      ) {
        continue;
      }
      result.push(candidate);
      if (result.length >= maxCandidates) break;
    }
    return result;
  }

  pointAt(pathIndex, s, wrap = true) {
    return this.paths[pathIndex].pointAt(s, wrap);
  }
}
