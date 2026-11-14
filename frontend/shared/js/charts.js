// Gráficos SVG livianos para las estadísticas (sin librerías): líneas con cursor y columnas con tooltip.
// Reglas: un solo eje, trazos de 2px, columnas de hasta 24px con punta redondeada, grilla tenue, leyenda con
// 2+ series, tooltip por hover y teclado, y siempre una vista de tabla con los mismos números.
// Paleta validada (azul/naranja, CVD ΔE 24,7 sobre blanco).

const NS = 'http://www.w3.org/2000/svg';
export const SERIES = ['#2a78d6', '#eb6834'];

function svg(tag, attrs = {}) {
  const node = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
  return node;
}

function html(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
}

/** Máximo "redondo" del eje y sus marcas (0, 5, 10…). */
function niceScale(max, ticks = 4) {
  if (max <= 0) return { max: ticks, step: 1 };
  const raw = max / ticks;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((f) => f * mag).find((s) => s >= raw && (s >= 1 || mag < 1)) || 10 * mag;
  const stepInt = max < ticks ? 1 : step;
  return { max: Math.ceil(max / stepInt) * stepInt, step: stepInt };
}

const compact = new Intl.NumberFormat('es-AR', { notation: 'compact', maximumFractionDigits: 1 });
const full = new Intl.NumberFormat('es-AR');
const tickText = (v, max) => (max >= 10000 ? compact.format(v) : full.format(v));

function tooltipEl(box) {
  let tip = box.querySelector('.chart-tooltip');
  if (!tip) {
    tip = html('div', 'chart-tooltip');
    tip.hidden = true;
    tip.setAttribute('role', 'status');
    box.append(tip);
  }
  return tip;
}

function placeTooltip(box, tip, x, y) {
  tip.hidden = false;
  const bw = box.clientWidth;
  const tw = tip.offsetWidth;
  const left = Math.min(Math.max(x - tw / 2, 0), bw - tw);
  tip.style.left = `${left}px`;
  tip.style.top = `${Math.max(y - tip.offsetHeight - 10, 0)}px`;
}

function fillTooltip(tip, title, rows) {
  tip.replaceChildren(html('div', 'tt-title', title), ...rows.map(({ color, name, value }) => {
    const row = html('div', 'tt-row');
    const key = html('span', 'tt-key');
    key.style.background = color;
    row.append(key, html('b', null, value), html('span', null, name));
    return row;
  }));
}

/** Tabla accesible con los mismos datos, detrás de un botón. */
export function tableToggle(container, headers, rows) {
  const button = html('button', 'btn btn-link p-0 chart-table-toggle', 'Ver como tabla');
  button.type = 'button';
  button.setAttribute('aria-expanded', 'false');
  const wrap = html('div', 'table-responsive');
  wrap.hidden = true;
  const table = html('table', 'table chart-table');
  const thead = html('thead');
  const tr = html('tr');
  headers.forEach((h, i) => { const th = html('th', i ? 'num' : '', h); th.scope = 'col'; tr.append(th); });
  thead.append(tr);
  const tbody = html('tbody');
  rows.forEach((r) => {
    const row = html('tr');
    r.forEach((cell, i) => row.append(html(i ? 'td' : 'th', i ? 'num' : '', cell)));
    row.firstChild.scope = 'row';
    tbody.append(row);
  });
  table.append(thead, tbody);
  wrap.append(table);
  button.addEventListener('click', () => {
    wrap.hidden = !wrap.hidden;
    button.setAttribute('aria-expanded', String(!wrap.hidden));
    button.textContent = wrap.hidden ? 'Ver como tabla' : 'Ocultar tabla';
  });
  container.append(button, wrap);
}

/**
 * Gráfico de líneas con cursor que busca el punto más cercano.
 * @param {HTMLElement} box
 * @param {{labels: string[], tickLabels: string[], series: {name: string, values: number[], color: string}[], ariaLabel: string}} data
 */
