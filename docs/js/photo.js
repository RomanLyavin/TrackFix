// Port of app/src/main/java/com/trackfix/app/PhotoCardRenderer.kt.
// Browser ES module, no build step and no npm dependencies.

import { METRICS, formatMetric, supports } from './core/activity.js';

// Filled from strings.xml by the porting script as \uXXXX escapes.
const SHORT_LABELS = {"MOVING_TIME":"\u0412\u0420\u0415\u041C\u042F","DISTANCE":"\u0414\u0418\u0421\u0422\u0410\u041D\u0426\u0418\u042F","AVERAGE_PACE":"\u0421\u0420\u0415\u0414\u041D\u0418\u0419 \u0422\u0415\u041C\u041F","AVERAGE_SPEED":"\u0421\u0420\u0415\u0414\u041D\u042F\u042F \u0421\u041A\u041E\u0420\u041E\u0421\u0422\u042C","AVERAGE_HEART_RATE":"\u0421\u0420\u0415\u0414\u041D\u0418\u0419 \u041F\u0423\u041B\u042C\u0421","AVERAGE_POWER":"\u0421\u0420\u0415\u0414\u041D\u042F\u042F \u041C\u041E\u0429\u041D\u041E\u0421\u0422\u042C","TOTAL_ASCENT":"\u041E\u0411\u0429\u0418\u0419 \u041F\u041E\u0414\u042A\u0401\u041C"};
const TITLE = '\u0422\u0420\u0415\u041D\u0418\u0420\u041E\u0412\u041A\u0410';
const RUN_LABEL = '\u0411\u0435\u0433';
const BIKE_LABEL = '\u0412\u0435\u043B\u043E';

function isDrawable(value) {
  if (!value || typeof value !== 'object') return false;
  if (typeof HTMLImageElement !== 'undefined' && value instanceof HTMLImageElement) return true;
  if (typeof HTMLCanvasElement !== 'undefined' && value instanceof HTMLCanvasElement) return true;
  if (typeof ImageBitmap !== 'undefined' && value instanceof ImageBitmap) return true;
  return typeof value.width === 'number' && typeof value.height === 'number';
}

function imageSize(value) {
  if (!isDrawable(value)) return null;
  const width = value.naturalWidth || value.width;
  const height = value.naturalHeight || value.height;
  return width > 0 && height > 0 ? { width, height } : null;
}

function drawCover(ctx, source, x, y, width, height) {
  const size = imageSize(source);
  if (!size) return false;
  try {
    ctx.drawImage(source, x, y, width, height);
    return true;
  } catch (error) {
    return false;
  }
}

function activityParts(typeIcon) {
  if (isDrawable(typeIcon)) return { icon: typeIcon, text: '' };
  if (typeof typeIcon === 'string') {
    const value = typeIcon.trim();
    const key = value.toLowerCase();
    if (key === 'run') return { icon: null, text: RUN_LABEL };
    if (key === 'bike' || key === 'cycling') {
      return { icon: null, text: BIKE_LABEL };
    }
    return { icon: null, text: value };
  }
  if (typeIcon && typeof typeIcon === 'object') {
    return {
      icon: isDrawable(typeIcon.icon) ? typeIcon.icon : null,
      text: typeof typeIcon.text === 'string' ? typeIcon.text : ''
    };
  }
  return { icon: null, text: '' };
}

function formatDate(timeMs) {
  if (typeof timeMs !== 'number' || !Number.isFinite(timeMs)) return '';
  const date = new Date(timeMs);
  const pad = (value) => String(value).padStart(2, '0');
  return pad(date.getDate()) + '.' + pad(date.getMonth() + 1) + '.' + date.getFullYear();
}

function metricLines(metrics, stats) {
  const requested = Array.isArray(metrics)
    ? metrics
    : METRICS.filter((metric) => supports(stats, metric)).slice(0, 2);
  const lines = [];
  for (const entry of requested) {
    if (typeof entry === 'string') {
      const supported = supports(stats, entry);
      if (stats && !supported) continue;
      lines.push({
        label: SHORT_LABELS[entry] || entry,
        value: supported ? formatMetric(entry, stats) : '\u2014'
      });
    } else if (entry && typeof entry === 'object') {
      const metric = entry.metric;
      const label = entry.label || SHORT_LABELS[metric] || '';
      const value = entry.value || (metric ? formatMetric(metric, stats) : '');
      if (label && value) lines.push({ label, value });
    }
  }
  return lines.slice(0, 7);
}

function setFont(ctx, size, bold) {
  ctx.font = (bold ? '700 ' : '400 ') + size.toFixed(2) + 'px sans-serif';
  ctx.textBaseline = 'alphabetic';
}

