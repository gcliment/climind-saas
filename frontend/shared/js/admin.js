// Panel SUPER_ADMIN (Climind-IA): métricas, negocios, suscripciones, pagos, planes e integración de pagos.
import { api } from './api.js';
import { formatDate, formatDateTime, formatMoney, formatNumber } from './format.js';
import { initPanel } from './panel.js';
import { hideNotice, showNotice, withBusy } from './ui.js';

const me = await initPanel({ current: '/app/admin', requireTenant: false });
const $ = (id) => document.getElementById(id);
const notice = $('admin-notice');

function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else if (k === 'on') Object.entries(v).forEach(([ev, fn]) => node.addEventListener(ev, fn));
    else if (k === 'checked') node.checked = Boolean(v);
    else if (k === 'value') node.value = v;
    else node.setAttribute(k, v === true ? '' : String(v));
  }
  node.append(...children.flat().filter((c) => c !== null && c !== undefined && c !== false));
  return node;
}

const money = (v) => formatMoney(v ?? 0, { decimals: 0 });
const date = (v) => (v ? formatDate(v) : '—');
const pill = (state, text) => h('span', { class: `state-pill state-${state}`, text: text || state });

function stats(container, rows) {
  container.replaceChildren(...rows.map(([label, value]) => h('div', { class: 'stat' }, h('dt', { text: label }), h('dd', { text: value }))));
}

// ------------------------------------------------------------------ pestañas

const TABS = ['resumen', 'negocios', 'suscripciones', 'pagos', 'planes', 'integracion'];
const loaders = {};
function selectTab(name) {
  for (const t of TABS) {
    const selected = t === name;
    $(`tab-${t}`).setAttribute('aria-selected', String(selected));
    $(`tab-${t}`).tabIndex = selected ? 0 : -1;
    $(`panel-${t}`).hidden = !selected;
  }
  hideNotice(notice);
  loaders[name]?.().catch((error) => showNotice(notice, error.message));
}
TABS.forEach((t, i) => {
  const tab = $(`tab-${t}`);
  tab.addEventListener('click', () => selectTab(t));
  tab.addEventListener('keydown', (e) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
    const next = TABS[(i + (e.key === 'ArrowRight' ? 1 : TABS.length - 1)) % TABS.length];
    selectTab(next);
    $(`tab-${next}`).focus();
  });
});

// ------------------------------------------------------------------ resumen

loaders.resumen = async () => {
  const s = await api.get('/api/v1/admin/summary');
  const r = s.revenue;
  stats($('revenue-stats'), [
    ['MRR estimado', money(r.estimatedMrr)],
    ['Cobrado en los últimos 30 días', money(r.collectedLast30Days)],
    ['Suscriptores pagos', formatNumber(r.paidSubscribers)],
    ['Con cobro rechazado (en gracia)', formatNumber(r.pastDue)],
    ['Pagos rechazados en 30 días', formatNumber(r.failedPaymentsLast30Days)],
    ['Cancelaciones en 30 días', formatNumber(r.canceledLast30Days)],
  ]);
  stats($('stats'), [
    ['Negocios registrados', formatNumber(s.totalTenants)],
    ['Negocios activos (con cotizador publicado)', formatNumber(s.activeTenants)],
    ['Negocios en plan Gratis', formatNumber(s.freeTenants)],
    ['Negocios suspendidos', formatNumber(s.suspendedTenants)],
    ['Altas en los últimos 30 días', formatNumber(s.newTenantsLast30Days)],
    ['Usuarios con email confirmado', `${formatNumber(s.verifiedUsers)} de ${formatNumber(s.totalUsers)}`],
    ['Cotizaciones este mes (Climind Cotiza)', formatNumber(s.quotesThisMonth)],
    ['Consultas este mes', formatNumber(s.leadsThisMonth)],
  ]);
};

// ------------------------------------------------------------------ negocios

