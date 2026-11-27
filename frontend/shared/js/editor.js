// Constructor de cotizadores. Edita una copia de trabajo de la configuración; la vista previa la calcula
// el servidor con el motor real (POST /preview con la configuración sin guardar). Guardar = PUT del borrador;
// Publicar = versión nueva e inmutable.
import { api, ApiError } from './api.js';
import * as M from './calculator-model.js';
import { formatDateTime } from './format.js';
import { initPanel } from './panel.js';
import { renderQuoteForm, renderTicket, visibleAnswers, visibleQuestions } from './quote-form.js';
import { hideNotice, planLink, showNotice, withBusy } from './ui.js';

const me = await initPanel({ current: '/app/cotizadores' });
// Con la baja de la cuenta programada, ningún cotizador atiende al público (la página pública da 404).
const accountClosing = Boolean(me.tenant?.deletionScheduledFor);
const calculatorId = new URLSearchParams(window.location.search).get('id');
if (!calculatorId) window.location.replace('/app/cotizadores');

const appUrl = window.CLIMIND_CONFIG?.appUrl || window.location.origin;
const $ = (id) => document.getElementById(id);
const notice = $('editor-notice');

const state = {
  calc: null,          // último detalle recibido del servidor
  config: null,        // copia de trabajo
  info: null,          // { name, description, slug }
  appearance: null,    // { accentColor, showLogo }
  dirty: false,
  serverIssues: [],    // problemas devueltos al guardar (CONFIG_INVALID)
  expanded: new Set(), // tarjetas abiertas
  answers: {},         // respuestas de la vista previa
  profile: null,
  tab: 'info',
};

// ------------------------------------------------------------------ DOM mínimo

/** h('div', {class, on: {click}}, ...hijos). Atributos booleanos con true/false. */
function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'on') Object.entries(value).forEach(([ev, fn]) => node.addEventListener(ev, fn));
    else if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key === 'value') node.value = value;
    else if (key === 'checked') node.checked = Boolean(value);
    else node.setAttribute(key, value === true ? '' : String(value));
  }
  node.append(...children.flat(Infinity).filter((c) => c !== null && c !== undefined && c !== false));
  return node;
}

/** append() que ignora null/undefined/false (append(null) escribiría el texto "null"). */
function appendAll(node, ...children) {
  node.append(...children.flat(Infinity).filter((c) => c !== null && c !== undefined && c !== false));
}

let uid = 0;
const fid = (p) => `${p}-${++uid}`;

/** Campo con etiqueta. Si el control viene envuelto (importe, porcentaje), la etiqueta apunta al input real. */
function field(labelText, control, help) {
  const target = control.matches('input, select, textarea') ? control : control.querySelector('input, select, textarea');
  if (!target) return h('div', {}, h('span', { class: 'form-label d-block', text: labelText }), control);
  const id = target.id || fid('f');
  target.id = id;
  const helpEl = help ? h('div', { class: 'form-text', id: `${id}-help`, text: help }) : null;
  if (helpEl) target.setAttribute('aria-describedby', helpEl.id);
  return h('div', {}, h('label', { class: 'form-label', for: id, text: labelText }), control, helpEl);
}

function textInput(value, onInput, attrs = {}) {
  return h('input', { class: 'form-control', type: 'text', value: value ?? '', ...attrs, on: { input: (e) => onInput(e.target.value) } });
}

function select(options, value, onChange, attrs = {}) {
  const el = h('select', { class: 'form-select', ...attrs, on: { change: (e) => onChange(e.target.value) } },
    options.map(([v, label]) => h('option', { value: v, text: label })));
  el.value = value ?? '';
  return el;
}

/** Importe en pesos (formato argentino). Solo actualiza el estado con valores válidos. */
function moneyInput(value, onValue, attrs = {}) {
  const input = h('input', { class: 'form-control', type: 'text', inputmode: 'decimal', autocomplete: 'off', value: M.moneyText(value), ...attrs });
  input.addEventListener('input', () => {
    const parsed = M.parseMoney(input.value);
    const valid = parsed !== null || (input.value.trim() === '' && attrs['data-optional']);
    input.classList.toggle('is-invalid', !valid);
    input.toggleAttribute('aria-invalid', !valid);
    if (valid) onValue(parsed);
  });
  input.addEventListener('blur', () => { if (!input.classList.contains('is-invalid')) input.value = M.moneyText(M.parseMoney(input.value)); });
  return h('div', { class: 'money-input' }, input);
}

function percentInput(value, onValue) {
  const input = h('input', { class: 'form-control', type: 'text', inputmode: 'decimal', value: value ?? '' });
  input.addEventListener('input', () => {
    const n = M.parseNumber(input.value);
    const valid = n !== null && n >= 0;
    input.classList.toggle('is-invalid', !valid);
    if (valid) onValue(n);
  });
  return h('div', { class: 'percent-input' }, input);
}

function numberInput(value, onValue, attrs = {}) {
  const input = h('input', { class: 'form-control', type: 'text', inputmode: 'decimal', value: value ?? '', ...attrs });
  input.addEventListener('input', () => {
    const n = M.parseNumber(input.value);
    const valid = n !== null || input.value.trim() === '';
    input.classList.toggle('is-invalid', !valid);
    if (valid) onValue(n);
  });
  return input;
}

function confirmDialog(title, text, okLabel = 'Confirmar') {
  const dialog = $('confirm-dialog');
  $('confirm-title').textContent = title;
  $('confirm-text').textContent = text;
  $('confirm-ok').textContent = okLabel;
  return new Promise((resolve) => {
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok'), { once: true });
    dialog.showModal();
  });
}

// ------------------------------------------------------------------ estado

function changed({ rerender = false } = {}) {
  state.dirty = true;
  updateHeader();
  if (rerender) renderActiveTab();
  schedulePreview();
}

