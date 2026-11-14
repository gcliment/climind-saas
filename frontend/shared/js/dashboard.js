// Inicio del panel: actividad del mes, consultas nuevas, puesta en marcha y cotizadores.
import { api } from './api.js';
import { formatDateTime, formatMoney, todayIso } from './format.js';
import { initPanel } from './panel.js';

const me = await initPanel({ current: '/app/' });
const $ = (id) => document.getElementById(id);
$('greeting').textContent = `Hola, ${me.user.firstName}`;
$('onboarding-banner').hidden = me.tenant.onboardingCompleted;

const STATUS = { BORRADOR: 'Borrador', PUBLICADO: 'Publicado', PAUSADO: 'Pausado', ARCHIVADO: 'Archivado' };
const number = new Intl.NumberFormat('es-AR');

function markDone(stepId, text, actionId, actionText) {
  const step = $(stepId);
  step.classList.remove('current');
  step.classList.add('done');
  $(`${stepId}-text`).textContent = text;
  const action = $(actionId);
  action.textContent = actionText;
  action.classList.replace('btn-primary', 'btn-quiet');
}

const today = todayIso();
const [profile, calcs, stats, counts, pending] = await Promise.all([
  api.get('/api/v1/business/profile').catch(() => null),
  api.get('/api/v1/calculators').catch(() => []),
  api.get(`/api/v1/stats/summary?from=${today.slice(0, 8)}01&to=${today}`).catch(() => null),
  api.get('/api/v1/leads/status-counts').catch(() => []),
  api.get('/api/v1/leads?status=NUEVA&size=3').catch(() => null),
]);
const billing = await api.get('/api/v1/billing').catch(() => null);

// Plan y consumo (con aviso al acercarse al límite).
if (billing) {
  const u = billing.usage;
  const ratio = u.quotesLimit ? u.quotesUsed / u.quotesLimit : 0;
  $('plan-name').textContent = billing.current.name;
  $('plan-quotes').textContent = `${number.format(u.quotesUsed)} de ${number.format(u.quotesLimit)}`;
  const meter = $('plan-meter');
  meter.setAttribute('aria-valuenow', String(u.quotesUsed));
  meter.setAttribute('aria-valuemax', String(u.quotesLimit));
  meter.classList.toggle('is-warn', ratio >= 0.8 && ratio < 1);
  meter.classList.toggle('is-full', ratio >= 1);
  meter.firstElementChild.style.width = `${Math.min(ratio, 1) * 100}%`;
  $('plan-text').textContent = ratio >= 1
    ? 'Llegaste al límite de cotizaciones: tus cotizadores muestran tus datos de contacto hasta el próximo período. Pasate a un plan con más capacidad para seguir recibiendo cotizaciones.'
    : ratio >= 0.8 ? 'Estás cerca del límite de cotizaciones de tu plan.'
      : billing.paid ? 'Tu plan pago está activo.' : 'Plan sin costo. Cuando necesites más capacidad, pasate a un plan pago.';
  $('plan-block').hidden = false;
}

// Paso 1: el negocio tiene nombre propio, rubro y WhatsApp.
const profileDone = profile && profile.industry && profile.whatsapp && !profile.businessName.startsWith('Negocio de ');
if (profileDone) {
  markDone('step-profile', `${profile.businessName}, ${profile.industryLabel.toLowerCase()}. Podés editarlo cuando quieras.`,
    'step-profile-action', 'Editar datos');
  $('step-calc').classList.add('current');
  $('step-calc-action').classList.replace('btn-quiet', 'btn-primary');
}

// Paso 2: al menos un cotizador publicado. Paso 3: compartirlo (cuando hay cotizaciones, ya se compartió).
const published = calcs.filter((c) => c.status === 'PUBLICADO');
if (published.length) {
  markDone('step-calc', `Tenés ${published.length === 1 ? 'un cotizador publicado' : `${published.length} cotizadores publicados`}.`,
    'step-calc-action', 'Ver cotizadores');
  $('step-calc-action').href = '/app/cotizadores';
  const shareUrl = `/app/editor?id=${published[0].id}&tab=publicacion`;
  $('step-share-action').href = shareUrl;
  $('quick-share').href = shareUrl;
  $('step-share').classList.add('current');
  $('step-share-action').classList.replace('btn-quiet', 'btn-primary');
} else if (calcs.length) {
  $('step-calc-text').textContent = 'Tenés un cotizador en borrador: revisá los precios y publicalo.';
  const action = $('step-calc-action');
  action.textContent = 'Seguir editando';
  action.href = `/app/editor?id=${calcs[0].id}`;
}

const everQuoted = stats && (stats.totals.simulations > 0 || counts.some((c) => c.count > 0));
if (published.length && everQuoted) {
  markDone('step-share', 'Tus clientes ya están cotizando con tu enlace.', 'step-share-action', 'Compartir de nuevo');
}
// Con todo listo, la puesta en marcha deja lugar a la actividad.
if (profileDone && published.length && everQuoted) $('setup-block').hidden = true;

// Actividad del mes.
if (stats && published.length) {
  const t = stats.totals;
  const pendingCount = counts.find((c) => c.status === 'NUEVA')?.count ?? 0;
  $('m-sims').textContent = number.format(t.simulations);
  $('m-leads').textContent = number.format(t.leads);
  $('m-pending').textContent = number.format(pendingCount);
  $('m-rate').textContent = t.conversionRate === null ? '—' : `${number.format(t.conversionRate)} %`;
  $('month-block').hidden = false;
}

// Consultas nuevas para responder.
if (pending && pending.items.length) {
  $('pending-list').replaceChildren(...pending.items.map((lead) => {
    const row = document.createElement('article');
    row.className = 'lead-row';
    const main = document.createElement('div');
    const title = document.createElement('h3');
    title.className = 'h6 m-0';
    const link = document.createElement('a');
    link.href = `/app/consultas?id=${lead.id}`;
    link.textContent = lead.customerName;
    title.append(link);
    const meta = document.createElement('p');
    meta.className = 'lead-meta';
    meta.textContent = `${lead.calculatorName} · ${formatDateTime(lead.createdAt)}`;
    main.append(title, meta);
    const total = document.createElement('strong');
    total.className = 'lead-total';
    total.textContent = formatMoney(lead.total, { decimals: 0 });
    row.append(main, total);
    return row;
  }));
  $('pending-block').hidden = false;
}

if (calcs.length) {
  $('calcs-block').hidden = false;
  $('calc-list').replaceChildren(...calcs.slice(0, 3).map((calc) => {
    const row = document.createElement('article');
    row.className = 'calc-row';
    const info = document.createElement('div');
    const title = document.createElement('h3');
    title.className = 'h6 m-0';
    const link = document.createElement('a');
    link.href = `/app/editor?id=${calc.id}`;
    link.textContent = calc.name;
    title.append(link);
    info.append(title);
    const badge = document.createElement('span');
    badge.className = `status-badge status-${calc.status}`;
    badge.textContent = STATUS[calc.status];
    row.append(info, badge);
    return row;
  }));
}
