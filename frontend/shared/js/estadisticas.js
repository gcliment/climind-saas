// Estadísticas del negocio: indicadores del período, actividad diaria/mensual, cotizadores y distribución de importes.
import { api } from './api.js';
import { columnChart, lineChart, SERIES, tableToggle } from './charts.js';
import { formatLocalDate, formatMoney, todayIso } from './format.js';
import { initPanel } from './panel.js';
import { hideNotice, showNotice } from './ui.js';

await initPanel({ current: '/app/estadisticas' });

const $ = (id) => document.getElementById(id);
const number = new Intl.NumberFormat('es-AR');
const monthFmt = new Intl.DateTimeFormat('es-AR', { month: 'short', year: '2-digit', timeZone: 'UTC' });
const dayShort = new Intl.DateTimeFormat('es-AR', { day: 'numeric', month: 'short', timeZone: 'UTC' });
let last = null;

function shiftDays(iso, days) {
  const d = new Date(`${iso}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

function range() {
  const today = todayIso();
  const period = $('period').value;
  if (period === 'custom') return { from: $('from').value, to: $('to').value };
  if (period === 'month') return { from: `${today.slice(0, 8)}01`, to: today };
  if (period === '365') return { from: shiftDays(today, -364), to: today };
  return { from: shiftDays(today, -(Number(period) - 1)), to: today };
}

function setKpi(id, value, hint) {
  const dd = $(id);
  const small = document.createElement('small');
  small.textContent = hint;
  dd.replaceChildren(document.createTextNode(value), small);
}

function renderKpis(s) {
  const t = s.totals;
  setKpi('k-sims', number.format(t.simulations), 'Precios calculados por clientes');
  setKpi('k-leads', number.format(t.leads), 'Dejaron sus datos');
  setKpi('k-rate', t.conversionRate === null ? '—' : `${number.format(t.conversionRate)} %`,
    t.conversionRate === null ? 'Sin cotizaciones en el período' : `${number.format(t.simulationsWithLead)} de ${number.format(t.simulations)} cotizaciones`);
  setKpi('k-sold', number.format(t.converted), 'Consultas marcadas como convertidas');
  setKpi('k-wa', number.format(t.whatsappClicks), 'Desde la cotización');
  setKpi('k-median', t.medianTotal === null ? '—' : formatMoney(t.medianTotal, { decimals: 0 }),
    t.averageTotal === null ? 'Sin cotizaciones en el período' : `Promedio: ${formatMoney(t.averageTotal, { decimals: 0 })}`);
}

function renderActivity(s) {
  const daily = s.granularity === 'DAY';
  $('activity-sub').textContent = daily ? 'Cotizaciones y consultas por día' : 'Cotizaciones y consultas por mes';
  const series = [
    { name: 'Cotizaciones', values: s.series.map((p) => p.simulations), color: SERIES[0] },
    { name: 'Consultas', values: s.series.map((p) => p.leads), color: SERIES[1] },
  ];
  $('activity-legend').replaceChildren(...series.map((x) => {
    const li = document.createElement('li');
    const key = document.createElement('span');
    key.className = 'key-line';
    key.style.background = x.color;
    li.append(key, x.name);
    return li;
  }));
  const labels = s.series.map((p) => (daily ? formatLocalDate(p.date) : monthFmt.format(new Date(`${p.date}T12:00:00Z`))));
  const tickLabels = s.series.map((p) => (daily ? dayShort.format(new Date(`${p.date}T12:00:00Z`)) : monthFmt.format(new Date(`${p.date}T12:00:00Z`))));
  lineChart($('activity-chart'), { labels, tickLabels, series, ariaLabel: `Cotizaciones y consultas ${daily ? 'por día' : 'por mes'}` });
  $('activity-table').replaceChildren();
  tableToggle($('activity-table'), [daily ? 'Día' : 'Mes', 'Cotizaciones', 'Consultas'],
    s.series.map((p, i) => [labels[i], number.format(p.simulations), number.format(p.leads)]));
}

function renderCalculators(s) {
  const box = $('calc-perf');
  if (!s.calculators.length) {
    box.replaceChildren(Object.assign(document.createElement('p'), { className: 'chart-empty', textContent: 'Sin cotizaciones en el período.' }));
    return;
  }
  const max = Math.max(...s.calculators.map((c) => c.simulations));
  const table = document.createElement('table');
  table.className = 'calc-perf';
  const head = table.createTHead().insertRow();
  [['Cotizador', ''], ['', 'bar-col'], ['Cotizaciones', 'num'], ['Conversión', 'num']].forEach(([text, cls]) => {
    const th = document.createElement('th');
    th.textContent = text;
    th.className = cls;
    th.scope = 'col';
    head.append(th);
  });
  const body = table.createTBody();
  for (const c of s.calculators) {
    const row = body.insertRow();
    const name = row.insertCell();
    name.className = 'name';
    name.textContent = c.name;
    const barCell = row.insertCell();
    barCell.className = 'bar-col perf-track';
    barCell.setAttribute('aria-hidden', 'true');
    const bar = document.createElement('div');
    bar.className = 'perf-bar';
    bar.style.width = `${(c.simulations / max) * 100}%`;
    barCell.append(bar);
    const sims = row.insertCell();
    sims.className = 'num';
    sims.textContent = number.format(c.simulations);
    const rate = row.insertCell();
    rate.className = 'num';
    rate.textContent = c.conversionRate === null ? '—' : `${number.format(c.conversionRate)} %`;
  }
  box.replaceChildren(table);
}

function renderDistribution(s) {
  const box = $('dist-chart');
  $('dist-table').replaceChildren();
  box.replaceChildren();
  if (!s.distribution.length) {
    box.append(Object.assign(document.createElement('p'), { className: 'chart-empty', textContent: 'Sin cotizaciones en el período.' }));
    return;
  }
  const money = (v) => formatMoney(v, { decimals: 0 });
  const short = new Intl.NumberFormat('es-AR', { notation: 'compact', maximumFractionDigits: 1 });
  const bars = s.distribution.map((b) => ({
    label: `De ${money(b.from)} a ${money(b.to)}`, short: `$${short.format(Number(b.from))}`, value: b.count,
  }));
  columnChart(box, { bars, color: SERIES[0], unit: 'cotizaciones', ariaLabel: 'Cantidad de cotizaciones por rango de importe' });
  tableToggle($('dist-table'), ['Rango', 'Cotizaciones'], bars.map((b) => [b.label, number.format(b.value)]));
}

async function load() {
  const { from, to } = range();
  if (!from || !to) return;
  hideNotice($('stats-notice'));
  document.querySelectorAll('.kpis, .chart-card').forEach((el) => el.classList.add('is-loading'));
  try {
    last = await api.get(`/api/v1/stats/summary?from=${from}&to=${to}`);
    render();
  } catch (error) {
    showNotice($('stats-notice'), error.fieldErrors?.[0]?.message || error.message);
  } finally {
    document.querySelectorAll('.kpis, .chart-card').forEach((el) => el.classList.remove('is-loading'));
  }
}

function render() {
  if (!last) return;
  renderKpis(last);
  renderActivity(last);
  renderCalculators(last);
  renderDistribution(last);
}

$('period').addEventListener('change', () => {
  const custom = $('period').value === 'custom';
  $('custom-range').hidden = !custom;
  if (custom) {
    $('to').value ||= todayIso();
    $('from').value ||= shiftDays(todayIso(), -29);
    $('to').max = todayIso();
    $('from').focus();
  } else {
    load();
  }
});
$('custom-range').addEventListener('submit', (e) => { e.preventDefault(); load(); });

let resizeTimer;
window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(render, 150); });

load();