function updateHeader() {
  const { calc } = state;
  $('calc-title').textContent = state.info.name || 'Cotizador sin nombre';
  const status = $('calc-status');
  status.textContent = { BORRADOR: 'Borrador', PUBLICADO: 'Publicado', PAUSADO: 'Pausado', ARCHIVADO: 'Archivado' }[calc.status];
  status.className = `status-badge status-${calc.status}`;
  if (accountClosing && calc.status === 'PUBLICADO') {
    status.textContent = 'No disponible';
    status.className = 'status-badge status-PAUSADO';
  }
  const saveState = $('save-state');
  if (state.dirty) {
    saveState.textContent = 'Cambios sin guardar';
    saveState.className = 'save-state is-dirty';
  } else if (calc.hasUnpublishedChanges) {
    saveState.textContent = 'Guardado. Hay cambios sin publicar';
    saveState.className = 'save-state is-dirty';
  } else {
    saveState.textContent = calc.publishedVersionNo ? `Versión ${calc.publishedVersionNo} publicada` : 'Guardado';
    saveState.className = 'save-state';
  }
  $('save-button').disabled = !state.dirty || calc.status === 'ARCHIVADO';
  const publish = $('publish-button');
  publish.hidden = calc.status === 'ARCHIVADO';
  const pending = state.dirty || calc.hasUnpublishedChanges;
  const upToDate = calc.status === 'PUBLICADO' && !pending;
  publish.disabled = upToDate;
  publish.textContent = upToDate ? 'Sin cambios para publicar'
    : calc.publishedVersionNo && pending ? 'Publicar cambios'
      : calc.status === 'PAUSADO' ? 'Reactivar' : 'Publicar';
  updateTabBadges();
}

function updateTabBadges() {
  const issues = state.dirty ? state.serverIssues : (state.calc.publishIssues || []);
  const count = (tab) => issues.filter((i) => sectionTab(i.section) === tab).length;
  for (const tab of ['preguntas', 'precios', 'publicacion']) {
    const button = $(`tab-${tab}`);
    button.querySelector('.tab-count')?.remove();
    const n = count(tab);
    if (n > 0) button.append(h('span', { class: 'tab-count', text: String(n), 'aria-label': `${n} para revisar` }));
  }
}

const sectionTab = (section) => ({ questions: 'preguntas', pricing: 'precios', commercial: 'publicacion', general: 'preguntas' }[section] || 'info');

function issuesFor(item) {
  const all = [...state.serverIssues, ...(state.dirty ? [] : state.calc.publishIssues || [])];
  return [...new Set(all.filter((i) => i.item === item).map((i) => i.message))];
}

function loadDetail(detail) {
  state.calc = detail;
  state.config = structuredClone(detail.config);
  state.config.pricing ??= { rules: [] };
  state.config.pricing.rules ??= [];
  state.config.commercial ??= {};
  state.info = { name: detail.name, description: detail.description ?? '', slug: detail.slug };
  state.appearance = { ...detail.appearance };
  state.dirty = false;
  state.serverIssues = [];
  updateHeader();
  renderActiveTab();
  schedulePreview(0);
}

// ------------------------------------------------------------------ pestañas

const TABS = ['info', 'preguntas', 'precios', 'apariencia', 'publicacion'];

function selectTab(tab, focus = false) {
  state.tab = tab;
  for (const t of TABS) {
    const button = $(`tab-${t}`);
    const selected = t === tab;
    button.setAttribute('aria-selected', String(selected));
    button.tabIndex = selected ? 0 : -1;
    $(`panel-${t}`).hidden = !selected;
  }
  renderActiveTab();
  if (focus) $(`tab-${tab}`).focus();
}

document.querySelectorAll('.builder-tabs [role="tab"]').forEach((button) => {
  button.addEventListener('click', () => selectTab(button.id.replace('tab-', '')));
  button.addEventListener('keydown', (e) => {
    const index = TABS.indexOf(state.tab);
    if (e.key === 'ArrowRight') selectTab(TABS[(index + 1) % TABS.length], true);
    if (e.key === 'ArrowLeft') selectTab(TABS[(index - 1 + TABS.length) % TABS.length], true);
  });
});

function renderActiveTab() {
  const renderers = { info: renderInfo, preguntas: renderQuestions, precios: renderPricing, apariencia: renderAppearance, publicacion: renderPublication };
  const panel = $(`panel-${state.tab}`);
  panel.replaceChildren(...[renderers[state.tab]()].flat(Infinity).filter(Boolean));
}

// ------------------------------------------------------------------ Información

function renderInfo() {
  const slug = textInput(state.info.slug, (v) => {
    state.info.slug = v.toLowerCase().replace(/[^a-z0-9-]+/g, '-');
    urlPreview.textContent = `${appUrl}/c/${me.tenant.slug}/${state.info.slug}`;
    changed();
  }, { maxlength: 60, autocomplete: 'off', spellcheck: 'false' });
  const urlPreview = h('code', { text: `${appUrl}/c/${me.tenant.slug}/${state.info.slug}` });
  return [
    h('p', { class: 'lead-text', text: 'Cómo se llama el cotizador y qué ve el cliente al entrar.' }),
    h('div', { class: 'field-grid' },
      field('Nombre del cotizador', textInput(state.info.name, (v) => { state.info.name = v; changed(); }, { maxlength: 120 }),
        'Es el título que ve tu cliente. Por ejemplo: Presupuesto de limpieza.'),
      field('Descripción (opcional)', h('textarea', {
        class: 'form-control', rows: 3, maxlength: 500, value: state.info.description,
        on: { input: (e) => { state.info.description = e.target.value; changed(); } },
      }), 'Una frase que explique qué se cotiza.'),
      field('Dirección del enlace', slug, 'Solo minúsculas, números y guiones. Si ya compartiste el enlace, cambiarla lo deja de funcionar.'),
      h('div', { class: 'url-box' }, urlPreview)),
  ];
}

// ------------------------------------------------------------------ Preguntas

function renderQuestions() {
  const questions = state.config.questions;
  const generalIssues = issuesFor(null).filter((m) => m.includes('pregunta'));
  const list = h('div', { class: 'item-list' }, questions.map((q, index) => questionCard(q, index)));
  return [
    h('p', { class: 'lead-text', text: 'Lo que le preguntás al cliente, en el orden en que lo ve. Las preguntas pueden aparecer solo si se cumple una condición.' }),
    generalIssues.length ? h('ul', { class: 'item-issues mb-3' }, generalIssues.map((m) => h('li', { text: m }))) : null,
    questions.length ? list : h('div', { class: 'empty-state mb-3' },
      h('h2', { text: 'Todavía no hay preguntas' }), h('p', { text: 'Agregá la primera eligiendo el tipo de respuesta.' })),
    h('h3', { class: 'h6 mt-2', text: 'Agregar una pregunta' }),
    h('div', { class: 'add-menu' }, Object.entries(M.QUESTION_TYPES).map(([type, meta]) => h('button', {
      type: 'button',
      on: { click: () => {
        if (questions.length >= 40) return showNotice(notice, 'Un cotizador admite hasta 40 preguntas.');
        const q = M.newQuestion(state.config, type);
        questions.push(q);
        state.expanded.add(q.key);
        changed({ rerender: true });
        document.querySelector(`[data-question="${q.key}"] input`)?.focus();
      } },
    }, h('strong', { text: meta.label }), h('span', { text: meta.hint })))),
  ];
}

