// Consultas del negocio: listado con filtros, detalle con línea de tiempo, estados, notas, WhatsApp, PDF y CSV.
import { api } from './api.js';
import { formatDateTime, formatMoney, formatPhone } from './format.js';
import { initPanel } from './panel.js';
import { renderTicket } from './quote-form.js';
import { hideNotice, showNotice, withBusy } from './ui.js';

await initPanel({ current: '/app/consultas' });

const $ = (id) => document.getElementById(id);
const PAGE_SIZE = 20;
const STATUSES = [
  ['NUEVA', 'Nueva'], ['CONTACTADA', 'Contactada'], ['EN_NEGOCIACION', 'En negociación'],
  ['CONVERTIDA', 'Convertida'], ['PERDIDA', 'Perdida'],
];
const PLURAL = { NUEVA: 'Nuevas', CONTACTADA: 'Contactadas', EN_NEGOCIACION: 'En negociación', CONVERTIDA: 'Convertidas', PERDIDA: 'Perdidas' };

const params = new URLSearchParams(window.location.search);
const state = { status: params.get('estado') || '', page: 0, total: 0, counts: [] };
const filters = $('filters');
const notice = $('list-notice');

function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key === 'on') Object.entries(value).forEach(([ev, fn]) => node.addEventListener(ev, fn));
    else node.setAttribute(key, value === true ? '' : String(value));
  }
  node.append(...children.flat().filter((c) => c !== null && c !== undefined && c !== false));
  return node;
}

function query(extra = {}) {
  const q = new URLSearchParams();
  if (state.status) q.set('status', state.status);
  const text = $('q').value.trim();
  if (text) q.set('q', text);
  if ($('calculator').value) q.set('calculatorId', $('calculator').value);
  q.set('sort', $('sort').value);
  for (const [k, v] of Object.entries(extra)) q.set(k, v);
  return q;
}

// ------------------------------------------------------------------ filtros por estado

function renderStatusFilter() {
  const total = state.counts.reduce((sum, s) => sum + s.count, 0);
  const options = [['', 'Todas', total], ...state.counts.map((s) => [s.status, PLURAL[s.status], s.count])];
  $('status-filter').replaceChildren(...options.map(([value, label, count]) => h('button', {
    type: 'button', class: 'status-chip', 'aria-pressed': String(state.status === value),
    on: { click: () => { state.status = value; state.page = 0; renderStatusFilter(); load(); } },
  }, label, h('span', { class: 'status-chip-count', text: String(count) }))));
}

async function loadCounts() {
  state.counts = await api.get('/api/v1/leads/status-counts');
  renderStatusFilter();
}

// ------------------------------------------------------------------ listado

function statusBadge(status, label) {
  return h('span', { class: `lead-status lead-status-${status}`, text: label });
}

function row(lead) {
  const name = lead.anonymized ? 'Datos eliminados' : lead.customerName;
  return h('article', { class: 'lead-row' },
    h('div', { class: 'lead-main' },
      h('h2', {}, h('button', { type: 'button', class: 'lead-open', text: name, on: { click: () => openLead(lead.id) } })),
      h('p', { class: 'lead-meta', text: `${lead.code} · ${formatDateTime(lead.createdAt)} · ${lead.calculatorName}` }),
      lead.summary ? h('p', { class: 'lead-summary', text: lead.summary }) : null),
    h('div', { class: 'lead-side' },
      h('strong', { class: 'lead-total', text: formatMoney(lead.total, { decimals: 0 }) }),
      statusBadge(lead.status, lead.statusLabel)));
}

async function load() {
  hideNotice(notice);
  $('export-link').href = $('export-link').dataset.locked !== undefined ? '/app/plan' : `/api/v1/leads/export.csv?${query()}`;
  $('lead-list').classList.add('is-loading');
  try {
    const page = await api.get(`/api/v1/leads?${query({ page: state.page, size: PAGE_SIZE })}`);
    state.total = page.totalItems;
    $('lead-list').replaceChildren(...page.items.map(row));
    const anyLead = state.counts.some((s) => s.count > 0);
    $('empty-all').hidden = anyLead;
    $('empty-filter').hidden = !anyLead || page.items.length > 0;
    $('status-filter').hidden = !anyLead;
    filters.hidden = !anyLead;
    $('export-link').hidden = !anyLead;
    $('pager').hidden = page.totalPages <= 1;
    $('prev').disabled = page.page === 0;
    $('next').disabled = page.page >= page.totalPages - 1;
    const from = page.page * PAGE_SIZE + 1;
    $('page-info').textContent = `${from}–${from + page.items.length - 1} de ${page.totalItems}`;
  } catch (error) {
    showNotice(notice, error.message);
  } finally {
    $('lead-list').classList.remove('is-loading');
  }
}