function fontHeight(ctx, size, bold) {
  setFont(ctx, size, bold);
  const metrics = ctx.measureText('\u041C');
  const ascent = metrics.actualBoundingBoxAscent || size * 0.8;
  const descent = metrics.actualBoundingBoxDescent || size * 0.2;
  return Math.max(size, ascent + descent);
}

function setTextStyle(ctx, size, bold, color) {
  setFont(ctx, size, bold);
  ctx.fillStyle = color;
  ctx.textAlign = 'left';
  ctx.shadowColor = 'rgba(0, 0, 0, 0.75)';
  ctx.shadowBlur = Math.max(1, size * 0.16);
  ctx.shadowOffsetX = 0;
  ctx.shadowOffsetY = size * 0.06;
}

function fittedSize(ctx, text, size, bold, maxWidth, minSize) {
  let current = size;
  const limit = minSize || 8;
  setFont(ctx, current, bold);
  while (current > limit && ctx.measureText(text).width > maxWidth) {
    current *= 0.94;
    setFont(ctx, current, bold);
  }
  return current;
}

function roundedRect(ctx, x, y, width, height, radius) {
  const r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + width - r, y);
  ctx.quadraticCurveTo(x + width, y, x + width, y + r);
  ctx.lineTo(x + width, y + height - r);
  ctx.quadraticCurveTo(x + width, y + height, x + width - r, y + height);
  ctx.lineTo(x + r, y + height);
  ctx.quadraticCurveTo(x, y + height, x, y + height - r);
  ctx.lineTo(x, y + r);
  ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();
}

/**
 * Draws the selected training data over a photo on an HTMLCanvasElement.
 * Images are optional: text is always drawn, even when a bitmap is missing.
 */
