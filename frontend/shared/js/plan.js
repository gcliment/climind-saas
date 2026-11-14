// Plan del negocio: plan vigente, consumo, contratación (checkout de Mercado Pago), cancelación y pagos.
// El plan pago se habilita SOLO cuando el servidor recibe la confirmación del pago: volver del checkout no
// activa nada, por eso al regresar esta página consulta el estado hasta que se confirme.
import { api } from './api.js';
import { formatDate, formatMoney, formatNumber, formatLocalDate } from './format.js';
import { initPanel } from './panel.js';
import { hideNotice, showNotice, withBusy } from './ui.js';

await initPanel({ current: '/app/plan' });

const $ = (id) => document.getElementById(id);
const notice = $('plan-notice');
const params = new URLSearchParams(window.location.search);
let overview = null;

const STATUS_TEXT = {
  ACTIVE: (s) => `Activo. Se renueva el ${formatDate(s.currentPeriodEnd)}.`,
  PAST_DUE: (s) => `No pudimos cobrar la renovación. Mercado Pago la reintenta; conservás los beneficios hasta el ${formatDate(s.graceUntil)}.`,
  PAUSED: () => 'Pausado en Mercado Pago: mientras tanto rige el plan Gratis.',
  CANCELED: (s) => (new Date(s.currentPeriodEnd) > new Date()
    ? `Cancelado. Conservás los beneficios hasta el ${formatDate(s.currentPeriodEnd)}; después pasás al plan Gratis.`
    : 'Cancelado.'),
  EXPIRED: () => 'Venció: rige el plan Gratis.',
};

function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else if (k === 'on') Object.entries(v).forEach(([ev, fn]) => node.addEventListener(ev, fn));
    else node.setAttribute(k, v === true ? '' : String(v));
  }
  node.append(...children.flat().filter((c) => c !== null && c !== undefined && c !== false));
  return node;
}

function price(plan) {
  return plan.free ? 'Sin costo' : `${formatMoney(plan.price, { decimals: 0 })} por mes`;
}

function meter(id, used, limit) {
  const el = $(id);
  const ratio = limit ? Math.min(used / limit, 1) : 0;
  el.setAttribute('aria-valuenow', String(used));
  el.setAttribute('aria-valuemax', String(limit));
  el.classList.toggle('is-warn', ratio >= 0.8 && ratio < 1);
  el.classList.toggle('is-full', ratio >= 1);
  el.firstElementChild.style.width = `${Math.max(ratio * 100, used > 0 ? 2 : 0)}%`;
}

function renderCurrent(o) {
  const plan = o.current;
  $('current-title').textContent = `Plan ${plan.name}`;
  $('current-price').textContent = price(plan);
  const sub = o.subscription;
  $('current-status').textContent = o.paid && sub && STATUS_TEXT[sub.status] ? STATUS_TEXT[sub.status](sub)
    : 'Sin costo. Podés pasarte a un plan pago cuando lo necesites.';

  const u = o.usage;
  $('quotes-text').textContent = `${formatNumber(u.quotesUsed)} de ${formatNumber(u.quotesLimit)}`;
  meter('quotes-meter', u.quotesUsed, u.quotesLimit);
  const end = new Date(`${u.periodEnd}T12:00:00Z`);
  end.setUTCDate(end.getUTCDate() - 1);
  $('quotes-caption').textContent = u.quotesUsed >= u.quotesLimit
    ? `Llegaste al límite: tus cotizadores muestran tus datos de contacto hasta el ${formatLocalDate(u.periodEnd)}.`
    : `Período del ${formatLocalDate(u.periodStart)} al ${formatLocalDate(end.toISOString().slice(0, 10))}. Recalcular las mismas respuestas no suma.`;
  $('calcs-text').textContent = `${formatNumber(u.publishedCalculators)} de ${formatNumber(u.publishedLimit)}`;
  meter('calcs-meter', u.publishedCalculators, u.publishedLimit);
  $('calcs-caption').textContent = u.publishedCalculators > u.publishedLimit
    ? 'Tenés más cotizadores publicados que los que permite tu plan: solo los más antiguos están disponibles. Pausá los que no uses.'
    : 'Los borradores y los pausados no cuentan.';

  const actions = [];
  if (o.paid && sub && ['ACTIVE', 'PAST_DUE', 'PAUSED'].includes(sub.status)) {
    actions.push(h('button', { type: 'button', class: 'btn btn-link text-danger p-0', text: 'Cancelar el plan', on: { click: () => confirmCancel(sub) } }));
  }
  $('current-actions').replaceChildren(...actions);
  $('current-block').hidden = false;
}

function renderPending(o) {
  const p = o.pendingCheckout;
  $('pending-block').hidden = !p;
  if (!p) return;
  $('pending-text').textContent = `Empezaste a contratar el plan ${p.planName} (${formatMoney(p.price, { decimals: 0 })} por mes). Se activa cuando Mercado Pago confirme el pago.`;
  const link = $('pending-continue');
  link.hidden = !p.checkoutUrl;
  if (p.checkoutUrl) link.href = p.checkoutUrl;
}

function feature(text, included = true) {
  return h('li', { class: included ? '' : 'is-off' }, h('span', { class: 'visually-hidden', text: included ? 'Incluye: ' : 'No incluye: ' }), text);
}