function questionCard(q, index) {
  const questions = state.config.questions;
  const expanded = state.expanded.has(q.key);
  const bodyId = fid('qbody');
  const title = h('strong', { text: q.label || 'Pregunta sin texto' });
  const issues = issuesFor(q.key);
  const head = h('div', { class: 'item-card-head' },
    h('div', { class: 'item-title' }, title,
      h('span', { text: `${M.QUESTION_TYPES[q.type]?.label ?? q.type}, ${q.required ? 'obligatoria' : 'opcional'}${(q.visibleIf || []).length ? ', con condición' : ''}` })),
    h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Subir pregunta', disabled: index === 0, text: '↑',
      on: { click: () => moveQuestion(index, -1) } }),
    h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Bajar pregunta', disabled: index === questions.length - 1, text: '↓',
      on: { click: () => moveQuestion(index, 1) } }),
    h('button', { type: 'button', class: 'icon-btn', 'aria-expanded': String(expanded), 'aria-controls': bodyId,
      'aria-label': expanded ? 'Cerrar edición' : 'Editar pregunta', text: expanded ? '−' : '✎',
      on: { click: () => { expanded ? state.expanded.delete(q.key) : state.expanded.add(q.key); renderActiveTab(); } } }),
    h('button', { type: 'button', class: 'icon-btn danger', 'aria-label': 'Eliminar pregunta', text: '✕',
      on: { click: () => deleteQuestion(q) } }));

  const body = h('div', { class: 'item-card-body', id: bodyId, hidden: !expanded });
  if (expanded) {
    appendAll(body,
      issues.length ? h('ul', { class: 'item-issues' }, issues.map((m) => h('li', { text: m }))) : null,
      field('Pregunta', textInput(q.label, (v) => { q.label = v; title.textContent = v || 'Pregunta sin texto'; changed(); },
        { maxlength: 200, placeholder: 'Por ejemplo: ¿Cuántos metros cuadrados tiene el lugar?' })),
      h('div', { class: 'field-grid cols-2' },
        field('Tipo de respuesta', select(Object.entries(M.QUESTION_TYPES).map(([t, m]) => [t, m.label]), q.type, async (type) => {
          const deps = M.dependentsOf(state.config, q.key);
          if (deps.rules.length || deps.conditions.length) {
            const ok = await confirmDialog('Cambiar el tipo de respuesta',
              `Esta pregunta se usa en ${deps.rules.length + deps.conditions.length} regla(s) o condición(es). Al cambiar el tipo se van a quitar.`, 'Cambiar tipo');
            if (!ok) return renderActiveTab();
          }
          M.changeQuestionType(state.config, q, type);
          changed({ rerender: true });
        })),
        h('div', { class: 'form-check mt-md-4 pt-md-2' },
          h('input', { class: 'form-check-input', type: 'checkbox', id: `${bodyId}-req`, checked: q.required,
            on: { change: (e) => { q.required = e.target.checked; changed(); } } }),
          h('label', { class: 'form-check-label', for: `${bodyId}-req`, text: 'Respuesta obligatoria' }))),
      field('Ayuda (opcional)', textInput(q.help, (v) => { q.help = v || undefined; changed(); }, { maxlength: 500 }),
        'Un texto corto debajo de la pregunta.'),
      ...typeFields(q),
      visibilityEditor(q, index),
    );
  }
  return h('div', { class: `item-card${issues.length ? ' has-issue' : ''}`, 'data-question': q.key }, head, body);
}

function typeFields(q) {
  if (M.isChoice(q.type)) {
    const rows = h('div', { class: 'option-rows' }, q.options.map((o, i) => h('div', { class: 'option-row' },
      h('input', { class: 'form-control', type: 'text', value: o.label, maxlength: 200, 'aria-label': `Opción ${i + 1}`,
        placeholder: `Opción ${i + 1}`, on: { input: (e) => { o.label = e.target.value; changed(); } } }),
      h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Subir opción', disabled: i === 0, text: '↑',
        on: { click: () => { M.move(q.options, i, -1); changed({ rerender: true }); } } }),
      h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Bajar opción', disabled: i === q.options.length - 1, text: '↓',
        on: { click: () => { M.move(q.options, i, 1); changed({ rerender: true }); } } }),
      h('button', { type: 'button', class: 'icon-btn danger', 'aria-label': 'Quitar opción', text: '✕',
        on: { click: () => { M.removeOption(state.config, q, o.key); changed({ rerender: true }); } } }))));
    return [h('div', {}, h('span', { class: 'form-label d-block', text: 'Opciones' }), rows,
      h('button', { type: 'button', class: 'btn btn-quiet btn-sm mt-2', text: 'Agregar opción', on: { click: () => {
        if (q.options.length >= 30) return;
        q.options.push(M.newOption(q));
        changed({ rerender: true });
        const inputs = document.querySelectorAll(`[data-question="${q.key}"] .option-row input`);
        inputs[inputs.length - 1]?.focus();
      } } }))];
  }
  if (M.isNumeric(q.type)) {
    return [h('div', { class: 'field-grid cols-3' },
      field('Mínimo', numberInput(q.min, (n) => { q.min = n ?? undefined; changed(); })),
      field('Máximo', numberInput(q.max, (n) => { q.max = n ?? undefined; changed(); })),
      field('Unidad (opcional)', textInput(q.unit, (v) => { q.unit = v || undefined; changed(); }, { maxlength: 20, placeholder: 'm², invitados…' }))),
    q.type === 'NUMBER' ? h('div', { class: 'form-check' },
      h('input', { class: 'form-check-input', type: 'checkbox', id: `int-${q.key}`, checked: q.integer,
        on: { change: (e) => { q.integer = e.target.checked || undefined; changed(); } } }),
      h('label', { class: 'form-check-label', for: `int-${q.key}`, text: 'Solo números enteros' })) : null];
  }
  if (q.type === 'TEXT') {
    return [field('Largo máximo', numberInput(q.maxLength, (n) => { q.maxLength = n ?? undefined; changed(); }), 'Hasta 2000 caracteres.')];
  }
  return [];
}

/**
 * Editor de condición simple. `candidates`: preguntas que se pueden usar. `current`: condición o undefined.
 * Llama a onChange(condición | null).
 */
