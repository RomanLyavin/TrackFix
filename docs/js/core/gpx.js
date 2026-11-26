// Port of app/src/main/java/com/trackfix/app/core/GpxCodec.kt.
// The browser representation keeps the original child XML of each <trkpt> in
// `innerXml` instead of a live DOM Element, so every module can stay dependency-free.
import { Geo } from './geo.js';

const GPX_NS = 'http://www.topografix.com/GPX/1/1';
const XMLNS_NS = 'http://www.w3.org/2000/xmlns/';
const MAX_FILE_BYTES = 30 * 1024 * 1024;
const REFERENCE_JOIN_MAX_GAP_METERS = 100;
const HR_NAMES = ['hr', 'heartrate', 'heart_rate'];
const POWER_NAMES = ['power', 'watts', 'watt'];

export class GpxException extends Error {
  constructor(reason, message = reason) {
    super(message == null ? String(reason) : String(message));
    this.name = 'GpxException';
    this.reason = reason;
  }
}

function localName(node) {
  if (!node) return '';
  if (node.localName) return node.localName;
  const name = node.nodeName || '';
  const colon = name.indexOf(':');
  return colon >= 0 ? name.slice(colon + 1) : name;
}

function childElements(parent, name = null) {
  const result = [];
  const children = parent && parent.childNodes ? parent.childNodes : [];
  for (let i = 0; i < children.length; i++) {
    const child = children.item ? children.item(i) : children[i];
    if (child && child.nodeType === 1 && (name == null || localName(child) === name)) {
      result.push(child);
    }
  }
  return result;
}

function directChild(parent, name) {
  return childElements(parent, name)[0] || null;
}

function descendantElements(parent, name) {
  if (!parent || !parent.getElementsByTagName) return [];
  return Array.from(parent.getElementsByTagName('*')).filter(
    (element) => localName(element) === name
  );
}