function renderPlans(o) {
  const currentCode = o.current.code;
  const canCancel = o.paid && o.subscription && ['ACTIVE', 'PAST_DUE', 'PAUSED'].includes(o.subscription.status);
  $('plan-grid').replaceChildren(...o.plans.map((plan) => {
    const isCurrent = plan.code === currentCode;
    let action;
    if (isCurrent) {
      action = h('button', { type: 'button', class: 'btn btn-quiet w-100', disabled: true, text: 'Tu plan actual' });
    } else if (plan.free) {
      action = canCancel
        ? h('button', { type: 'button', class: 'btn btn-quiet w-100', text: 'Volver al plan Gratis', on: { click: () => confirmCancel(o.subscription) } })
        : h('button', { type: 'button', class: 'btn btn-quiet w-100', disabled: true, text: 'Incluido sin costo' });
    } else {
      action = h('button', {
        type: 'button', class: 'btn btn-primary w-100', disabled: !o.paymentsAvailable, text: `Pasar a ${plan.name}`,
        on: { click: (e) => startCheckout(plan, e.currentTarget) },
      });
    }
    return h('article', { class: `plan-card${isCurrent ? ' is-current' : ''}` },
      h('h3', { text: plan.name }),
      h('p', { class: 'plan-price', text: price(plan) }),
      plan.description ? h('p', { class: 'plan-desc', text: plan.description }) : null,
      h('ul', { class: 'plan-features' },
        feature(plan.maxPublishedCalculators === 1 ? '1 cotizador publicado' : `${formatNumber(plan.maxPublishedCalculators)} cotizadores publicados`),
        feature(`${formatNumber(plan.maxMonthlyQuotes)} cotizaciones por mes`),
        feature('Consultas, seguimiento y estadísticas'),
        feature('Exportación a CSV', plan.csvExport),
        feature('Color propio y sin la marca de Climind', plan.customBranding),
        feature('Aviso por email de cada consulta', plan.leadEmailNotifications)),
      action);
  }));
  if (!o.paymentsAvailable) {
    showNotice(notice, 'Los pagos todavía no están habilitados. Escribinos y te ayudamos a contratar.', 'warn');
  }
}

function renderPayments(o) {
  $('payments-block').hidden = o.payments.length === 0;
  const LABEL = { APPROVED: 'Aprobado', PENDING: 'En proceso', REJECTED: 'Rechazado', REFUNDED: 'Devuelto', CANCELLED: 'Cancelado' };
  $('payments-body').replaceChildren(...o.payments.map((p) => h('tr', {},
    h('td', { text: formatDate(p.date) }), h('td', { text: p.planName || '' }),
    h('td', { class: 'num', text: formatMoney(p.amount, { decimals: 0 }) }),
    h('td', {}, h('span', { class: `pay-status pay-${p.status}`, text: LABEL[p.status] || p.status })))));
}

function render(o) {
  overview = o;
  $('simulator-banner').hidden = !o.simulator;
  renderCurrent(o);
  renderPending(o);
  renderPlans(o);
  renderPayments(o);
}

async function load() {
  render(await api.get('/api/v1/billing'));
}

async function startCheckout(plan, button) {
  hideNotice(notice);
  await withBusy(button, async () => {
    try {
      const { checkoutUrl } = await api.post('/api/v1/billing/checkout', { planCode: plan.code });
      window.location.assign(checkoutUrl);
    } catch (error) {
      showNotice(notice, error.message);
      notice.focus();
    }
  });
}

function confirmCancel(sub) {
  const dialog = $('cancel-dialog');
  $('cancel-title').textContent = `Cancelar el plan ${sub.planName}`;
  $('cancel-text').textContent = `No se te va a volver a cobrar.${sub.currentPeriodEnd && new Date(sub.currentPeriodEnd) > new Date()
    ? ` Conservás los beneficios hasta el ${formatDate(sub.currentPeriodEnd)}.` : ''} Después tu cuenta pasa al plan Gratis: no se borra nada, pero si tenés más cotizadores publicados que los que permite, los que exceden quedan no disponibles.`;
  dialog.returnValue = '';
  dialog.showModal();
  dialog.addEventListener('close', async () => {
    if (dialog.returnValue !== 'ok') return;
    try {
      render(await api.post('/api/v1/billing/cancel'));
      showNotice(notice, 'Cancelaste el plan. Te mandamos un email con el detalle.', 'ok');
    } catch (error) {
      showNotice(notice, error.message);
    }
    notice.focus();
  }, { once: true });
}

$('pending-discard').addEventListener('click', (e) => withBusy(e.currentTarget, async () => {
  try {
    render(await api.post('/api/v1/billing/checkout/cancel'));
  } catch (error) {
    showNotice(notice, error.message);
  }
}));

/** Al volver del checkout: se espera la confirmación del pago (webhook) sin activar nada desde el navegador. */
async function waitForConfirmation() {
  const url = new URL(window.location.href);
  url.search = '';
  window.history.replaceState(null, '', url);
  if (!overview.pendingCheckout) return;
  showNotice(notice, 'Estamos esperando la confirmación del pago de Mercado Pago. Puede demorar unos minutos: esta página se actualiza sola.', 'warn');
  for (let i = 0; i < 36; i++) {
    await new Promise((r) => setTimeout(r, 5000));
    await load();
    const pending = overview.pendingCheckout;
    const last = overview.payments[0];
    if (pending && last && last.status === 'REJECTED' && new Date(last.date) >= new Date(pending.createdAt)) {
      showNotice(notice, 'Mercado Pago rechazó el pago. Lo va a reintentar; también podés continuar con otro medio de pago.', 'error');
      return;
    }
    if (!pending) {
      showNotice(notice, overview.paid ? `¡Listo! Tu plan ${overview.current.name} está activo.` : 'La contratación no se completó.', overview.paid ? 'ok' : 'warn');
      return;
    }
  }
  showNotice(notice, 'Todavía no recibimos la confirmación. Si ya pagaste, tu plan se va a activar apenas Mercado Pago la envíe; te avisamos por email.', 'warn');
}

try {
  await load();
  if (params.get('retorno')) waitForConfirmation();
} catch (error) {
  showNotice(notice, error.message);
}