function conditionEditor(labelText, alwaysText, candidates, current, onChange) {
  const usable = candidates.filter((q) => M.affectsPrice(q.type));
  const wrapper = h('fieldset', { class: 'condition-editor border-0 p-0 m-0' });
  const render = (cond) => {
    const mode = select([['always', alwaysText], ['if', 'Solo si…']], cond ? 'if' : 'always', (v) => {
      if (v === 'always') { onChange(null); render(null); return; }
      const q = usable[0];
      if (!q) return;
      const next = defaultCondition(q);
      onChange(next);
      render(next);
    }, { disabled: !usable.length && !cond });
    const parts = [h('legend', { class: 'form-label', text: labelText }), mode];
    if (!usable.length && !cond) parts.push(h('div', { class: 'form-text', text: 'Para usar condiciones, primero agregá preguntas de opciones, cantidad o sí/no.' }));
    if (cond) {
      const q = usable.find((x) => x.key === cond.question);
      const row = h('div', { class: 'condition-row mt-2' });
      row.append(select(usable.map((x) => [x.key, x.label || 'Pregunta sin texto']), cond.question, (key) => {
        const next = defaultCondition(usable.find((x) => x.key === key));
        onChange(next);
        render(next);
      }, { 'aria-label': 'Pregunta de la condición' }));
      if (q) {
        row.append(select(M.operatorsFor(q.type).map((op) => [op, M.OPERATORS[op]]), cond.op, (op) => {
          cond.op = op;
          onChange(cond);
        }, { 'aria-label': 'Comparación' }));
        if (M.isChoice(q.type)) {
          row.append(select(q.options.map((o) => [o.key, o.label || 'Opción sin texto']), cond.value, (v) => { cond.value = v; onChange(cond); },
            { 'aria-label': 'Opción' }));
        } else if (M.isNumeric(q.type)) {
          row.append(numberInput(cond.value, (n) => { cond.value = n === null ? '' : String(n); onChange(cond); }, { 'aria-label': 'Valor' }));
        }
      }
      parts.push(row);
    }
    wrapper.replaceChildren(...parts);
  };
  render(current);
  return wrapper;
}

function defaultCondition(q) {
  if (M.isChoice(q.type)) return { question: q.key, op: q.type === 'MULTI_CHOICE' ? 'INCLUDES' : 'EQUALS', value: q.options[0]?.key ?? '' };
  if (q.type === 'YES_NO') return { question: q.key, op: 'IS_TRUE' };
  return { question: q.key, op: 'GTE', value: String(q.min ?? 1) };
}

function visibilityEditor(q, index) {
  const previous = state.config.questions.slice(0, index);
  return conditionEditor('¿Cuándo se muestra?', 'Siempre', previous, (q.visibleIf || [])[0], (cond) => {
    q.visibleIf = cond ? [cond] : [];
    changed();
  });
}

function moveQuestion(index, delta) {
  const questions = state.config.questions;
  const moving = questions[index];
  const target = questions[index + delta];
  // Una pregunta no puede quedar antes de otra de la que depende su visibilidad.
  const depends = (a, b) => (a.visibleIf || []).some((c) => c.question === b.key);
  if ((delta < 0 && depends(moving, target)) || (delta > 0 && depends(target, moving))) {
    showNotice(notice, 'No se puede mover: una pregunta se muestra según la respuesta de la otra, que tiene que ir antes.', 'warn');
    return;
  }
  hideNotice(notice);
  M.move(questions, index, delta);
  changed({ rerender: true });
}

async function deleteQuestion(q) {
  const deps = M.dependentsOf(state.config, q.key);
  const extra = deps.rules.length + deps.conditions.length;
  const ok = await confirmDialog('Eliminar pregunta',
    `Vas a eliminar “${q.label || 'Pregunta sin texto'}”.${extra ? ` También se van a quitar ${extra} regla(s) o condición(es) que la usan.` : ''}`, 'Eliminar');
  if (!ok) return;
  M.removeQuestion(state.config, q.key);
  delete state.answers[q.key];
  changed({ rerender: true });
}

// ------------------------------------------------------------------ Precios

function ruleSummary(rule) {
  const q = (key) => M.questionLabel(state.config, key);
  switch (rule.type) {
    case 'OPTION_PRICE': return `Según: ${q(rule.question)}`;
    case 'PER_UNIT': return `Por unidad de: ${q(rule.question)}`;
    case 'OPTION_PER_UNIT': return `${q(rule.quantityQuestion)} × precio de: ${q(rule.question)}`;
    case 'FIXED': return rule.label || 'Importe fijo sin nombre';
    case 'SURCHARGE_PERCENT': return `${rule.label || 'Recargo'} (+${rule.percent ?? 0} %)`;
    case 'DISCOUNT_PERCENT': return `${rule.label || 'Descuento'} (−${rule.percent ?? 0} %)`;
    default: return rule.type;
  }
}

function renderPricing() {
  const rules = state.config.pricing.rules;
  const pricingIssues = issuesFor(null).filter((m) => /precio|impuesto|redondeo|mínimo/i.test(m));
  return [
    h('p', { class: 'lead-text', text: 'Cómo se arma el precio. Primero se suman los importes; después se aplican recargos, descuentos, el precio mínimo e impuestos.' }),
    pricingIssues.length ? h('ul', { class: 'item-issues mb-3' }, pricingIssues.map((m) => h('li', { text: m }))) : null,
    rules.length ? h('div', { class: 'item-list' }, rules.map((r, i) => ruleCard(r, i)))
      : h('div', { class: 'empty-state mb-3' }, h('h2', { text: 'Todavía no hay precios' }),
        h('p', { text: 'Agregá cómo se calcula el precio. Lo más común: un precio según la opción elegida o un precio por unidad.' })),
    h('h3', { class: 'h6 mt-2', text: 'Agregar un precio' }),
    h('div', { class: 'add-menu' }, Object.entries(M.RULE_TYPES).map(([type, meta]) => h('button', {
      type: 'button',
      on: { click: () => {
        if (rules.length >= 60) return showNotice(notice, 'Un cotizador admite hasta 60 reglas de precio.');
        const rule = M.newRule(state.config, type);
        rules.push(rule);
        state.expanded.add(`rule:${rule.id}`);
        changed({ rerender: true });
      } },
    }, h('strong', { text: meta.label }), h('span', { text: meta.hint })))),
    pricingSettings(),
  ];
}

