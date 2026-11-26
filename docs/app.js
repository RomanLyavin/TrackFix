import { WEB_VERSION } from './version.js';

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

const FIX_WARNING_TEXT = {
  LARGE_CHANGES: 'Исправлено больше половины точек. Проверьте, что эталон действительно соответствует этой тренировке.',
  NO_REFERENCE_COVERAGE: 'Часть проблемных точек не удалось исправить: эталон не покрывает этот участок.',
  REFERENCE_SEGMENTS: 'В эталонном файле несколько несвязанных сегментов. Корректность стыков проверена приблизительно.',
  NO_GPS_RECONSTRUCTED: 'В исходном файле не было GPS-координат. Маршрут восстановлен по эталону: дистанция распределена по времени движения без пауз. Проверьте результат.',
  NOTHING_TO_FIX: 'Проблемных координат не найдено. Трек оставлен без изменений.'
};

const state = {
  fix: { output: null, name: null },
  merge: { output: null, name: null },
  photo: {
    track: null,
    stats: null,
    image: null,
    imageUrl: null,
    canvas: null
  },
  files: {
    original: null,
    reference: null,
    merge: [],
    photoTrack: null,
    photoImage: null
  }
};

let toastTimer = null;

function fillVersion() {
  $$('[data-version]').forEach((node) => {
    node.textContent = WEB_VERSION;
  });
}

function toast(message, isError = false) {
  const node = $('#toast');
  node.textContent = message;
  node.classList.toggle('is-error', isError);
  node.hidden = false;
  if (toastTimer) window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    node.hidden = true;
  }, 4200);
}

function setBusy(button, busy, busyText = 'Обработка…') {
  if (!button) return;
  if (busy) {
    button.dataset.originalText = button.textContent;
    button.textContent = busyText;
    button.disabled = true;
  } else {
    if (button.dataset.originalText) button.textContent = button.dataset.originalText;
    delete button.dataset.originalText;
    button.disabled = false;
  }
}

function readBytes(file) {
  return file.arrayBuffer().then((buffer) => new Uint8Array(buffer));
}

function formatDistance(meters) {
  const value = Number(meters);
  if (!Number.isFinite(value)) return '—';
  return value < 1000 ? `${value.toFixed(0)} м` : `${(value / 1000).toFixed(2)} км`;
}

function formatDuration(seconds) {
  const total = Math.max(0, Math.round(Number(seconds) || 0));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = total % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
    : `${minutes}:${String(rest).padStart(2, '0')}`;
}

function formatTime(ms) {
  if (ms == null || !Number.isFinite(Number(ms))) return '—';
  return new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(new Date(ms));
}

function fallbackName(name, suffix) {
  const dot = name.lastIndexOf('.');
  return `${dot > 0 ? name.slice(0, dot) : name}${suffix}.gpx`;
}

function makeCard(label, value) {
  const card = document.createElement('div');
  card.className = 'stat';
  const caption = document.createElement('span');
  caption.textContent = label;
  const text = document.createElement('strong');
  text.textContent = value;
  card.append(caption, text);
  return card;
}

function showCards(target, cards, { warnings = [], note = null } = {}) {
  target.hidden = false;
  target.replaceChildren();

  const title = document.createElement('h3');
  title.textContent = 'Результат';
  target.append(title);

  if (cards.length) {
    const grid = document.createElement('div');
    grid.className = 'stat-grid';
    cards.forEach(([label, value]) => grid.append(makeCard(label, value)));
    target.append(grid);
  }

  if (note) {
    const clean = document.createElement('p');
    clean.className = 'hint';
    clean.textContent = note;
    target.append(clean);
  }

  warnings.forEach((text) => {
    const warning = document.createElement('p');
    warning.className = 'warning';
    warning.textContent = text;
    target.append(warning);
  });
}

function showError(target, error) {
  target.hidden = false;
  target.replaceChildren();
  const text = document.createElement('p');
  text.className = 'error-text';
  text.textContent = `Ошибка: ${error && error.message ? error.message : error}`;
  target.append(text);
}

function downloadText(text, name) {
  const blob = new Blob([text], { type: 'application/gpx+xml;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function downloadCanvas(canvas, name) {
  canvas.toBlob((blob) => {
    if (!blob) {
      toast('Не удалось сохранить изображение', true);
      return;
    }
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }, 'image/png');
}

function bindTabs() {
  $$('.tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      const name = tab.dataset.panel;
      $$('.tab').forEach((item) => {
        const active = item === tab;
        item.classList.toggle('is-active', active);
        item.setAttribute('aria-selected', String(active));
      });
      $$('.panel').forEach((panel) => {
        const active = panel.id === `panel-${name}`;
        panel.hidden = !active;
        panel.classList.toggle('is-active', active);
      });
    });
  });
}