let searchTimer;
$('q').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { state.page = 0; load(); }, 300); });
filters.addEventListener('submit', (e) => { e.preventDefault(); state.page = 0; load(); });
$('calculator').addEventListener('change', () => { state.page = 0; load(); });
$('sort').addEventListener('change', () => { state.page = 0; load(); });
$('prev').addEventListener('click', () => { state.page -= 1; load(); });
$('next').addEventListener('click', () => { state.page += 1; load(); });

// ------------------------------------------------------------------ detalle

const dialog = $('lead-dialog');
const leadNotice = $('lead-notice');
let current = null;

$('lead-close').addEventListener('click', () => dialog.close());
dialog.addEventListener('close', () => {
  const url = new URL(window.location.href);
  url.searchParams.delete('id');
  window.history.replaceState(null, '', url);
});

const HISTORY_TEXT = {
  CREATED: () => 'Recibiste la consulta',
  STATUS_CHANGED: (item) => `Estado: ${item.fromStatus} → ${item.toStatus}`,
  NOTE: () => 'Nota',
  ANONYMIZED: () => 'Se eliminaron los datos personales',
};

function renderDetail(detail) {
  current = detail;
  const { lead } = detail;
  $('lead-code').textContent = `${lead.code} · ${lead.calculatorName}`;
  $('lead-title').textContent = lead.anonymized ? 'Datos eliminados' : lead.customerName;
  $('lead-when').textContent = `Recibida el ${formatDateTime(lead.createdAt)}`;

  const statusSelect = h('select', { class: 'form-select', id: 'status-select' },
    STATUSES.map(([value, label]) => h('option', { value, text: label })));
  statusSelect.value = lead.status;
  const statusNote = h('textarea', { class: 'form-control', id: 'status-note', rows: 2, maxlength: 1000, placeholder: 'Nota opcional (por ejemplo, qué te respondió)' });
  const statusForm = h('form', { class: 'lead-status-form', on: { submit: (e) => {
    e.preventDefault();
    withBusy(e.submitter, async () => {
      try {
        renderDetail(await api.put(`/api/v1/leads/${lead.id}/status`, { status: statusSelect.value, note: statusNote.value.trim() || null }));
        showNotice(leadNotice, 'Guardamos el seguimiento.', 'ok');
        refreshList();
      } catch (error) { showNotice(leadNotice, error.message); }
    });
  } } },
  h('div', {}, h('label', { class: 'form-label', for: 'status-select', text: 'Estado' }), statusSelect),
  lead.anonymized ? null : h('div', {}, h('label', { class: 'form-label', for: 'status-note', text: 'Nota' }), statusNote),
  h('button', { class: 'btn btn-primary', type: 'submit', text: 'Guardar' }),
  h('p', { class: 'form-text m-0', text: 'Marcala como Convertida cuando se concrete la venta. Nada cambia de estado solo.' }));

  const contact = lead.anonymized
    ? h('p', { class: 'text-body-secondary', text: 'Los datos de contacto de esta consulta se eliminaron.' })
    : h('div', { class: 'lead-contact' },
      detail.whatsappUrl ? h('a', { class: 'btn btn-whatsapp', href: detail.whatsappUrl, target: '_blank', rel: 'noopener', text: `Escribir por WhatsApp (${formatPhone(lead.customerWhatsapp)})` }) : null,
      lead.customerEmail ? h('a', { class: 'btn btn-quiet', href: `mailto:${lead.customerEmail}`, text: lead.customerEmail }) : null);

  const ticket = h('div', { class: 'ticket ticket-compact' });
  renderTicket(ticket, detail.result, {
    businessName: lead.calculatorName, title: 'Cotización enviada', scale: detail.priceScale,
    validUntil: detail.validUntil, disclaimer: null, code: lead.code,
  });

  const answers = (detail.result.answered || []).map((a) => h('div', { class: 'lead-answer' }, h('dt', { text: a.label }), h('dd', { text: a.value })));
  const timeline = h('ol', { class: 'lead-timeline' }, detail.history.map((item) => h('li', {},
    h('strong', { text: HISTORY_TEXT[item.kind](item) }),
    h('span', { text: formatDateTime(item.createdAt) }),
    item.note ? h('p', { text: item.note }) : null)));

  const noteInput = h('textarea', { class: 'form-control', id: 'note-input', rows: 2, maxlength: 1000, required: true });
  const noteForm = lead.anonymized ? null : h('form', { class: 'lead-note-form', on: { submit: (e) => {
    e.preventDefault();
    if (!noteInput.value.trim()) return noteInput.focus();
    withBusy(e.submitter, async () => {
      try {
        renderDetail(await api.post(`/api/v1/leads/${lead.id}/notes`, { note: noteInput.value.trim() }));
      } catch (error) { showNotice(leadNotice, error.message); }
    });
  } } }, h('label', { class: 'form-label', for: 'note-input', text: 'Agregar una nota' }), noteInput,
  h('button', { class: 'btn btn-quiet', type: 'submit', text: 'Agregar nota' }));

  const pdf = h('a', { class: 'btn btn-quiet', href: `/api/v1/leads/${lead.id}/pdf`, download: true, text: 'Descargar PDF' });
  const anonymize = lead.anonymized ? null : h('button', { type: 'button', class: 'btn btn-link text-danger p-0', text: 'Eliminar datos personales', on: { click: async () => {
    const confirm = $('confirm-dialog');
    confirm.returnValue = '';
    confirm.showModal();
    confirm.addEventListener('close', async () => {
      if (confirm.returnValue !== 'ok') return;
      try {
        renderDetail(await api.post(`/api/v1/leads/${lead.id}/anonymize`));
        showNotice(leadNotice, 'Se eliminaron los datos personales de la consulta.', 'ok');
        refreshList();
      } catch (error) { showNotice(leadNotice, error.message); }
    }, { once: true });
  } } });

  $('lead-body').replaceChildren(
    h('section', { class: 'lead-section' }, h('h3', { text: 'Contacto' }), contact,
      detail.comments ? h('blockquote', { class: 'lead-comments', text: detail.comments }) : null),
    h('section', { class: 'lead-section' }, h('h3', { text: 'Seguimiento' }), statusForm),
    h('section', { class: 'lead-section' }, h('h3', { text: 'Lo que cotizó' }),
      answers.length ? h('dl', { class: 'lead-answers' }, answers) : null, ticket,
      detail.whatsappClickedAt ? h('p', { class: 'form-text', text: 'El cliente también abrió WhatsApp desde la cotización.' }) : null),
    h('section', { class: 'lead-section' }, h('h3', { text: 'Historial' }), timeline, noteForm),
    h('section', { class: 'lead-section lead-actions' }, pdf, anonymize),
  );
}

async function openLead(id) {
  hideNotice(leadNotice);
  try {
    renderDetail(await api.get(`/api/v1/leads/${id}`));
    if (!dialog.open) dialog.showModal();
    const url = new URL(window.location.href);
    url.searchParams.set('id', id);
    window.history.replaceState(null, '', url);
  } catch (error) {
    showNotice(notice, error.message);
  }
}

function refreshList() {
  loadCounts().then(load).catch(() => {});
}

// ------------------------------------------------------------------ inicio

try {
  const [calcs, billing] = await Promise.all([api.get('/api/v1/calculators?archived=true'), api.get('/api/v1/billing')]);
  if (!billing.current.csvExport) {
    // La exportación es de los planes pagos (el servidor también lo verifica).
    const link = $('export-link');
    link.removeAttribute('download');
    link.textContent = 'Exportar CSV (planes pagos)';
    link.dataset.locked = '';
  }
  calcs.forEach((c) => $('calculator').add(new Option(c.name, c.id)));
  await loadCounts();
  await load();
  if (params.get('id')) openLead(params.get('id'));
} catch (error) {
  showNotice(notice, error.message);
}