function ruleCard(rule, index) {
  const rules = state.config.pricing.rules;
  const key = `rule:${rule.id}`;
  const expanded = state.expanded.has(key);
  const bodyId = fid('rbody');
  const issues = issuesFor(rule.id);
  const summary = h('strong', { text: ruleSummary(rule) });
  const refresh = () => { summary.textContent = ruleSummary(rule); changed(); };
  const head = h('div', { class: 'item-card-head' },
    h('div', { class: 'item-title' }, summary,
      h('span', { text: `${M.RULE_TYPES[rule.type]?.label ?? rule.type}${(rule.when || []).length ? ', con condición' : ''}` })),
    h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Subir', disabled: index === 0, text: '↑',
      on: { click: () => { M.move(rules, index, -1); changed({ rerender: true }); } } }),
    h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Bajar', disabled: index === rules.length - 1, text: '↓',
      on: { click: () => { M.move(rules, index, 1); changed({ rerender: true }); } } }),
    h('button', { type: 'button', class: 'icon-btn', 'aria-expanded': String(expanded), 'aria-controls': bodyId,
      'aria-label': expanded ? 'Cerrar edición' : 'Editar precio', text: expanded ? '−' : '✎',
      on: { click: () => { expanded ? state.expanded.delete(key) : state.expanded.add(key); renderActiveTab(); } } }),
    h('button', { type: 'button', class: 'icon-btn danger', 'aria-label': 'Eliminar precio', text: '✕',
      on: { click: async () => {
        if (await confirmDialog('Eliminar precio', `Vas a eliminar “${ruleSummary(rule)}”.`, 'Eliminar')) {
          rules.splice(index, 1);
          changed({ rerender: true });
        }
      } } }));
  const body = h('div', { class: 'item-card-body', id: bodyId, hidden: !expanded });
  if (expanded) {
    appendAll(body, issues.length ? h('ul', { class: 'item-issues' }, issues.map((m) => h('li', { text: m }))) : null,
      ...ruleFields(rule, refresh),
      conditionEditor('¿Cuándo se aplica?', 'Siempre', state.config.questions, (rule.when || [])[0], (cond) => {
        rule.when = cond ? [cond] : [];
        refresh();
      }));
  }
  return h('div', { class: `item-card${issues.length ? ' has-issue' : ''}` }, head, body);
}

function questionSelect(filter, value, onChange, label) {
  const options = state.config.questions.filter(filter).map((q) => [q.key, q.label || 'Pregunta sin texto']);
  if (!options.length) return h('p', { class: 'form-text', text: 'Primero agregá una pregunta del tipo necesario en la pestaña Preguntas.' });
  return field(label, select([['', 'Elegí una pregunta'], ...options], value, onChange));
}

function priceTable(questionKey, prices, onPrice, header) {
  const q = state.config.questions.find((x) => x.key === questionKey);
  if (!q) return null;
  return h('div', {}, h('span', { class: 'form-label d-block', text: header }),
    h('div', { class: 'price-table' }, q.options.map((o) => h('div', { class: 'price-row' },
      h('span', { text: o.label || 'Opción sin texto' }),
      moneyInput(prices[o.key], (v) => onPrice(o.key, v), { 'aria-label': `Precio de ${o.label || 'la opción'}`, 'data-optional': 'true' })))));
}

function ruleFields(rule, refresh) {
  const nameField = (labelText, help) => field(labelText, textInput(rule.label, (v) => { rule.label = v; refresh(); }, { maxlength: 200 }), help);
  const setPrice = (k, v) => { if (v === null) delete rule.prices[k]; else rule.prices[k] = v; refresh(); };
  switch (rule.type) {
    case 'OPTION_PRICE':
      return [questionSelect((q) => M.isChoice(q.type), rule.question, (v) => { rule.question = v; rule.prices = {}; refresh(); renderActiveTab(); }, 'Pregunta'),
        priceTable(rule.question, rule.prices, setPrice, 'Precio de cada opción'),
        h('p', { class: 'form-text m-0', text: 'Las opciones sin precio no suman nada.' })];
    case 'PER_UNIT':
      return [questionSelect((q) => M.isNumeric(q.type), rule.question, (v) => { rule.question = v; refresh(); }, 'Cantidad o medida'),
        nameField('Nombre en el detalle (opcional)', 'Si lo dejás vacío se usa el texto de la pregunta.'),
        field('Precio por unidad', moneyInput(rule.unitPrice, (v) => { rule.unitPrice = v; refresh(); })),
        tiersEditor(rule, refresh)];
    case 'OPTION_PER_UNIT':
      return [h('div', { class: 'field-grid cols-2' },
        questionSelect((q) => q.type === 'SINGLE_CHOICE', rule.question, (v) => { rule.question = v; rule.prices = {}; refresh(); renderActiveTab(); }, 'Opción que define el precio'),
        questionSelect((q) => M.isNumeric(q.type), rule.quantityQuestion, (v) => { rule.quantityQuestion = v; refresh(); }, 'Cantidad que multiplica')),
      priceTable(rule.question, rule.prices, setPrice, 'Precio por unidad de cada opción')];
    case 'FIXED':
      return [h('div', { class: 'field-grid cols-2' }, nameField('Nombre', 'Así aparece en el detalle. Ej.: Instalación.'),
        field('Importe', moneyInput(rule.amount, (v) => { rule.amount = v; refresh(); })))];
    case 'SURCHARGE_PERCENT':
    case 'DISCOUNT_PERCENT':
      return [h('div', { class: 'field-grid cols-2' },
        nameField('Nombre', rule.type === 'SURCHARGE_PERCENT' ? 'Ej.: Urgencia.' : 'Ej.: Promoción de lanzamiento.'),
        field('Porcentaje', percentInput(rule.percent, (v) => { rule.percent = v; refresh(); })))];
    default:
      return [];
  }
}

function tiersEditor(rule, refresh) {
  rule.tiers ??= [];
  const rows = rule.tiers.map((tier, i) => h('div', { class: 'price-row' },
    h('div', { class: 'd-flex align-items-center gap-2' }, h('span', { text: 'Desde' }),
      numberInput(tier.from, (n) => { tier.from = n; refresh(); }, { 'aria-label': `Cantidad desde, tramo ${i + 1}` })),
    h('div', { class: 'd-flex align-items-center gap-1' },
      moneyInput(tier.unitPrice, (v) => { tier.unitPrice = v; refresh(); }, { 'aria-label': `Precio por unidad, tramo ${i + 1}` }),
      h('button', { type: 'button', class: 'icon-btn danger', 'aria-label': 'Quitar tramo', text: '✕',
        on: { click: () => { rule.tiers.splice(i, 1); changed({ rerender: true }); } } }))));
  return h('div', {}, h('span', { class: 'form-label d-block', text: 'Precio por volumen (opcional)' }),
    h('p', { class: 'form-text mt-0', text: 'Desde cierta cantidad, toda la cantidad se cobra a otro precio por unidad.' }),
    h('div', { class: 'price-table' }, rows),
    rule.tiers.length < 10 ? h('button', { type: 'button', class: 'btn btn-quiet btn-sm mt-2', text: 'Agregar precio por volumen', on: { click: () => {
      const last = rule.tiers[rule.tiers.length - 1];
      rule.tiers.push({ from: (Number(last?.from) || 0) + 10, unitPrice: rule.unitPrice ?? 0 });
      changed({ rerender: true });
    } } }) : null);
}

