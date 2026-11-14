// Landing de Climind Cotiza: demo en vivo y precios.
// La demo usa un negocio FICTICIO con precios de ejemplo. El importe lo calcula el SERVIDOR con el mismo motor y
// la misma versión publicada que la página pública (/api/v1/public/demos/{demo}/estimate, sin guardar nada).
import { loadPlans, renderPlanCards } from './pricing.js';
import { renderQuoteForm, renderTicket, visibleAnswers, visibleQuestions } from './quote-form.js';
import './site.js';

const $ = (id) => document.getElementById(id);

/** Respuestas iniciales de cada demo (para que el ticket arranque con un ejemplo completo). */
const STARTING_ANSWERS = {
  limpieza: { tipo: 'profunda', m2: '60', ambientes: '3', frecuencia: 'unica', extras: ['horno'] },
  catering: { invitados: '40', menu: 'clasico', bebidas: true, mozos: true, duracion: '4', extras: [] },
  instalaciones: { tipo: 'aire_split', cantidad: '2', materiales: true, urgente: false, extras: [] },
};

async function getJson(method, url, body) {
  const response = await fetch(url, {
    method,
    headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

// ------------------------------------------------------------------ demo en vivo

const demo = {
  list: [],
  current: null,     // { slug, path, calc }
  answers: {},
  timer: null,
  request: 0,
};

function selectTab(slug, focus = false) {
  $('demo-tabs').querySelectorAll('[role="tab"]').forEach((tab) => {
    const selected = tab.dataset.slug === slug;
    tab.setAttribute('aria-selected', String(selected));
    tab.tabIndex = selected ? 0 : -1;
    if (selected && focus) tab.focus();
  });
}

async function openDemo(slug) {
  const item = demo.list.find((d) => d.slug === slug);
  if (!item) return;
  selectTab(slug);
  const ticket = $('demo-ticket');
  ticket.classList.add('is-loading');
  try {
    const calc = await getJson('GET', `/api/v1/public${item.path}`);
    demo.current = { ...item, calc };
    // Solo las preguntas que influyen en el precio: el texto libre queda para la demo completa.
    const config = { questions: (calc.questions || []).filter((q) => q.type !== 'TEXT') };
    demo.current.config = config;
    demo.answers = { ...(STARTING_ANSWERS[slug] || {}) };
    $('demo-name').textContent = calc.name;
    $('demo-desc').textContent = calc.description || '';
    $('demo-open').href = item.path;
    lastVisible = visibleQuestions(config, demo.answers).join('|');
    renderQuoteForm($('demo-questions'), config, demo.answers, onAnswer);
    await estimate();
  } catch {
    ticket.classList.remove('is-loading');
    $('demo-questions').replaceChildren();
    const p = document.createElement('p');
    p.className = 'demo-error';
    p.textContent = 'No pudimos cargar la demo. Probá de nuevo en unos minutos.';
    $('demo-questions').append(p);
  }
}

let lastVisible = '';
function onAnswer() {
  // Se vuelve a pintar el formulario solo si cambió qué preguntas se ven (así no se pierde el foco al escribir).
  const { config } = demo.current;
  const visible = visibleQuestions(config, demo.answers).join('|');
  if (visible !== lastVisible) {
    lastVisible = visible;
    renderQuoteForm($('demo-questions'), config, demo.answers, onAnswer);
  }
  clearTimeout(demo.timer);
  $('demo-ticket').classList.add('is-loading');
  demo.timer = setTimeout(estimate, 250);
}

async function estimate() {
  const { calc, slug, config } = demo.current;
  const id = ++demo.request;
  const ticket = $('demo-ticket');
  try {
    const result = await getJson('POST', `/api/v1/public/demos/${slug}/estimate`,
      { answers: visibleAnswers(config, demo.answers) });
    if (id !== demo.request) return; // llegó una respuesta más nueva
    renderTicket(ticket, result, {
      businessName: calc.business.name,
      title: 'Estimación',
      disclaimer: calc.disclaimer,
      validityDays: calc.validityDays,
      scale: calc.priceScale,
    });
  } catch {
    if (id !== demo.request) return;
    renderTicket(ticket, null, { businessName: calc.business.name, title: 'Estimación' });
    ticket.querySelector('.ticket-empty span').textContent = 'Revisá las respuestas: algún valor está fuera de rango.';
  } finally {
    if (id === demo.request) ticket.classList.remove('is-loading');
  }
}

function setupTabs() {
  const tabs = $('demo-tabs');
  tabs.replaceChildren(...demo.list.map((item) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.setAttribute('role', 'tab');
    button.setAttribute('aria-controls', 'demo-stage');
    button.id = `demo-tab-${item.slug}`;
    button.dataset.slug = item.slug;
    button.textContent = item.industry || item.name;
    button.addEventListener('click', () => openDemo(item.slug));
    return button;
  }));
  tabs.addEventListener('keydown', (event) => {
    if (!['ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) return;
    const slugs = demo.list.map((d) => d.slug);
    const index = slugs.indexOf(demo.current?.slug);
    let next = index;
    if (event.key === 'ArrowRight') next = (index + 1) % slugs.length;
    if (event.key === 'ArrowLeft') next = (index - 1 + slugs.length) % slugs.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = slugs.length - 1;
    event.preventDefault();
    selectTab(slugs[next], true);
    openDemo(slugs[next]);
  });
}

async function initDemos() {
  try {
    demo.list = await getJson('GET', '/api/v1/public/demos');
  } catch {
    demo.list = [];
  }
  if (!demo.list.length) return; // demos deshabilitadas: la sección y sus enlaces no se muestran
  document.querySelectorAll('[data-needs-demos]').forEach((el) => { el.hidden = false; });
  setupTabs();
  $('demo-stage').setAttribute('aria-labelledby', `demo-tab-${demo.list[0].slug}`);
  $('demo-form').addEventListener('submit', (e) => e.preventDefault());
  await openDemo(demo.list[0].slug);
}

// ------------------------------------------------------------------ precios

async function initPricing() {
  const grid = $('pricing-grid');
  try {
    const plans = await loadPlans();
    renderPlanCards(grid, plans);
  } catch {
    const p = document.createElement('p');
    p.className = 'pricing-error';
    p.textContent = 'No pudimos cargar los planes en este momento. Podés empezar gratis y ver los precios desde tu panel.';
    grid.replaceChildren(p);
  }
}

initDemos();
initPricing();
