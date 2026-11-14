// Planes y precios desde la base (GET /api/v1/catalog/plans): la landing, la página de precios y el portal
// muestran siempre los valores vigentes que edita el administrador. Nada de precios escritos en el HTML.
import { formatMoney, formatNumber } from './format.js';

const PERIOD = { MONTHLY: 'por mes', YEARLY: 'por año' };

/** @param {string} [base] origen de la API ('' = mismo origen) */
export async function loadPlans(base = '') {
  const response = await fetch(`${base}/api/v1/catalog/plans`, { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

const plural = (n, one, many) => `${formatNumber(n)} ${n === 1 ? one : many}`;

/** Lo que incluye cada plan, en el mismo orden para todos (las ausencias se muestran tachadas). */
export function planFeatures(plan) {
  return [
    [true, `${plural(plan.maxPublishedCalculators, 'cotizador publicado', 'cotizadores publicados')}`],
    [true, `${plural(plan.maxMonthlyQuotes, 'cotización', 'cotizaciones')} por mes`],
    [true, 'Consultas de clientes, PDF y WhatsApp'],
    [plan.leadEmailNotifications, 'Aviso por email de cada consulta'],
    [plan.csvExport, 'Exportación de consultas a CSV'],
    [plan.customBranding, 'Tu color y sin la marca de Climind'],
  ];
}

export function priceParts(plan) {
  if (plan.free || Number(plan.price) === 0) return { amount: 'Sin costo', period: 'para siempre' };
  return { amount: formatMoney(plan.price, { decimals: 0 }), period: PERIOD[plan.billingPeriod] || '' };
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

/**
 * Tarjetas de planes.
 * @param {HTMLElement} container
 * @param {object[]} plans
 * @param {{href?: (plan: object) => string, cta?: (plan: object) => string}} [options]
 */
export function renderPlanCards(container, plans, options = {}) {
  const href = options.href || (() => '/registro');
  const cta = options.cta || ((plan) => (plan.free ? 'Empezar gratis' : `Empezar con ${plan.name}`));
  container.replaceChildren(...plans.map((plan) => {
    const card = el('article', 'price-card');
    card.dataset.plan = plan.code;
    const { amount, period } = priceParts(plan);
    const price = el('p', 'price');
    price.append(el('strong', '', amount), el('span', '', period));
    card.append(el('h3', '', plan.name), price);
    if (plan.description) card.append(el('p', 'price-desc', plan.description));
    const list = el('ul', 'price-features');
    for (const [on, text] of planFeatures(plan)) {
      const li = el('li', on ? '' : 'is-off', text);
      if (!on) li.prepend(el('span', 'visually-hidden', 'No incluye: '));
      list.append(li);
    }
    const link = el('a', `btn ${plan.free ? 'btn-primary' : 'btn-quiet'}`, cta(plan));
    link.href = href(plan);
    card.append(list, link);
    return card;
  }));
}

/** Tabla comparativa (una columna por plan). */
export function renderComparison(table, plans) {
  const yes = () => { const s = el('span', 'yes', '✓'); s.setAttribute('aria-label', 'Incluido'); return s; };
  const no = () => { const s = el('span', 'no', '—'); s.setAttribute('aria-label', 'No incluido'); return s; };
  const rows = [
    ['Precio', (p) => { const { amount, period } = priceParts(p); return `${amount}${p.free ? '' : ` ${period}`}`; }],
    ['Cotizadores publicados', (p) => formatNumber(p.maxPublishedCalculators)],
    ['Cotizaciones por mes', (p) => formatNumber(p.maxMonthlyQuotes)],
    ['Consultas de clientes', () => 'Todas las que lleguen'],
    ['Página pública para celular, PDF y WhatsApp', () => true],
    ['Estadísticas', () => true],
    ['Aviso por email de cada consulta', (p) => p.leadEmailNotifications],
    ['Exportación de consultas a CSV', (p) => p.csvExport],
    ['Tu color y sin la marca de Climind', (p) => p.customBranding],
  ];
  const caption = el('caption', '', 'Comparación de planes de Climind Cotiza');
  const head = el('thead');
  const headRow = el('tr');
  headRow.append(el('td'));
  plans.forEach((p) => { const th = el('th', '', p.name); th.scope = 'col'; headRow.append(th); });
  head.append(headRow);
  const body = el('tbody');
  for (const [label, value] of rows) {
    const tr = el('tr');
    const th = el('th', '', label);
    th.scope = 'row';
    tr.append(th);
    for (const plan of plans) {
      const v = value(plan);
      const td = el('td');
      if (v === true) td.append(yes());
      else if (v === false) td.append(no());
      else td.textContent = v;
      tr.append(td);
    }
    body.append(tr);
  }
  table.replaceChildren(caption, head, body);
}