function pricingSettings() {
  const pricing = state.config.pricing;
  pricing.tax ??= { mode: 'FINAL' };
  pricing.rounding ??= { scale: 0 };
  const taxFields = h('div', { class: 'field-grid cols-2', hidden: pricing.tax.mode !== 'ADDED' },
    field('Porcentaje', percentInput(pricing.tax.rate, (v) => { pricing.tax.rate = v; changed(); })),
    field('Nombre', textInput(pricing.tax.label, (v) => { pricing.tax.label = v || undefined; changed(); }, { maxlength: 40, placeholder: 'IVA' })));
  return h('div', { class: 'settings-block' },
    h('h3', { text: 'Ajustes del precio' }),
    h('div', { class: 'field-grid cols-2' },
      field('Precio mínimo (opcional)', moneyInput(pricing.minimumTotal, (v) => { pricing.minimumTotal = v ?? undefined; changed(); }, { 'data-optional': 'true' }),
        'Si el cálculo da menos, se cobra este importe.'),
      field('Redondeo del total', select([['0', 'Pesos enteros'], ['2', 'Con centavos']], String(pricing.rounding.scale ?? 0),
        (v) => { pricing.rounding.scale = Number(v); changed(); }))),
    field('Impuestos', select([['FINAL', 'Mis precios son finales'], ['EXCLUDED', 'Mis precios no incluyen impuestos (se avisa al cliente)'], ['ADDED', 'Sumar un impuesto al total']],
      pricing.tax.mode, (mode) => { pricing.tax.mode = mode; taxFields.hidden = mode !== 'ADDED'; changed(); }),
    'La cotización es una estimación: no reemplaza una factura.'),
    taxFields);
}

// ------------------------------------------------------------------ Apariencia

const PALETTE = [['#0071DB', 'Azul'], ['#0A7A5C', 'Verde'], ['#B42318', 'Rojo'], ['#6941C6', 'Violeta'], ['#B54708', 'Naranja'], ['#08131F', 'Negro']];