function bindDialogs() {
  $$('[data-open]').forEach((button) => {
    button.addEventListener('click', () => {
      const dialog = $(`#${button.dataset.open}-dialog`);
      if (!dialog) return;
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
    });
  });
}

function bindDrop(dropSelector, inputSelector, onFiles) {
  const drop = $(dropSelector);
  const input = $(inputSelector);
  if (!drop || !input) return;

  input.addEventListener('change', () => {
    onFiles(Array.from(input.files || []));
  });

  ['dragenter', 'dragover'].forEach((event) => {
    drop.addEventListener(event, (e) => {
      e.preventDefault();
      drop.classList.add('is-dragover');
    });
  });
  ['dragleave', 'drop'].forEach((event) => {
    drop.addEventListener(event, (e) => {
      e.preventDefault();
      drop.classList.remove('is-dragover');
    });
  });
  drop.addEventListener('drop', (e) => {
    const files = Array.from(e.dataTransfer?.files || []);
    if (!files.length) return;
    const transfer = new DataTransfer();
    files.forEach((file) => transfer.items.add(file));
    input.files = transfer.files;
    onFiles(files);
  });
}

function renderMergeList() {
  const list = $('#merge-list');
  list.replaceChildren();
  state.files.merge.forEach((file, index) => {
    const item = document.createElement('li');
    const label = document.createElement('span');
    label.textContent = `${index + 1}. ${file.name}`;
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.textContent = '×';
    remove.setAttribute('aria-label', `Удалить файл ${file.name}`);
    remove.addEventListener('click', () => {
      state.files.merge.splice(index, 1);
      const transfer = new DataTransfer();
      state.files.merge.forEach((entry) => transfer.items.add(entry));
      $('#merge-files').files = transfer.files;
      $('#merge-names').textContent = state.files.merge.length
        ? state.files.merge.map((entry) => entry.name).join(', ')
        : 'Файлы не выбраны';
      renderMergeList();
    });
    item.append(label, remove);
    list.append(item);
  });
}

function bindFileInputs() {
  bindDrop('#drop-original', '#original-file', (files) => {
    state.files.original = files[0] || null;
    $('#original-name').textContent = files[0]?.name || 'Файл не выбран';
    $('#drop-original').classList.toggle('has-file', Boolean(files[0]));
  });

  bindDrop('#drop-reference', '#reference-file', (files) => {
    state.files.reference = files[0] || null;
    $('#reference-name').textContent = files[0]?.name || 'Файл не выбран';
    $('#drop-reference').classList.toggle('has-file', Boolean(files[0]));
  });

  bindDrop('#drop-merge', '#merge-files', (files) => {
    state.files.merge = files.filter((file) => /\.(gpx|fit)$/i.test(file.name));
    $('#merge-names').textContent = state.files.merge.length
      ? state.files.merge.map((file) => file.name).join(', ')
      : 'Файлы не выбраны';
    renderMergeList();
  });

  bindDrop('#drop-photo-track', '#photo-track', (files) => {
    state.files.photoTrack = files[0] || null;
    state.photo.stats = null;
    $('#photo-track-name').textContent = files[0]?.name || 'Файл не выбран';
    $('#drop-photo-track').classList.toggle('has-file', Boolean(files[0]));
    if (files[0]) void refreshPhotoMetrics();
  });

  bindDrop('#drop-photo-image', '#photo-image', (files) => {
    const file = files[0];
    state.files.photoImage = file || null;
    state.photo.image = null;
    if (state.photo.imageUrl) URL.revokeObjectURL(state.photo.imageUrl);
    state.photo.imageUrl = file ? URL.createObjectURL(file) : null;
    $('#photo-image-name').textContent = file?.name || 'Файл не выбран';
    $('#drop-photo-image').classList.toggle('has-file', Boolean(file));
    if (file) {
      void loadImage(state.photo.imageUrl)
        .then((image) => { state.photo.image = image; })
        .catch(() => toast('Не удалось прочитать фотографию', true));
    }
  });
}

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('Не удалось загрузить изображение'));
    image.src = src;
  });
}