export function renderPhotoCard({ image, stats, metrics, typeIcon, logo } = {}) {
  const photoSize = imageSize(image);
  const width = photoSize ? photoSize.width : 1080;
  const height = photoSize ? photoSize.height : 1350;

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('canvas 2d context is not available');

  if (!drawCover(ctx, image, 0, 0, width, height)) {
    const gradient = ctx.createLinearGradient(0, 0, 0, height);
    gradient.addColorStop(0, '#1b2430');
    gradient.addColorStop(1, '#0b1016');
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, width, height);
  }

  const lines = metricLines(metrics, stats);
  const subtitle = formatDate(stats && stats.startTimeMs != null
    ? stats.startTimeMs
    : stats && stats.startTime);
  const activity = activityParts(typeIcon);
  const logoSize = imageSize(logo);

  const base = Math.min(width, height);
  const columns = lines.length <= 1 ? 1 : 2;
  const rows = Math.max(1, Math.ceil(lines.length / columns));
  const margin = width * 0.055;
  const contentLeft = margin;
  const contentRight = width - margin;
  const contentBottom = height - margin;

  let titleSize = base * 0.028;
  let subtitleSize = base * 0.021;
  let labelSize = base * 0.0285;
  let valueSize = columns === 1 ? base * 0.078 : base * 0.057;
  let cellGapY = base * 0.018;
  let headerGap = base * 0.018;

  function headerHeight() {
    const title = fontHeight(ctx, titleSize, true);
    const subtitleHeight = subtitle ? fontHeight(ctx, subtitleSize, false) : 0;
    const logoHeight = logoSize ? title * 1.5 : 0;
    return Math.max(title, subtitleHeight, logoHeight);
  }

  function cellHeight() {
    return fontHeight(ctx, labelSize, false) +
      fontHeight(ctx, valueSize, true) + base * 0.006;
  }

  function contentHeight() {
    return headerHeight() + headerGap + rows * cellHeight() +
      (rows - 1) * cellGapY;
  }

  let attempts = 0;
  while (contentHeight() > height * 0.72 && attempts < 12) {
    const scale = 0.88;
    titleSize *= scale;
    subtitleSize *= scale;
    labelSize *= scale;
    valueSize *= scale;
    cellGapY *= scale;
    headerGap *= scale;
    attempts++;
  }

  const header = headerHeight();
  const headerTop = contentBottom - contentHeight();
  const gridTop = headerTop + header + headerGap;
  const availableWidth = contentRight - contentLeft;
  const panelPadX = base * 0.03;
  const panelPadY = base * 0.035;

  ctx.shadowColor = 'transparent';
  ctx.shadowBlur = 0;
  ctx.shadowOffsetX = 0;
  ctx.shadowOffsetY = 0;
  roundedRect(
    ctx,
    contentLeft - panelPadX,
    headerTop - panelPadY,
    availableWidth + panelPadX * 2,
    Math.min(height, contentBottom + panelPadY) - (headerTop - panelPadY),
    base * 0.02
  );
  ctx.fillStyle = 'rgba(0, 0, 0, 0.42)';
  ctx.fill();

  setTextStyle(ctx, titleSize, true, '#ffffff');
  const titleBaseline = headerTop + fontHeight(ctx, titleSize, true) * 0.8;
  ctx.fillText(TITLE, contentLeft, titleBaseline);
  const titleWidth = ctx.measureText(TITLE).width;

  let subtitleWidth = 0;
  if (subtitle) {
    setTextStyle(ctx, subtitleSize, false, 'rgba(235, 245, 240, 0.82)');
    ctx.textAlign = 'right';
    const baseline = headerTop + fontHeight(ctx, subtitleSize, false) * 0.8;
    ctx.fillText(subtitle, contentRight, baseline);
    subtitleWidth = ctx.measureText(subtitle).width;
    ctx.textAlign = 'left';
  }

  const suffixLeft = contentLeft + titleWidth + base * 0.012;
  const suffixRight = contentRight - subtitleWidth -
    (subtitleWidth > 0 ? base * 0.03 : 0);
  const suffixAvailable = suffixRight - suffixLeft;
  if ((activity.icon || activity.text || logoSize) && suffixAvailable > base * 0.01) {
    const lineHeight = fontHeight(ctx, titleSize, true);
    const centerY = titleBaseline - lineHeight * 0.3;
    const gap = base * 0.012;

    let iconHeight = lineHeight * 0.92;
    const iconSize = imageSize(activity.icon);
    let iconWidth = iconSize ? (iconHeight * iconSize.width) / iconSize.height : 0;
    let logoHeight = logoSize ? lineHeight * 1.38 : 0;
    let logoWidth = logoSize ? (logoHeight * logoSize.width) / logoSize.height : 0;

    let textSize = 0;
    let textWidth = 0;
    if (!iconSize && activity.text) {
      textSize = fittedSize(
        ctx, activity.text, titleSize * 0.82, true, suffixAvailable
      );
      setFont(ctx, textSize, true);
      textWidth = ctx.measureText(activity.text).width;
    }

    const activityWidth = iconSize ? iconWidth : textWidth;
    let totalWidth = activityWidth + logoWidth;
    if (activityWidth > 0 && logoWidth > 0) totalWidth += gap;
    if (totalWidth > suffixAvailable && totalWidth > 0) {
      const scale = suffixAvailable / totalWidth;
      iconHeight *= scale;
      iconWidth *= scale;
      logoHeight *= scale;
      logoWidth *= scale;
      if (!iconSize && activity.text) {
        setFont(ctx, textSize * scale, true);
        textWidth = ctx.measureText(activity.text).width;
      }
    }

    let cursor = suffixLeft;
    if (iconSize && drawCover(ctx, activity.icon, cursor, centerY - iconHeight / 2,
      iconWidth, iconHeight)) {
      cursor += iconWidth;
    } else if (!iconSize && activity.text) {
      ctx.fillStyle = 'rgba(255, 255, 255, 0.92)';
      ctx.textAlign = 'left';
      ctx.fillText(activity.text, cursor, titleBaseline);
      cursor += textWidth;
    }
    if (logoSize) {
      if (cursor > suffixLeft) cursor += gap;
      drawCover(ctx, logo, cursor, centerY - logoHeight / 2, logoWidth, logoHeight);
    }
  }

  const columnGap = columns === 2 ? width * 0.035 : 0;
  const columnWidth = (availableWidth - columnGap * (columns - 1)) / columns;
  const cell = cellHeight();

  lines.forEach((line, index) => {
    const column = index % columns;
    const row = Math.floor(index / columns);
    const x = contentLeft + column * (columnWidth + columnGap);
    const top = gridTop + row * (cell + cellGapY);
    const label = line.label.toLocaleUpperCase('ru-RU');

    const labelSizeFit = fittedSize(ctx, label, labelSize, false, columnWidth);
    setTextStyle(ctx, labelSizeFit, false, 'rgba(235, 245, 240, 0.74)');
    const labelHeight = fontHeight(ctx, labelSizeFit, false);
    ctx.fillText(label, x, top + labelHeight * 0.8);

    const valueTop = top + labelHeight + base * 0.002;
    const valueSizeFit = fittedSize(ctx, line.value, valueSize, true, columnWidth);
    setTextStyle(ctx, valueSizeFit, true, '#ffffff');
    const valueHeight = fontHeight(ctx, valueSizeFit, true);
    ctx.fillText(line.value, x, valueTop + valueHeight * 0.8);
  });

  return canvas;
}