function contrastWithWhite(hex) {
  const c = (i) => { const v = parseInt(hex.slice(i, i + 2), 16) / 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
  return 1.05 / (0.2126 * c(1) + 0.7152 * c(3) + 0.0722 * c(5) + 0.05);
}

function renderAppearance() {
  const message = h('div', { class: 'form-text', role: 'status' });
  const setColor = (hex) => {
    if (contrastWithWhite(hex) < 4.5) {
      message.textContent = 'Ese color es muy claro: el texto de los botones no se leería. Elegí uno más oscuro.';
      message.classList.add('text-danger');
      return false;
    }
    message.textContent = '';
    message.classList.remove('text-danger');
    state.appearance.accentColor = hex.toUpperCase();
    changed();
    return true;
  };
  const name = fid('accent');
  const swatches = h('div', { class: 'swatches', role: 'radiogroup', 'aria-label': 'Color principal' },
    PALETTE.map(([hex, label]) => {
      const input = h('input', { type: 'radio', name, value: hex, 'aria-label': label, checked: state.appearance.accentColor === hex,
        on: { change: () => setColor(hex) } });
      const swatch = h('span');
      swatch.style.background = hex;
      return h('label', { class: 'swatch' }, input, swatch);
    }),
    h('label', { class: 'd-inline-flex align-items-center gap-2 ms-2' }, 'Otro:',
      h('input', { type: 'color', value: state.appearance.accentColor, 'aria-label': 'Elegir otro color',
        on: { change: (e) => { if (!setColor(e.target.value)) e.target.value = state.appearance.accentColor; } } })));
  const logo = state.profile?.logoUrl
    ? h('img', { src: state.profile.logoUrl, alt: 'Logo actual', width: 56, height: 56, class: 'rounded border bg-white' })
    : h('p', { class: 'form-text m-0' }, 'Todavía no subiste un logo. ', h('a', { href: '/app/negocio', text: 'Subilo desde Mi negocio' }), '.');
  return [
    h('p', { class: 'lead-text', text: 'El color de los botones y si se muestra tu logo. El resto del diseño está pensado para leerse bien en el celular.' }),
    state.customBranding === false
      ? h('p', { class: 'notice notice-warn' }, 'En el plan Gratis tus cotizadores usan el color de Climind Cotiza y muestran "Creado con Climind Cotiza". El color que elijas se aplica cuando pases a un plan pago. ',
        h('a', { href: '/app/plan', text: 'Ver planes' }))
      : null,
    h('div', { class: 'field-grid' },
      h('div', {}, h('span', { class: 'form-label d-block', text: 'Color principal' }), swatches, message),
      h('div', { class: 'form-check' },
        h('input', { class: 'form-check-input', type: 'checkbox', id: 'show-logo', checked: state.appearance.showLogo,
          on: { change: (e) => { state.appearance.showLogo = e.target.checked; changed(); } } }),
        h('label', { class: 'form-check-label', for: 'show-logo', text: 'Mostrar el logo del negocio' })),
      logo),
  ];
}

// ------------------------------------------------------------------ Publicación

function renderPublication() {
  const { calc } = state;
  const commercial = state.config.commercial;
  const publishIssues = state.dirty ? [] : (calc.publishIssues || []);
  const url = calc.publicUrl;
  const live = calc.status === 'PUBLICADO' && !accountClosing;
  const shareWhatsapp = `https://wa.me/?text=${encodeURIComponent(`Calculá tu presupuesto de ${calc.name} en segundos: ${url}`)}`;
  const copy = h('button', { type: 'button', class: 'btn btn-quiet', text: 'Copiar enlace', on: { click: async () => {
    try {
      await navigator.clipboard.writeText(url);
      copy.textContent = 'Enlace copiado';
    } catch {
      copy.textContent = 'No se pudo copiar';
    }
  } } });
  const versions = h('ul', { class: 'version-list' }, h('li', { text: 'Cargando…' }));
  api.get(`/api/v1/calculators/${calculatorId}/versions`).then((list) => {
    versions.replaceChildren(...(list.length ? list.map((v) => h('li', { text: `Versión ${v.versionNo}: ${formatDateTime(v.publishedAt)}${v.current ? ' (la que ven tus clientes)' : ''}` }))
      : [h('li', { text: 'Todavía no publicaste ninguna versión.' })]));
  }).catch(() => versions.replaceChildren(h('li', { text: 'No pudimos cargar el historial.' })));

  const actions = [];
  if (calc.status === 'PUBLICADO') actions.push(actionButton('Pausar', 'pause', 'Pausar cotizador', 'Mientras esté pausado, tus clientes no van a poder cotizar con este enlace.'));
  if (calc.status === 'PAUSADO') actions.push(actionButton('Reactivar', 'resume'));
  if (calc.status === 'ARCHIVADO') actions.push(actionButton('Restaurar', 'restore'));
  else actions.push(actionButton('Archivar', 'archive', 'Archivar cotizador', 'Deja de estar disponible y se oculta de tu lista. No se borra nada y lo podés restaurar.'));
  actions.push(h('button', { type: 'button', class: 'btn btn-quiet', text: 'Duplicar', on: { click: (e) => withBusy(e.currentTarget, async () => {
    try {
      const copyCalc = await api.post(`/api/v1/calculators/${calculatorId}/duplicate`);
      window.location.assign(`/app/editor?id=${copyCalc.id}`);
    } catch (error) { showNotice(notice, error.message); }
  }) } }));

  return [
    h('p', { class: 'lead-text', text: 'Lo que ve tu cliente es siempre la última versión publicada. Podés seguir editando sin afectarla hasta que publiques de nuevo.' }),
    publishIssues.length ? h('div', { class: 'mb-3' }, h('h3', { class: 'h6', text: 'Para poder publicar, falta:' }),
      h('ul', { class: 'checklist' }, publishIssues.map((i) => h('li', {}, `${i.message} `,
        h('button', { type: 'button', text: 'Ir a corregir', on: { click: () => selectTab(sectionTab(i.section)) } }))))) : null,
    state.dirty ? h('p', { class: 'notice notice-warn', text: 'Tenés cambios sin guardar. Se guardan al publicar.' }) : null,
    h('div', { class: 'settings-block mt-0 pt-0 border-0' },
      h('h3', { text: 'Enlace del cotizador' }),
      h('div', { class: 'url-box' }, h('code', { text: url }), copy),
      live
        ? h('div', { class: 'share-row' },
          h('a', { class: 'btn btn-quiet', href: url, target: '_blank', rel: 'noopener', text: 'Abrir la página' }),
          h('a', { class: 'btn btn-whatsapp', href: shareWhatsapp, target: '_blank', rel: 'noopener', text: 'Compartir por WhatsApp' }))
        : accountClosing && calc.status === 'PUBLICADO'
          ? h('p', { class: 'notice notice-warn m-0' }, 'Tu cuenta tiene la baja programada: el enlace no funciona para tus clientes hasta que la canceles. ',
            h('a', { href: '/app/cuenta', text: 'Cancelar la baja' }))
          : h('p', { class: 'form-text m-0', text: calc.status === 'PAUSADO'
          ? 'El cotizador está pausado: quien abra el enlace ve un aviso y tus datos de contacto.'
          : 'El enlace empieza a funcionar cuando publiques el cotizador.' })),
    live ? h('div', { class: 'settings-block qr-block' },
      h('h3', { text: 'Código QR' }),
      h('div', { class: 'qr-row' },
        h('img', { src: `/api/v1/calculators/${calculatorId}/qr.svg?v=${encodeURIComponent(calc.slug)}`, alt: `Código QR del enlace ${url}`, width: 148, height: 148, class: 'qr-img' }),
        h('div', {},
          h('p', { class: 'form-text mt-0', text: 'Para imprimir en tu local, folletos o tarjetas. Al escanearlo se abre este cotizador.' }),
          h('a', { class: 'btn btn-quiet', href: `/api/v1/calculators/${calculatorId}/qr.png`, download: `qr-${calc.slug}.png`, text: 'Descargar QR (PNG)' })))) : null,
    h('div', { class: 'settings-block' },
      h('h3', { text: 'Textos para el cliente' }),
      field('Aclaración', h('textarea', { class: 'form-control', rows: 2, maxlength: 500, value: commercial.disclaimer ?? '',
        on: { input: (e) => { commercial.disclaimer = e.target.value || undefined; changed(); } } }),
      'Aparece debajo del total. Recomendamos aclarar que es una estimación.'),
      h('div', { class: 'field-grid cols-2' },
        field('Vigencia (días)', numberInput(commercial.validityDays, (n) => { commercial.validityDays = n ?? undefined; changed(); }),
          'Cuánto tiempo respetás el precio estimado.')),
      field('Cuándo pedir los datos del cliente', select([['AFTER_ESTIMATE', 'Después de mostrar el precio (recomendado)'], ['BEFORE_ESTIMATE', 'Antes de mostrar el precio']],
        commercial.leadCapture || 'AFTER_ESTIMATE', (v) => { commercial.leadCapture = v; changed(); }),
      'Pedir los datos antes reduce las cotizaciones: muchos clientes prefieren ver el precio primero.'),
      field('Mensaje de WhatsApp', h('textarea', { class: 'form-control', rows: 3, maxlength: 500, value: commercial.whatsappTemplate ?? '',
        on: { input: (e) => { commercial.whatsappTemplate = e.target.value || undefined; changed(); } } }),
      'El mensaje que te manda el cliente. Podés usar {servicio}, {total} y {codigo}.')),
    h('div', { class: 'settings-block' }, h('h3', { text: 'Estado' }), h('div', { class: 'd-flex flex-wrap gap-2' }, actions)),
    h('div', { class: 'settings-block' }, h('h3', { text: 'Versiones publicadas' }), versions),
  ];
}

function actionButton(label, action, confirmTitle, confirmText) {
  return h('button', { type: 'button', class: 'btn btn-quiet', text: label, on: { click: async (e) => {
    if (confirmTitle && !(await confirmDialog(confirmTitle, confirmText, label))) return;
    if (state.dirty && action !== 'archive' && !(await save())) return;
    await withBusy(e.target, async () => {
      try {
        loadDetail(await api.post(`/api/v1/calculators/${calculatorId}/${action}`));
        showNotice(notice, { pause: 'Pausaste el cotizador.', resume: 'El cotizador está activo de nuevo.', archive: 'Archivaste el cotizador.', restore: 'Restauraste el cotizador.' }[action], 'ok');
      } catch (error) { showNotice(notice, error.message, 'error', planLink(error)); }
    });
  } } });
}

// ------------------------------------------------------------------ guardar y publicar

function payload() {
  return {
    name: state.info.name.trim(),
    description: state.info.description.trim() || null,
    slug: state.info.slug,
    appearance: state.appearance,
    config: state.config,
    lockVersion: state.calc.lockVersion,
  };
}

async function save() {
  hideNotice(notice);
  try {
    const detail = await api.put(`/api/v1/calculators/${calculatorId}`, payload());
    loadDetail(detail);
    return true;
  } catch (error) {
    handleSaveError(error);
    return false;
  }
}

function handleSaveError(error) {
  if (!(error instanceof ApiError)) return showNotice(notice, error.message);
  if (error.code === 'CONFIG_INVALID') {
    state.serverIssues = error.issues || [];
    updateTabBadges();
    const first = state.serverIssues[0];
    if (first) {
      if (first.item) state.expanded.add(first.section === 'pricing' ? `rule:${first.item}` : first.item);
      selectTab(sectionTab(first.section));
    }
    showNotice(notice, `${error.message} ${state.serverIssues.map((i) => i.message).slice(0, 3).join(' ')}`);
  } else if (error.code === 'STALE_VERSION') {
    showNotice(notice, error.message);
    notice.append(' ', h('button', { type: 'button', class: 'btn btn-link p-0 align-baseline', text: 'Recargar', on: { click: () => window.location.reload() } }));
  } else if (error.fieldErrors?.length) {
    const fieldError = error.fieldErrors[0];
    selectTab(fieldError.field.startsWith('appearance') ? 'apariencia' : 'info');
    showNotice(notice, error.fieldErrors.map((f) => f.message).join(' '));
  } else {
    showNotice(notice, error.message);
  }
  notice.focus();
}

$('save-button').addEventListener('click', (e) => withBusy(e.currentTarget, async () => {
  if (await save()) showNotice(notice, 'Guardaste los cambios. Tus clientes siguen viendo la versión publicada hasta que publiques.', 'ok');
}));

$('publish-button').addEventListener('click', (e) => withBusy(e.currentTarget, async () => {
  if (state.dirty && !(await save())) return;
  try {
    const result = await api.post(`/api/v1/calculators/${calculatorId}/publish`);
    loadDetail(result.calculator);
    showNotice(notice, result.newVersion
      ? `Publicaste la versión ${result.versionNo}. Las cotizaciones anteriores conservan sus precios.`
      : 'No había cambios nuevos: el cotizador sigue publicado con la misma versión.', 'ok');
  } catch (error) {
    if (error.code === 'NOT_PUBLISHABLE') {
      state.calc.publishIssues = error.issues || [];
      updateTabBadges();
      selectTab('publicacion');
      showNotice(notice, error.message);
    } else {
      showNotice(notice, error.message, 'error', planLink(error));
    }
  }
  notice.focus();
}));

window.addEventListener('beforeunload', (e) => { if (state.dirty) e.preventDefault(); });
window.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault();
    if (state.dirty) $('save-button').click();
  }
});