function bindFix() {
  const button = $('#fix-run');
  button.addEventListener('click', async () => {
    const original = state.files.original;
    const reference = state.files.reference;
    const resultNode = $('#fix-result');
    if (!original || !reference) {
      showError(resultNode, new Error('Выберите исходный трек и эталонный маршрут.'));
      return;
    }

    setBusy(button, true, 'Исправление…');
    resultNode.hidden = true;
    try {
      const { process } = await import('./js/core/fixengine.js');
      const { FIX_WARNINGS } = await import('./js/core/trackfixer.js');
      const [originalBytes, referenceBytes] = await Promise.all([
        readBytes(original),
        readBytes(reference)
      ]);
      const settings = {
        maxDeviationMeters: Math.max(1, Number($('#deviation').value) || 30),
        fillMissingElevation: $('#fill-elevation').checked,
        followReference: $('#follow-reference').checked,
        maxSpeedMetersPerSecond: 60
      };
      const result = await process(
        originalBytes, original.name,
        referenceBytes, reference.name,
        settings
      );

      state.fix.output = result.outputText;
      state.fix.name = result.suggestedName || fallbackName(original.name, '_fixed');
      $('#fix-download').disabled = false;

      const stats = result.stats || {};
      const cards = [
        ['Точек в треке', String(stats.totalPoints ?? '—')],
        ['Эталон', formatDistance(result.referenceLengthMeters)]
      ];
      if (stats.reconstructedCoordinates > 0) {
        cards.push(['Восстановлено по эталону', `${stats.reconstructedCoordinates}`]);
      }
      if (stats.insertedPoints > 0) {
        cards.push(['Добавлено точек в длинных пропусках', `${stats.insertedPoints}`]);
      }
      if (stats.fixedCoordinates > 0 && stats.reconstructedCoordinates === 0) {
        cards.push(['Исправлено координат', `${stats.fixedCoordinates}`]);
      }
      if (stats.frozenCoordinates > 0) {
        cards.push([
          'Восстановлено после зависания GPS',
          `${stats.frozenCoordinates} точек / ${stats.frozenRuns || 0} участков`
        ]);
      }
      if (stats.missingCoordinates > 0 && stats.reconstructedCoordinates === 0) {
        cards.push(['Заполнено пропущенных координат', `${stats.missingCoordinates}`]);
      }
      if (stats.filledElevations > 0) {
        cards.push(['Заполнено высот', `${stats.filledElevations}`]);
      }
      if (stats.problemBlocks > 0) {
        cards.push(['Проблемных участков', `${stats.problemBlocks}`]);
      }
      if (stats.leadingFixed > 0) {
        cards.push(['Восстановлено в начале трека', `${stats.leadingFixed}`]);
      }
      if (stats.trailingFixed > 0) {
        cards.push(['Восстановлено в конце трека', `${stats.trailingFixed}`]);
      }
      if (stats.unresolvedPoints > 0) {
        cards.push(['Не удалось исправить точек', `${stats.unresolvedPoints}`]);
      }
      if (stats.fixedCoordinates > 0) {
        cards.push([
          'Дистанция',
          `${formatDistance(stats.distanceBefore)} → ${formatDistance(stats.distanceAfter)}`
        ]);
      }
      if (stats.maxDeviationBefore > 0) {
        cards.push([
          'Максимальное отклонение до',
          stats.maxDeviationCapped ? `больше ${formatDistance(300)}` : formatDistance(stats.maxDeviationBefore)
        ]);
      }

      const names = FIX_WARNINGS || {};
      const warnings = Array.from(result.warnings || []).map((warning) => {
        const key = typeof warning === 'string' ? warning : '';
        return FIX_WARNING_TEXT[key] || names[key] || `Предупреждение: ${key}`;
      });
      const clean = stats.fixedCoordinates === 0 && stats.unresolvedPoints === 0
        ? FIX_WARNING_TEXT.NOTHING_TO_FIX
        : null;

      showCards(resultNode, cards, { warnings, note: clean });
      toast('Трек исправлен');
    } catch (error) {
      showError(resultNode, error);
      toast('Не удалось исправить трек', true);
    } finally {
      setBusy(button, false);
    }
  });

  $('#fix-download').addEventListener('click', () => {
    if (state.fix.output) downloadText(state.fix.output, state.fix.name);
  });
}