export function lineChart(box, data) {
  box.querySelector('svg')?.remove();
  const W = Math.max(box.clientWidth, 280);
  const H = 220;
  const pad = { l: 36, r: 12, t: 12, b: 26 };
  const n = data.labels.length;
  const maxValue = Math.max(0, ...data.series.flatMap((s) => s.values));
  const { max, step } = niceScale(maxValue);
  const x = (i) => pad.l + (n === 1 ? (W - pad.l - pad.r) / 2 : (i * (W - pad.l - pad.r)) / (n - 1));
  const y = (v) => pad.t + (H - pad.t - pad.b) * (1 - v / max);

  const root = svg('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': data.ariaLabel });
  const grid = svg('g', { class: 'chart-grid' });
  for (let v = 0; v <= max + 1e-9; v += step) {
    grid.append(svg('line', { x1: pad.l, x2: W - pad.r, y1: y(v), y2: y(v) }));
    const t = svg('text', { x: pad.l - 6, y: y(v) + 4, 'text-anchor': 'end' });
    t.textContent = tickText(v, max);
    grid.append(t);
  }
  root.append(grid);
  // Etiquetas del eje X: unas 6, sin superponerse.
  const every = Math.max(1, Math.ceil(n / Math.max(2, Math.floor((W - pad.l) / 70))));
  for (let i = 0; i < n; i += every) {
    const t = svg('text', { x: x(i), y: H - 6, 'text-anchor': n === 1 ? 'middle' : i === 0 ? 'start' : 'middle' });
    t.textContent = data.tickLabels[i];
    root.append(t);
  }
  root.append(svg('line', { class: 'chart-axis', x1: pad.l, x2: W - pad.r, y1: y(0), y2: y(0) }));

  for (const s of data.series) {
    const d = s.values.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join('');
    root.append(svg('path', { d, fill: 'none', stroke: s.color, 'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));
    const last = n - 1;
    root.append(svg('circle', { cx: x(last), cy: y(s.values[last]), r: 4, fill: s.color, stroke: '#fff', 'stroke-width': 2 }));
  }

  const cross = svg('line', { y1: pad.t, y2: H - pad.b, stroke: '#4C5A72', 'stroke-width': 1, visibility: 'hidden' });
  const dots = data.series.map((s) => svg('circle', { r: 4, fill: s.color, stroke: '#fff', 'stroke-width': 2, visibility: 'hidden' }));
  root.append(cross, ...dots);
  const hit = svg('rect', { class: 'chart-hit', x: pad.l - 8, y: 0, width: W - pad.l - pad.r + 16, height: H, tabindex: 0,
    'aria-label': `${data.ariaLabel}. Usá las flechas para recorrer los valores.` });
  root.append(hit);
  box.prepend(root);

  const tip = tooltipEl(box);
  let index = n - 1;
  const show = (i) => {
    index = Math.min(Math.max(i, 0), n - 1);
    const px = x(index);
    cross.setAttribute('x1', px); cross.setAttribute('x2', px); cross.setAttribute('visibility', 'visible');
    data.series.forEach((s, k) => {
      dots[k].setAttribute('cx', px); dots[k].setAttribute('cy', y(s.values[index])); dots[k].setAttribute('visibility', 'visible');
    });
    fillTooltip(tip, data.labels[index], data.series.map((s) => ({ color: s.color, name: s.name, value: full.format(s.values[index]) })));
    const scale = box.clientWidth / W;
    placeTooltip(box, tip, px * scale, y(Math.max(...data.series.map((s) => s.values[index]))) * scale);
  };
  const hide = () => {
    tip.hidden = true;
    cross.setAttribute('visibility', 'hidden');
    dots.forEach((d) => d.setAttribute('visibility', 'hidden'));
  };
  hit.addEventListener('pointermove', (e) => {
    const rect = root.getBoundingClientRect();
    const px = ((e.clientX - rect.left) / rect.width) * W;
    show(n === 1 ? 0 : Math.round(((px - pad.l) / (W - pad.l - pad.r)) * (n - 1)));
  });
  hit.addEventListener('pointerleave', hide);
  hit.addEventListener('focus', () => show(index));
  hit.addEventListener('blur', hide);
  hit.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
      e.preventDefault();
      show(index + (e.key === 'ArrowRight' ? 1 : -1));
    }
  });
}

/**
 * Columnas de una serie (p. ej. histograma). Cada columna tiene su tooltip (hover y foco).
 * @param {{bars: {label: string, short: string, value: number}[], color: string, ariaLabel: string, unit: string}} data
 */
export function columnChart(box, data) {
  box.querySelector('svg')?.remove();
  const W = Math.max(box.clientWidth, 280);
  const H = 200;
  const pad = { l: 32, r: 8, t: 12, b: 26 };
  const n = data.bars.length;
  const { max, step } = niceScale(Math.max(0, ...data.bars.map((b) => b.value)));
  const band = (W - pad.l - pad.r) / n;
  const bw = Math.min(24, band - 4);
  const y = (v) => pad.t + (H - pad.t - pad.b) * (1 - v / max);

  const root = svg('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': data.ariaLabel });
  const grid = svg('g', { class: 'chart-grid' });
  for (let v = 0; v <= max + 1e-9; v += step) {
    grid.append(svg('line', { x1: pad.l, x2: W - pad.r, y1: y(v), y2: y(v) }));
    const t = svg('text', { x: pad.l - 6, y: y(v) + 4, 'text-anchor': 'end' });
    t.textContent = tickText(v, max);
    grid.append(t);
  }
  root.append(grid);
  const tip = tooltipEl(box);
  const every = Math.max(1, Math.ceil(n / Math.max(2, Math.floor((W - pad.l) / 64))));

  data.bars.forEach((b, i) => {
    const cx = pad.l + band * i + band / 2;
    const top = y(b.value);
    const base = y(0);
    const r = Math.min(4, (base - top) / 2, bw / 2);
    const x0 = cx - bw / 2;
    const x1 = cx + bw / 2;
    const hitRect = svg('rect', { class: 'chart-bar-hit', x: pad.l + band * i, y: pad.t, width: band, height: base - pad.t, tabindex: 0,
      'aria-label': `${b.label}: ${full.format(b.value)} ${data.unit}` });
    const bar = svg('path', {
      class: 'chart-bar', fill: data.color,
      d: b.value > 0 ? `M${x0},${base}V${top + r}Q${x0},${top} ${x0 + r},${top}H${x1 - r}Q${x1},${top} ${x1},${top + r}V${base}Z` : '',
    });
    const show = () => {
      bar.classList.add('is-active');
      fillTooltip(tip, b.label, [{ color: data.color, name: data.unit, value: full.format(b.value) }]);
      const scale = box.clientWidth / W;
      placeTooltip(box, tip, cx * scale, Math.min(top, base - 4) * scale);
    };
    const hide = () => { bar.classList.remove('is-active'); tip.hidden = true; };
    hitRect.addEventListener('pointerenter', show);
    hitRect.addEventListener('pointerleave', hide);
    hitRect.addEventListener('focus', show);
    hitRect.addEventListener('blur', hide);
    root.append(hitRect, bar);
    if (i % every === 0) {
      const t = svg('text', { x: cx, y: H - 6, 'text-anchor': 'middle' });
      t.textContent = b.short;
      root.append(t);
    }
  });
  root.append(svg('line', { class: 'chart-axis', x1: pad.l, x2: W - pad.r, y1: y(0), y2: y(0) }));
  box.prepend(root);
}