async function suspend(tenant) {
  const reason = window.prompt(`Motivo de la suspensión de ${tenant.businessName} (queda registrado):`);
  if (reason === null) return;
  try {
    await api.post(`/api/v1/admin/tenants/${tenant.id}/suspend`, { reason });
    showNotice(notice, `Suspendiste ${tenant.businessName}.`, 'ok');
    await loaders.negocios();
  } catch (error) { showNotice(notice, error.fieldErrors?.[0]?.message || error.message); }
}

async function reactivate(tenant) {
  try {
    await api.post(`/api/v1/admin/tenants/${tenant.id}/reactivate`);
    showNotice(notice, `Reactivaste ${tenant.businessName}.`, 'ok');
    await loaders.negocios();
  } catch (error) { showNotice(notice, error.message); }
}

const TENANT_LABEL = { ACTIVE: 'Activo', SUSPENDED: 'Suspendido', DELETED: 'Eliminado' };
loaders.negocios = async () => {
  const q = new URLSearchParams({ q: $('tenant-q').value.trim(), status: $('tenant-status').value, size: '50' });
  const page = await api.get(`/api/v1/admin/tenants?${q}`);
  $('tenant-rows').replaceChildren(...page.items.map((t) => h('tr', {},
    h('td', {}, t.businessName, h('small', { text: `${t.ownerEmail || ''} · /c/${t.slug}` })),
    h('td', { text: date(t.createdAt) }),
    h('td', {}, t.planName, t.paid ? null : h('small', { text: 'sin costo' })),
    h('td', { class: 'num', text: `${formatNumber(t.quotesUsed)} / ${formatNumber(t.quotesLimit)}` }),
    h('td', { class: 'num', text: `${formatNumber(t.publishedCalculators)} / ${formatNumber(t.publishedLimit)}` }),
    h('td', {}, pill(t.status, TENANT_LABEL[t.status] || t.status), t.suspendedReason ? h('small', { text: t.suspendedReason }) : null,
      t.deletionScheduledFor ? h('small', { text: `Baja pedida: se elimina el ${date(t.deletionScheduledFor)}` }) : null),
    h('td', {}, t.status === 'ACTIVE'
      ? h('button', { type: 'button', class: 'btn btn-link text-danger p-0', text: 'Suspender', on: { click: () => suspend(t) } })
      : t.status === 'SUSPENDED'
        ? h('button', { type: 'button', class: 'btn btn-link p-0', text: 'Reactivar', on: { click: () => reactivate(t) } })
        : null))));
  $('tenant-count').textContent = page.totalItems > page.items.length
    ? `Mostrando ${page.items.length} de ${page.totalItems}. Refiná la búsqueda para ver otros.` : `${formatNumber(page.totalItems)} negocios.`;
};
$('tenant-filters').addEventListener('submit', (e) => { e.preventDefault(); loaders.negocios().catch((err) => showNotice(notice, err.message)); });

// ------------------------------------------------------------------ suscripciones y pagos

const SUB_LABEL = { ACTIVE: 'Activa', PAST_DUE: 'Cobro rechazado', PENDING: 'Pendiente', PAUSED: 'Pausada', CANCELED: 'Cancelada', EXPIRED: 'Vencida', TRIALING: 'Prueba' };
loaders.suscripciones = async () => {
  const page = await api.get(`/api/v1/admin/subscriptions?status=${$('sub-status').value}&size=100`);
  $('sub-rows').replaceChildren(...page.items.map((s) => h('tr', {},
    h('td', { text: s.businessName || '—' }),
    h('td', {}, s.planName, h('small', { text: `${money(s.price)} por mes` })),
    h('td', {}, pill(s.status, SUB_LABEL[s.status]), s.status === 'PAST_DUE' ? h('small', { text: `Gracia hasta ${date(s.graceUntil)}` }) : null),
    h('td', { text: date(s.currentPeriodEnd) }),
    h('td', { text: date(s.lastPaymentAt) }),
    h('td', {}, s.provider, h('small', { text: `${s.externalId || 'sin id'} · ${s.providerStatus || '—'}` })))));
};
$('sub-status').addEventListener('change', () => loaders.suscripciones().catch((e) => showNotice(notice, e.message)));