function bindMerge() {
  const button = $('#merge-run');
  button.addEventListener('click', async () => {
    const resultNode = $('#merge-result');
    if (state.files.merge.length < 2) {
      showError(resultNode, new Error('Добавьте минимум два файла.'));
      return;
    }

    setBusy(button, true, 'Объединение…');
    resultNode.hidden = true;
    try {
      const { merge } = await import('./js/core/merge.js');
      const items = await Promise.all(state.files.merge.map(async (file) => ({
        name: file.name,
        bytes: await readBytes(file)
      })));
      const result = await merge(items);

      state.merge.output = result.outputText;
      state.merge.name = result.suggestedName || 'trackfix_merged.gpx';
      $('#merge-download').disabled = false;

      const cards = [
        ['Объединено файлов', `${result.contributedSources} из ${result.inputSourceCount}`],
        ['Точек в результате', `${result.totalPoints}`],
        ['Сегментов в результате', `${result.segmentCount}`],
        ['Удалено пересекающихся точек', `${result.trimmedPoints}`],
        ['Период', `${formatTime(result.startTimeMs)} — ${formatTime(result.endTimeMs)}`],
        ['Дистанция', formatDistance(result.distanceMeters)]
      ];
      const warnings = [];
      if (result.skippedSources?.length) {
        warnings.push(`Полностью перекрыты по времени и пропущены: ${result.skippedSources.join(', ')}.`);
      }
      if (result.contributedSources === 1) {
        warnings.push('После обрезки пересечений остался только один файл. Проверьте порядок и время треков.');
      }

      showCards(resultNode, cards, { warnings });
      toast('Треки объединены');
    } catch (error) {
      showError(resultNode, error);
      toast('Не удалось объединить треки', true);
    } finally {
      setBusy(button, false);
    }
  });

  $('#merge-download').addEventListener('click', () => {
    if (state.merge.output) downloadText(state.merge.output, state.merge.name);
  });
}

async function refreshPhotoMetrics() {
  const file = state.files.photoTrack;
  if (!file) return;
  try {
    const { parseTrack } = await import('./js/core/fixengine.js');
    const { calculateStats, supports } = await import('./js/core/activity.js');
    const bytes = await readBytes(file);
    const parsed = parseTrack(bytes, file.name);
    const stats = calculateStats(parsed);
    state.photo.stats = stats;
    $$('.metrics input[type="checkbox"]').forEach((box) => {
      const available = supports(stats, box.value);
      box.disabled = !available;
      box.closest('.check')?.classList.toggle('is-disabled', !available);
      if (!available) box.checked = false;
    });
  } catch (error) {
    state.photo.stats = null;
    toast('Не удалось прочитать трек тренировки', true);
  }
}

function bindPhoto() {
  const button = $('#photo-render');
  const iconSelect = $('#photo-icon');
  const customWrap = $('#photo-custom-wrap');

  iconSelect.addEventListener('change', () => {
    customWrap.hidden = iconSelect.value !== 'text';
  });

  button.addEventListener('click', async () => {
    const preview = $('#photo-preview');
    if (!state.files.photoTrack) {
      preview.hidden = false;
      showError(preview, new Error('Сначала выберите трек тренировки.'));
      return;
    }
    if (!state.photo.image) {
      preview.hidden = false;
      showError(preview, new Error('Сначала выберите фотографию.'));
      return;
    }
    const metrics = $$('.metrics input[type="checkbox"]:checked').map((box) => box.value);
    if (!metrics.length) {
      preview.hidden = false;
      showError(preview, new Error('Выберите хотя бы один показатель.'));
      return;
    }

    setBusy(button, true, 'Создание изображения…');
    preview.hidden = true;
    try {
      await refreshPhotoMetrics();
      if (!state.photo.stats) throw new Error('Не удалось прочитать трек тренировки');

      const { renderPhotoCard } = await import('./js/photo.js');
      const { formatMetric } = await import('./js/core/activity.js');

      let typeIcon;
      if (iconSelect.value === 'text') {
        typeIcon = $('#photo-custom').value.trim() || 'ТРЕНИРОВКА';
      } else {
        typeIcon = await loadImage(`assets/ic_activity_${iconSelect.value}.png`);
      }
      const logo = $('#photo-logo').checked ? await loadImage('assets/kr_logo.jpg') : null;

      const canvas = renderPhotoCard({
        image: state.photo.image,
        stats: state.photo.stats,
        metrics,
        typeIcon,
        logo
      });

      state.photo.canvas = canvas;
      canvas.id = 'photo-canvas';
      preview.replaceChildren();
      const summary = document.createElement('div');
      summary.className = 'metric-summary';
      metrics.forEach((metric) => {
        const chip = document.createElement('span');
        chip.textContent = formatMetric(metric, state.photo.stats);
        summary.append(chip);
      });
      preview.append(summary, canvas);
      preview.hidden = false;
      $('#photo-download').disabled = false;
      toast('Карточка готова');
    } catch (error) {
      preview.hidden = false;
      showError(preview, error);
      toast('Не удалось создать изображение', true);
    } finally {
      setBusy(button, false);
    }
  });

  $('#photo-download').addEventListener('click', () => {
    if (!state.photo.canvas) return;
    downloadCanvas(state.photo.canvas, `trackfix_${Date.now()}.png`);
  });
}

fillVersion();
bindTabs();
bindDialogs();
bindFileInputs();
bindFix();
bindMerge();
bindPhoto();