// ------------------------------------------------------------------ vista previa

let previewTimer;
let previewSeq = 0;
let lastVisible = '';

function schedulePreview(delay = 350) {
  renderPreviewShell();
  clearTimeout(previewTimer);
  previewTimer = setTimeout(runPreview, delay);
}

function renderPreviewShell() {
  const surface = $('preview-surface');
  surface.style.setProperty('--accent', state.appearance.accentColor);
  const head = $('preview-head');
  const logo = state.appearance.showLogo && state.profile?.logoUrl
    ? h('img', { src: state.profile.logoUrl, alt: '' }) : null;
  head.replaceChildren(...[logo, h('div', {}, h('h3', { text: state.info.name || 'Cotizador sin nombre' }),
    state.info.description ? h('p', { text: state.info.description }) : null)].filter(Boolean));
  renderForm();
}

function renderForm(issues = {}) {
  lastVisible = visibleQuestions(state.config, state.answers).join('|');
  renderQuoteForm($('preview-form'), state.config, state.answers, () => {
    // Se vuelve a pintar solo si cambió qué preguntas se ven (así no se pierde el foco al escribir).
    if (visibleQuestions(state.config, state.answers).join('|') !== lastVisible) renderForm();
    clearTimeout(previewTimer);
    previewTimer = setTimeout(runPreview, 300);
  }, { issues });
}

async function runPreview() {
  const seq = ++previewSeq;
  const issuesBox = $('preview-issues');
  try {
    const response = await api.post(`/api/v1/calculators/${calculatorId}/preview`, {
      answers: visibleAnswers(state.config, state.answers),
      config: state.config,
    });
    if (seq !== previewSeq) return; // llegó una respuesta vieja
    if (response.configIssues.length) {
      issuesBox.hidden = false;
      issuesBox.textContent = `Para ver la vista previa, revisá: ${response.configIssues.slice(0, 3).map((i) => i.message).join(' ')}`;
      renderTicket($('preview-ticket'), null, ticketMeta());
      return;
    }
    issuesBox.hidden = true;
    const answerIssues = Object.fromEntries((response.result.issues || [])
      .filter((i) => i.question && i.message !== 'Respondé esta pregunta.' && i.message !== 'Pregunta desconocida.')
      .map((i) => [i.question, i.message]));
    if (Object.keys(answerIssues).length) renderForm(answerIssues);
    renderTicket($('preview-ticket'), response.result, ticketMeta());
  } catch (error) {
    if (seq !== previewSeq) return;
    issuesBox.hidden = false;
    issuesBox.textContent = error.message;
  }
}

function ticketMeta() {
  return {
    businessName: state.profile?.businessName,
    title: 'Estimación',
    disclaimer: state.config.commercial?.disclaimer,
    validityDays: state.config.commercial?.validityDays,
    scale: state.config.pricing?.rounding?.scale,
  };
}

$('reset-answers').addEventListener('click', () => { state.answers = {}; schedulePreview(0); });

document.querySelectorAll('.view-toggle button').forEach((button) => button.addEventListener('click', () => {
  $('builder').dataset.view = button.dataset.view;
  document.querySelectorAll('.view-toggle button').forEach((b) => b.setAttribute('aria-pressed', String(b === button)));
}));

// ------------------------------------------------------------------ inicio

try {
  const [detail, profile, billing] = await Promise.all([
    api.get(`/api/v1/calculators/${calculatorId}`),
    api.get('/api/v1/business/profile').catch(() => null),
    api.get('/api/v1/billing').catch(() => null),
  ]);
  state.profile = profile;
  state.customBranding = billing ? billing.current.customBranding : true;
  document.title = `${detail.name}: editar — Climind Cotiza`;
  loadDetail(detail);
  const params = new URLSearchParams(window.location.search);
  if (TABS.includes(params.get('tab'))) selectTab(params.get('tab'));
  if (params.get('vista') === 'previa') document.querySelector('.view-toggle [data-view="preview"]').click();
} catch (error) {
  showNotice(notice, error.status === 404 ? 'No encontramos ese cotizador.' : error.message);
  $('builder').hidden = true;
  document.querySelector('.view-toggle').hidden = true;
  $('save-button').hidden = true;
  $('publish-button').hidden = true;
}