function parseDoubleOrNull(value) {
  if (value == null) return null;
  const text = String(value).trim().replace(',', '.');
  if (!text) return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

function descendantDouble(element, name) {
  const nodes = descendantElements(element, name);
  return nodes.length ? parseDoubleOrNull(nodes[0].textContent) : null;
}

function descendantInt(element, name) {
  const value = descendantDouble(element, name);
  return value == null ? null : Math.trunc(value);
}

function sensorDouble(element, names) {
  for (const name of names) {
    const value = descendantDouble(element, name);
    if (value != null) return value;
  }
  return null;
}

/** ISO 8601 -> epoch milliseconds. Zone-less date/times are treated as UTC. */
function parseTime(value) {
  const text = value == null ? '' : String(value).trim();
  if (!text) return null;
  const hasZone = /(Z|[+-]\d{2}:?\d{2})$/i.test(text);
  const looksLikeDate = /^\d{4}-\d{2}-\d{2}(T|$)/.test(text);
  const candidate = !hasZone && text.includes('T') ? `${text}Z` : text;
  const ms = Date.parse(candidate);
  return Number.isFinite(ms) && looksLikeDate ? ms : null;
}

function formatTime(ms) {
  const iso = new Date(ms).toISOString();
  return iso.endsWith('.000Z') ? iso.slice(0, -5) + 'Z' : iso;
}

function formatCoordinate(value) {
  return value.toFixed(7);
}

function formatElevation(value) {
  return value
    .toFixed(2)
    .replace(/0+$/, '')
    .replace(/\.$/, '');
}

function escapeText(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function escapeAttr(value) {
  return escapeText(value).replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}

function newDocument(text) {
  if (typeof DOMParser === 'undefined') {
    throw new GpxException('cannot parse XML', 'DOMParser is not available');
  }
  const doc = new DOMParser().parseFromString(text, 'application/xml');
  const root = doc.documentElement;
  if (
    !root ||
    localName(root) === 'parsererror' ||
    doc.getElementsByTagName('parsererror').length > 0
  ) {
    throw new GpxException('cannot parse XML', 'cannot parse XML');
  }
  return doc;
}

function allElements(doc, name) {
  return Array.from(doc.getElementsByTagName('*')).filter(
    (element) => localName(element) === name
  );
}

function collectTrackElements(doc) {
  const result = [];
  const segments = allElements(doc, 'trkseg');
  if (segments.length) {
    segments.forEach((segment, segmentIndex) => {
      for (const element of childElements(segment, 'trkpt')) {
        result.push({ element, segmentIndex });
      }
    });
  } else {
    for (const element of allElements(doc, 'trkpt')) {
      result.push({ element, segmentIndex: 0 });
    }
  }
  return result;
}

function collectReferenceElements(doc) {
  const tracks = collectTrackElements(doc);
  if (tracks.length) return tracks;

  const result = [];
  const routes = allElements(doc, 'rte');
  if (routes.length) {
    routes.forEach((route, routeIndex) => {
      for (const element of childElements(route, 'rtept')) {
        result.push({ element, segmentIndex: routeIndex });
      }
    });
  } else {
    for (const element of allElements(doc, 'rtept')) {
      result.push({ element, segmentIndex: 0 });
    }
  }
  return result;
}

function trackMeta(doc) {
  let name = null;
  let type = null;
  for (const track of allElements(doc, 'trk')) {
    if (name == null) {
      const node = directChild(track, 'name');
      const text = node && node.textContent ? node.textContent.trim() : '';
      if (text) name = text;
    }
    if (type == null) {
      const node = directChild(track, 'type');
      const text = node && node.textContent ? node.textContent.trim() : '';
      if (text) type = text;
    }
    if (name != null && type != null) break;
  }
  if (name == null) {
    for (const node of allElements(doc, 'name')) {
      const text = node.textContent ? node.textContent.trim() : '';
      if (text) {
        name = text;
        break;
      }
    }
  }
  if (type == null) {
    for (const node of allElements(doc, 'type')) {
      const text = node.textContent ? node.textContent.trim() : '';
      if (text) {
        type = text;
        break;
      }
    }
  }
  return { name, type };
}

function rootMeta(doc) {
  const root = doc.documentElement;
  const attrs = [];
  if (root && root.attributes) {
    for (let i = 0; i < root.attributes.length; i++) {
      const attr = root.attributes.item ? root.attributes.item(i) : root.attributes[i];
      attrs.push({ name: attr.name, value: attr.value });
    }
  }
  return {
    namespaceUri: root && root.namespaceURI ? root.namespaceURI : null,
    prefix: root && root.prefix ? root.prefix : '',
    attrs
  };
}

function hide(object, key, value) {
  Object.defineProperty(object, key, { value, enumerable: false, configurable: true });
  return object;
}

function effectiveRootMeta(file) {
  const namespaceUri = file && file.namespaceUri ? file.namespaceUri : null;
  const prefix = file && file._rootPrefix ? file._rootPrefix : '';
  let attrs = file && file._rootAttrs
    ? file._rootAttrs.map((a) => ({ name: a.name, value: a.value }))
    : [
        { name: 'version', value: '1.1' },
        { name: 'creator', value: 'TrackFix' }
      ];
  if (!attrs.some((a) => a.name === 'version')) {
    attrs.push({ name: 'version', value: '1.1' });
  }
  if (!attrs.some((a) => a.name === 'creator')) {
    attrs.push({ name: 'creator', value: 'TrackFix' });
  }
  if (namespaceUri) {
    const declaration = prefix ? `xmlns:${prefix}` : 'xmlns';
    const existing = attrs.find((a) => a.name === declaration);
    if (!existing) attrs.unshift({ name: declaration, value: namespaceUri });
    else if (existing.value !== namespaceUri) existing.value = namespaceUri;
  }
  return { namespaceUri, prefix, attrs };
}

function makeWrapper(doc, meta, tagName) {
  const name = meta.prefix ? `${meta.prefix}:${tagName}` : tagName;
  let wrapper;
  if (meta.namespaceUri) wrapper = doc.createElementNS(meta.namespaceUri, name);
  else wrapper = doc.createElement(name);
  for (const attr of meta.attrs) {
    if (!/^xmlns(?::|$)/.test(attr.name)) continue;
    if (meta.prefix ? attr.name === `xmlns:${meta.prefix}` : attr.name === 'xmlns') {
      continue; // createElementNS already emitted the wrapper's own declaration
    }
    try {
      wrapper.setAttributeNS(XMLNS_NS, attr.name, attr.value);
    } catch (_) {
      wrapper.setAttribute(attr.name, attr.value);
    }
  }
  return wrapper;
}

/** Serializes the original children of a <trkpt> without losing namespace declarations. */
function serializeInner(element, meta) {
  if (!element || !element.ownerDocument || typeof XMLSerializer === 'undefined') return '';
  const doc = element.ownerDocument;
  const wrapper = makeWrapper(doc, meta, 'wrapper');
  for (const child of element.childNodes) {
    if (child.nodeType === 1) wrapper.appendChild(child.cloneNode(true));
    else if (child.nodeType === 3 && child.textContent.trim()) {
      wrapper.appendChild(doc.createTextNode(child.textContent));
    }
  }
  if (wrapper.childNodes.length === 0) return '';
  const serialized = new XMLSerializer().serializeToString(wrapper);
  return serialized.slice(serialized.indexOf('>') + 1, serialized.lastIndexOf('<'));
}

export function parseGpx(text, { reference = false, sourceName = '' } = {}) {
  let input = text;
  if (input instanceof Uint8Array) {
    if (typeof TextDecoder === 'undefined') {
      throw new GpxException('cannot parse XML', 'TextDecoder is not available');
    }
    input = new TextDecoder('utf-8').decode(input);
  }
  if (input == null || String(input).length === 0) {
    throw new GpxException('empty file', 'empty file');
  }
  if (String(input).length > MAX_FILE_BYTES) {
    throw new GpxException('file too large', 'file too large');
  }

  const doc = newDocument(String(input));
  const root = doc.documentElement;
  if (!root || localName(root).toLowerCase() !== 'gpx') {
    throw new GpxException('not a GPX document', 'not a GPX document');
  }
  const elements = reference
    ? collectReferenceElements(doc)
    : collectTrackElements(doc);
  if (elements.length === 0) throw new GpxException('no track points', 'no track points');

  const meta = rootMeta(doc);
  const names = trackMeta(doc);
  const points = elements.map(({ element, segmentIndex }, index) => {
    const timeText = directChild(element, 'time');
    const rawTime = timeText ? timeText.textContent : null;
    return {
      index,
      segmentIndex,
      lat: parseDoubleOrNull(element.getAttribute('lat')),
      lon: parseDoubleOrNull(element.getAttribute('lon')),
      ele: parseDoubleOrNull(directChild(element, 'ele')?.textContent),
      timeMs: parseTime(rawTime),
      timeText: rawTime == null ? null : String(rawTime),
      speed: descendantDouble(element, 'speed'),
      distance: descendantDouble(element, 'distance'),
      cadence: descendantInt(element, 'cad'),
      hr: sensorDouble(element, HR_NAMES),
      power: sensorDouble(element, POWER_NAMES),
      innerXml: serializeInner(element, meta)
    };
  });

  const file = {
    points,
    namespaceUri: meta.namespaceUri,
    type: names.type,
    name: names.name || (sourceName ? baseName(sourceName) : null)
  };
  hide(file, '_rootAttrs', meta.attrs);
  hide(file, '_rootPrefix', meta.prefix);
  hide(file, '_document', doc);
  return file;
}

function stitchReferencePaths(paths) {
  if (paths.length < 2) return paths;
  const result = [];
  let current = paths[0].slice();
  for (const next of paths.slice(1)) {
    const end = current[current.length - 1];
    const start = next[0];
    const gap = Geo.distanceMeters(end.lat, end.lon, start.lat, start.lon);
    if (gap <= REFERENCE_JOIN_MAX_GAP_METERS) current.push(...next);
    else {
      result.push(current);
      current = next.slice();
    }
  }
  result.push(current);
  return result;
}

export function referencePaths(file) {
  const grouped = new Map();
  for (const point of (file && file.points) || []) {
    if (!Geo.isValidCoordinate(point.lat, point.lon)) continue;
    const key = point.segmentIndex == null ? 0 : point.segmentIndex;
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key).push({ lat: point.lat, lon: point.lon, ele: point.ele ?? null });
  }
  const paths = Array.from(grouped.values())
    .filter((path) => path.length >= 2)
    .map((path) => path.slice());
  return stitchReferencePaths(paths);
}

function editElevationInnerXml(file, point, value) {
  if (typeof DOMParser === 'undefined' || typeof XMLSerializer === 'undefined') return;
  const meta = effectiveRootMeta(file);
  const qualify = (tag) => (meta.prefix ? `${meta.prefix}:${tag}` : tag);
  // Points built outside parseGpx may not have innerXml yet; synthesize it first
  // so adding <ele> does not drop time and sensor children.
  const inner =
    typeof point.innerXml === 'string' && point.innerXml.trim()
      ? point.innerXml
      : synthesizedInner(point, qualify);
  const name = qualify('fragment');
  const attrs = meta.attrs
    .filter((a) => /^xmlns(?::|$)/.test(a.name))
    .map((a) => ` ${a.name}="${escapeAttr(a.value)}"`)
    .join('');
  const doc = new DOMParser().parseFromString(
    `<${name}${attrs}>${inner}</${name}>`,
    'application/xml'
  );
  if (!doc.documentElement || doc.getElementsByTagName('parsererror').length) return;

  const wrapper = doc.documentElement;
  let ele = directChild(wrapper, 'ele');
  if (!ele) {
    const qualified = meta.prefix ? `${meta.prefix}:ele` : 'ele';
    ele = meta.namespaceUri
      ? doc.createElementNS(meta.namespaceUri, qualified)
      : doc.createElement(qualified);
    ele.textContent = formatElevation(value);
    const before = directChild(wrapper, 'time') || directChild(wrapper, 'extensions');
    if (before) wrapper.insertBefore(ele, before);
    else wrapper.appendChild(ele);
  } else {
    ele.textContent = formatElevation(value);
  }
  const serialized = new XMLSerializer().serializeToString(wrapper);
  point.innerXml = serialized.slice(serialized.indexOf('>') + 1, serialized.lastIndexOf('<'));
}

export function applyFixes(file, result) {
  const lat = (result && result.lat) || [];
  const lon = (result && result.lon) || [];
  const ele = (result && result.ele) || [];
  const coordinateChanged = (result && result.coordinateChanged) || [];
  const elevationFilled = (result && result.elevationFilled) || [];
  for (let i = 0; i < (file?.points || []).length; i++) {
    const point = file.points[i];
    const index = point.index == null ? i : point.index;
    if (coordinateChanged[index] && Number.isFinite(lat[index]) && Number.isFinite(lon[index])) {
      point.lat = lat[index];
      point.lon = lon[index];
    }
    if (elevationFilled[index] && Number.isFinite(ele[index])) {
      point.ele = ele[index];
      editElevationInnerXml(file, point, ele[index]);
    }
  }
  return file;
}

function synthesizedInner(point, qualify) {
  const parts = [];
  if (Number.isFinite(point.ele)) {
    parts.push(`<${qualify('ele')}>${formatElevation(point.ele)}</${qualify('ele')}>`);
  }
  const timeText = point.timeText || (Number.isFinite(point.timeMs) ? formatTime(point.timeMs) : null);
  if (timeText) {
    parts.push(`<${qualify('time')}>${escapeText(String(timeText).trim())}</${qualify('time')}>`);
  }
  const sensors = [];
  if (Number.isFinite(point.speed)) {
    sensors.push(`<${qualify('speed')}>${point.speed}</${qualify('speed')}>`);
  }
  if (Number.isFinite(point.distance)) {
    sensors.push(`<${qualify('distance')}>${point.distance}</${qualify('distance')}>`);
  }
  if (Number.isFinite(point.cadence)) {
    sensors.push(`<${qualify('cad')}>${Math.trunc(point.cadence)}</${qualify('cad')}>`);
  }
  if (Number.isFinite(point.hr)) {
    sensors.push(`<${qualify('hr')}>${point.hr}</${qualify('hr')}>`);
  }
  if (Number.isFinite(point.power)) {
    sensors.push(`<${qualify('power')}>${point.power}</${qualify('power')}>`);
  }
  if (sensors.length) {
    parts.push(`<${qualify('extensions')}>${sensors.join('')}</${qualify('extensions')}>`);
  }
  return parts.join('');
}

export function serializeGpx(file) {
  const meta = effectiveRootMeta(file);
  const qualify = (name) => (meta.prefix ? `${meta.prefix}:${name}` : name);
  const rootName = qualify('gpx');
  const attrText = meta.attrs
    .filter((a) => a.name)
    .map((a) => ` ${a.name}="${escapeAttr(a.value)}"`)
    .join('');
  const lines = [`<?xml version="1.0" encoding="UTF-8"?>`, `<${rootName}${attrText}>`];

  if (file?.name != null && String(file.name).trim()) {
    lines.push(`  <${qualify('trk')}>`);
    lines.push(`    <${qualify('name')}>${escapeText(String(file.name).trim())}</${qualify('name')}>`);
    if (file.type != null && String(file.type).trim()) {
      lines.push(`    <${qualify('type')}>${escapeText(String(file.type).trim())}</${qualify('type')}>`);
    }
  } else {
    lines.push(`  <${qualify('trk')}>`);
    if (file?.type != null && String(file.type).trim()) {
      lines.push(`    <${qualify('type')}>${escapeText(String(file.type).trim())}</${qualify('type')}>`);
    }
  }

  const segments = new Map();
  for (const point of (file && file.points) || []) {
    const key = point.segmentIndex == null ? 0 : point.segmentIndex;
    if (!segments.has(key)) segments.set(key, []);
    segments.get(key).push(point);
  }
  for (const points of segments.values()) {
    lines.push(`    <${qualify('trkseg')}>`);
    for (const point of points) {
      const lat = Number.isFinite(point.lat) ? formatCoordinate(point.lat) : '';
      const lon = Number.isFinite(point.lon) ? formatCoordinate(point.lon) : '';
      const inner = typeof point.innerXml === 'string' && point.innerXml.trim()
        ? point.innerXml
        : synthesizedInner(point, qualify);
      lines.push(
        `      <${qualify('trkpt')} lat="${lat}" lon="${lon}">${inner}</${qualify('trkpt')}>`
      );
    }
    lines.push(`    </${qualify('trkseg')}>`);
  }
  lines.push(`  </${qualify('trk')}>`);
  lines.push(`</${rootName}>`);
  return lines.join('\n') + '\n';
}

export function baseName(name) {
  const clean = String(name == null ? '' : name).split(/[\\/]/).pop() || '';
  const base = clean.includes('.') ? clean.slice(0, clean.lastIndexOf('.')) : clean;
  return base.trim() ? base : 'track';
}

export function detectTrackType(text) {
  try {
    const doc = newDocument(String(text));
    for (const node of allElements(doc, 'type')) {
      const value = node.textContent ? node.textContent.trim() : '';
      if (value) return value;
    }
  } catch (_) {
    return null;
  }
  return null;
}