const PAY_LABEL = { APPROVED: 'Aprobado', REJECTED: 'Rechazado', PENDING: 'En proceso', REFUNDED: 'Devuelto', CANCELLED: 'Cancelado' };
loaders.pagos = async () => {
  const page = await api.get(`/api/v1/admin/payments?status=${$('pay-status').value}&size=100`);
  $('pay-rows').replaceChildren(...page.items.map((p) => h('tr', {},
    h('td', { text: formatDateTime(p.paidAt || p.chargeDate || p.createdAt) }),
    h('td', { text: p.businessName || '—' }),
    h('td', { class: 'num', text: money(p.amount) }),
    h('td', {}, pill(p.status, PAY_LABEL[p.status])),
    h('td', {}, h('code', { text: p.externalId })))));
};
$('pay-status').addEventListener('change', () => loaders.pagos().catch((e) => showNotice(notice, e.message)));

// ------------------------------------------------------------------ planes

let uid = 0;
function input(label, attrs, help) {
  const id = `pf-${++uid}`;
  const control = h('input', { class: attrs.type === 'checkbox' ? 'form-check-input' : 'form-control', id, ...attrs });
  if (attrs.type === 'checkbox') return h('div', { class: 'form-check' }, control, h('label', { class: 'form-check-label', for: id, text: label }));
  return h('div', {}, h('label', { class: 'form-label', for: id, text: label }), control, help ? h('div', { class: 'form-text', text: help }) : null);
}

function planForm(plan) {
  const form = h('form', { class: 'plan-form panel-block', novalidate: true });
  const fields = {
    code: plan ? null : input('Código', { name: 'code', maxlength: 30, required: true, placeholder: 'AGENCIA' }),
    name: input('Nombre', { name: 'name', maxlength: 60, value: plan?.name ?? '', required: true }),
    price: input('Precio mensual (ARS)', { name: 'price', type: 'number', min: 0, step: '0.01', value: plan?.price ?? '', disabled: plan?.free }),
    maxPublishedCalculators: input('Cotizadores publicados', { name: 'maxPublishedCalculators', type: 'number', min: 1, max: 1000, value: plan?.maxPublishedCalculators ?? 1 }),
    maxMonthlyQuotes: input('Cotizaciones por mes', { name: 'maxMonthlyQuotes', type: 'number', min: 1, value: plan?.maxMonthlyQuotes ?? 100 }),
    sortOrder: input('Orden', { name: 'sortOrder', type: 'number', min: 0, value: plan?.sortOrder ?? 9 }),
    description: input('Descripción', { name: 'description', maxlength: 300, value: plan?.description ?? '' }),
    csvExport: input('Exportación CSV', { name: 'csvExport', type: 'checkbox', checked: plan?.csvExport }),
    customBranding: input('Color propio y sin marca de Climind', { name: 'customBranding', type: 'checkbox', checked: plan?.customBranding }),
    leadEmailNotifications: input('Avisos por email de consultas', { name: 'leadEmailNotifications', type: 'checkbox', checked: plan?.leadEmailNotifications ?? true }),
    activeForNew: input('Disponible para contratar', { name: 'activeForNew', type: 'checkbox', checked: plan?.activeForNew ?? true, disabled: plan?.free }),
  };
  fields.description.classList.add('span-3');
  const status = h('p', { class: 'form-text m-0', role: 'status' });
  form.append(
    h('h2', { class: 'h5 span-3 m-0', text: plan ? `${plan.name} (${plan.code})` : 'Plan nuevo' },
      plan ? h('small', { class: 'text-body-secondary fw-normal', text: ` · ${plan.activeSubscriptions} suscripciones vigentes` }) : null),
    ...Object.values(fields).filter(Boolean),
    h('div', { class: 'span-3 d-flex flex-wrap align-items-center gap-2' },
      h('button', { class: 'btn btn-primary', type: 'submit', text: plan ? 'Guardar cambios' : 'Crear plan' }), status));
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const v = (n) => form.elements.namedItem(n);
    const body = {
      code: plan ? undefined : v('code').value.trim(),
      name: v('name').value.trim(),
      description: v('description').value.trim() || null,
      price: plan?.free ? 0 : Number(v('price').value),
      maxPublishedCalculators: Number(v('maxPublishedCalculators').value),
      maxMonthlyQuotes: Number(v('maxMonthlyQuotes').value),
      csvExport: v('csvExport').checked,
      customBranding: v('customBranding').checked,
      leadEmailNotifications: v('leadEmailNotifications').checked,
      activeForNew: plan?.free ? true : v('activeForNew').checked,
      sortOrder: Number(v('sortOrder').value),
      lockVersion: plan?.lockVersion,
    };
    withBusy(e.submitter, async () => {
      try {
        const plans = plan ? await api.put(`/api/v1/admin/plans/${plan.id}`, body) : await api.post('/api/v1/admin/plans', body);
        renderPlans(plans);
        showNotice(notice, plan ? `Guardaste el plan ${body.name}.` : `Creaste el plan ${body.name}.`, 'ok');
      } catch (error) {
        status.textContent = error.fieldErrors?.map((f) => f.message).join(' ') || error.message;
      }
    });
  });
  return form;
}

function renderPlans(plans) {
  $('plan-forms').replaceChildren(...plans.map(planForm));
  const fresh = planForm(null);
  $('new-plan').replaceWith(fresh);
  fresh.id = 'new-plan';
  fresh.classList.remove('panel-block');
  fresh.classList.add('mt-3');
}
loaders.planes = async () => renderPlans(await api.get('/api/v1/admin/plans'));

// ------------------------------------------------------------------ integración

function eventList(container, events, empty) {
  container.replaceChildren(...(events.length ? events.map((e) => h('li', {},
    h('strong', { text: formatDateTime(e.receivedAt) }), ` · ${e.type} ${e.resourceId || ''} · `, e.detail || e.result))
    : [h('li', { text: empty })]));
}

loaders.integracion = async () => {
  const health = await api.get('/api/v1/admin/billing/health');
  stats($('health-stats'), [
    ['Proveedor', health.provider === 'FAKE' ? 'Simulador (solo desarrollo)' : 'Mercado Pago'],
    ['Credenciales', health.configured ? 'Configuradas' : 'Faltan (MERCADOPAGO_ACCESS_TOKEN)'],
    ['Clave de webhooks', health.webhookSecretConfigured ? 'Configurada' : 'Falta (MERCADOPAGO_WEBHOOK_SECRET)'],
    ['Última notificación', health.lastNotificationAt ? formatDateTime(health.lastNotificationAt) : 'Ninguna todavía'],
    ['Notificaciones con error (7 días)', formatNumber(health.failedLast7Days)],
    ['Última conciliación', health.lastReconciliationAt ? `${formatDateTime(health.lastReconciliationAt)}: ${health.lastReconciliationResult}` : 'Nunca'],
  ]);
  eventList($('corrections'), health.recentCorrections, 'Sin diferencias detectadas.');
  eventList($('failures'), health.recentFailures, 'Sin errores.');
};
$('reconcile').addEventListener('click', (e) => withBusy(e.currentTarget, async () => {
  try {
    const r = await api.post('/api/v1/admin/billing/reconcile');
    $('reconcile-result').textContent = `Revisadas ${r.checked}, corregidas ${r.corrected}, vencidas ${r.expired}, con error ${r.failed}.`;
    await loaders.integracion();
  } catch (error) { showNotice(notice, error.message); }
}));

// ------------------------------------------------------------------ inicio

if (me.user.platformRole !== 'SUPER_ADMIN') {
  showNotice(notice, 'Esta sección es exclusiva del equipo de Climind.', 'warn');
} else {
  $('admin-tabs').hidden = false;
  selectTab('resumen');
}
