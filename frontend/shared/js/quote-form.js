// Formulario de cotización tal como lo ve el cliente final, más el ticket con el desglose.
// Lo usan la vista previa del constructor y (Fase 3) la página pública. El importe SIEMPRE lo calcula el
// servidor: acá solo se muestran las preguntas, se decide cuáles se ven y se pinta el resultado.
import { formatDateTime, formatLocalDate, formatMoney, formatNumber } from './format.js';

const NUMERIC = new Set(['QUANTITY', 'NUMBER']);

/** Igual que el backend: una condición sobre una pregunta sin responder es falsa. */
function conditionMatches(condition, answer, type) {
  if (answer === undefined || answer === null || answer === '' || (Array.isArray(answer) && answer.length === 0)) return false;
  const n = Number(String(answer).replace(',', '.'));
  const v = Number(condition.value);
  const same = NUMERIC.has(type) ? n === v : String(answer) === condition.value;
  switch (condition.op) {
    case 'EQUALS': return same;
    case 'NOT_EQUALS': return !same;
    case 'INCLUDES': return Array.isArray(answer) && answer.includes(condition.value);
    case 'IS_TRUE': return answer === true;
    case 'IS_FALSE': return answer === false;
    case 'GT': return n > v;
    case 'GTE': return n >= v;
    case 'LT': return n < v;
    case 'LTE': return n <= v;
    default: return false;
  }
}

/** Claves de las preguntas visibles según las respuestas actuales (en orden). */
export function visibleQuestions(config, answers) {
  const visible = [];
  const effective = {};
  const types = Object.fromEntries((config.questions || []).map((q) => [q.key, q.type]));
  for (const q of config.questions || []) {
    const ok = (q.visibleIf || []).every((c) => conditionMatches(c, effective[c.question], types[c.question]));
    if (!ok) continue;
    visible.push(q.key);
    effective[q.key] = answers[q.key];
  }
  return visible;
}

/** Respuestas solo de preguntas visibles (lo que se envía al servidor). */
export function visibleAnswers(config, answers) {
  const keys = new Set(visibleQuestions(config, answers));
  return Object.fromEntries(Object.entries(answers).filter(([k, v]) => keys.has(k) && v !== '' && v !== undefined));
}

let uid = 0;
const nextId = (prefix) => `${prefix}-${++uid}`;

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value === true ? '' : String(value));
  }
  node.append(...children.filter(Boolean));
  return node;
}

function questionHead(q, inputId, legend = false) {
  const tag = legend ? 'legend' : 'label';
  const label = el(tag, { class: 'qf-label', for: legend ? undefined : inputId });
  label.append(q.label || 'Pregunta sin texto');
  label.append(el('span', { class: 'qf-req', text: q.required ? '' : ' (opcional)' }));
  return label;
}

function helpText(q, id) {
  return q.help ? el('p', { class: 'qf-help', id, text: q.help }) : ''; // '' y no null: append(null) escribe "null"
}

/**
 * Pinta el formulario. `onChange(answers)` se llama con cada cambio.
 * @param {HTMLElement} container
 * @param {object} config configuración del cotizador
 * @param {object} answers estado de respuestas (se muta)
 * @param {(answers: object) => void} onChange
 * @param {{issues?: Record<string,string>}} [options] errores por pregunta (del servidor)
 */
export function renderQuoteForm(container, config, answers, onChange, options = {}) {
  const issues = options.issues || {};
  const visible = new Set(visibleQuestions(config, answers));
  const fields = [];

  for (const q of config.questions || []) {
    if (!visible.has(q.key)) continue;
    const id = nextId(`q-${q.key}`);
    const helpId = q.help ? `${id}-help` : undefined;
    const errorId = issues[q.key] ? `${id}-error` : undefined;
    const describedBy = [helpId, errorId].filter(Boolean).join(' ') || undefined;
    const set = (value) => { answers[q.key] = value; onChange(answers); };
    let field;

    switch (q.type) {
      case 'SINGLE_CHOICE':
      case 'YES_NO': {
        const options = q.type === 'YES_NO'
          ? [{ key: true, label: 'Sí' }, { key: false, label: 'No' }]
          : (q.options || []);
        field = el('fieldset', { class: 'qf-field', 'aria-describedby': describedBy });
        field.append(questionHead(q, id, true), helpText(q, helpId));
        const group = el('div', { class: `qf-options${q.type === 'YES_NO' ? ' qf-options-inline' : ''}` });
        for (const option of options) {
          const input = el('input', { type: 'radio', name: id, value: String(option.key), required: q.required });
          input.checked = answers[q.key] === option.key;
          input.addEventListener('change', () => set(option.key));
          group.append(el('label', { class: 'qf-option' }, input, el('span', { text: option.label || 'Opción sin texto' })));
        }
        field.append(group);
        break;
      }
      case 'MULTI_CHOICE': {
        field = el('fieldset', { class: 'qf-field', 'aria-describedby': describedBy });
        field.append(questionHead(q, id, true), helpText(q, helpId));
        const group = el('div', { class: 'qf-options' });
        const current = new Set(answers[q.key] || []);
        for (const option of q.options || []) {
          const input = el('input', { type: 'checkbox', value: option.key });
          input.checked = current.has(option.key);
          input.addEventListener('change', () => {
            input.checked ? current.add(option.key) : current.delete(option.key);
            set((q.options || []).map((o) => o.key).filter((k) => current.has(k)));
          });
          group.append(el('label', { class: 'qf-option' }, input, el('span', { text: option.label || 'Opción sin texto' })));
        }
        field.append(group);
        break;
      }
      case 'QUANTITY':
      case 'NUMBER': {
        field = el('div', { class: 'qf-field' });
        field.append(questionHead(q, id), helpText(q, helpId));
        const integer = q.type === 'QUANTITY' || q.integer;
        const input = el('input', {
          class: 'form-control qf-number', id, type: 'text', inputmode: integer ? 'numeric' : 'decimal',
          autocomplete: 'off', 'aria-describedby': describedBy, 'aria-invalid': issues[q.key] ? 'true' : undefined,
        });
        input.value = answers[q.key] ?? '';
        input.addEventListener('input', () => set(input.value.trim()));
        const row = el('div', { class: 'qf-number-row' });
        if (q.type === 'QUANTITY') {
          const step = (delta) => {
            const min = Number(q.min ?? 0);
            const max = Number(q.max ?? 1000000);
            const current = Number(String(input.value).replace(',', '.')) || (delta > 0 ? min - 1 : min);
            const next = Math.min(max, Math.max(min, Math.round(current + delta)));
            input.value = String(next);
            set(input.value);
          };
          const minus = el('button', { type: 'button', class: 'qf-step', 'aria-label': `Restar uno a ${q.label}`, text: '−' });
          const plus = el('button', { type: 'button', class: 'qf-step', 'aria-label': `Sumar uno a ${q.label}`, text: '+' });
          minus.addEventListener('click', () => step(-1));
          plus.addEventListener('click', () => step(1));
          row.append(minus, input, plus);
        } else {
          row.append(input);
        }
        if (q.unit) row.append(el('span', { class: 'qf-unit', text: q.unit }));
        field.append(row);
        if (q.max !== undefined && q.max !== null) {
          field.append(el('p', { class: 'qf-range', text: `Entre ${formatNumber(Number(q.min ?? 0), 4)} y ${formatNumber(Number(q.max), 4)}` }));
        }
        break;
      }
      case 'TEXT': {
        field = el('div', { class: 'qf-field' });
        field.append(questionHead(q, id), helpText(q, helpId));
        const input = el('textarea', { class: 'form-control', id, rows: 3, maxlength: q.maxLength || 1000, 'aria-describedby': describedBy });
        input.value = answers[q.key] ?? '';
        input.addEventListener('input', () => set(input.value));
        field.append(input);
        break;
      }
      case 'DATE': {
        field = el('div', { class: 'qf-field' });
        field.append(questionHead(q, id), helpText(q, helpId));
        const input = el('input', { class: 'form-control', id, type: 'date', 'aria-describedby': describedBy });
        input.min = new Date().toISOString().slice(0, 10);
        input.value = answers[q.key] ?? '';
        input.addEventListener('change', () => set(input.value));
        field.append(input);
        break;
      }
      default:
        continue;
    }
    field.dataset.key = q.key;
    if (issues[q.key]) field.append(el('p', { class: 'qf-error', id: errorId, text: issues[q.key] }));
    fields.push(field);
  }
  container.replaceChildren(...fields);
  if (fields.length === 0) {
    container.append(el('p', { class: 'qf-empty', text: 'Todavía no hay preguntas. Agregalas en la pestaña Preguntas.' }));
  }
}

function lineDetail(line) {
  if (line.quantity !== undefined && line.unitPrice !== undefined) {
    const unit = line.unit ? ` ${line.unit}` : '';
    return `${formatNumber(Number(line.quantity), 4)}${unit} × ${formatMoney(line.unitPrice, { decimals: 0 })}`;
  }
  if (line.percent !== undefined && (line.kind === 'SURCHARGE' || line.kind === 'DISCOUNT' || line.kind === 'TAX')) {
    return `${line.kind === 'DISCOUNT' ? '−' : '+'}${formatNumber(Number(line.percent), 2)} %`;
  }
  return null;
}

function money(amount, decimals) {
  const n = Number(amount);
  return n < 0 ? `−${formatMoney(-n, { decimals })}` : formatMoney(n, { decimals });
}

/**
 * Pinta el ticket con el resultado del servidor (PriceBreakdown).
 * @param {HTMLElement} container
 * @param {object|null} result
 * @param {{businessName?: string, title?: string, disclaimer?: string, validityDays?: number, validUntil?: string,
 *          scale?: number, code?: string, createdAt?: string}} meta  validUntil (cotización emitida) reemplaza a validityDays
 */
export function renderTicket(container, result, meta = {}) {
  const decimals = meta.scale === 2 ? 2 : 0;
  const head = el('div', { class: 'ticket-head' },
    el('strong', { text: meta.businessName || 'Tu negocio' }),
    el('span', { text: meta.title || 'Estimación' }));
  const lines = el('ul', { class: 'ticket-lines' });
  for (const line of result?.lines || []) {
    const detail = lineDetail(line);
    const name = el('span', { class: 'ticket-line-name' }, line.label || 'Concepto');
    if (detail) name.append(el('small', { text: detail }));
    lines.append(el('li', { class: line.kind === 'DISCOUNT' ? 'is-discount' : undefined }, name,
      el('b', { text: money(line.amount, 2 === decimals ? 2 : 0) })));
  }
  if (!result || result.lines.length === 0) {
    lines.append(el('li', { class: 'ticket-empty' }, el('span', { text: 'Respondé las preguntas para ver el desglose.' })));
  }
  const total = el('div', { class: 'ticket-total' },
    el('span', { text: 'Total estimado' }),
    el('output', { class: 'num', text: result ? money(result.total, decimals) : '—' }));
  const notes = [];
  if (result?.taxMode === 'EXCLUDED') notes.push(result.taxLabel || 'Precios sin impuestos');
  if (meta.validUntil) notes.push(`Precio válido hasta el ${formatLocalDate(meta.validUntil)}.`);
  else if (meta.validityDays) notes.push(`Válido por ${meta.validityDays} días.`);
  if (meta.disclaimer) notes.push(meta.disclaimer);
  if (result && !result.complete) notes.unshift('Faltan respuestas obligatorias: el total puede cambiar.');
  const stamp = meta.code
    ? el('p', { class: 'ticket-stamp' }, el('span', { text: 'Código ' }), el('strong', { text: meta.code }),
      meta.createdAt ? el('span', { text: ` · ${formatDateTime(meta.createdAt)}` }) : '')
    : '';
  container.replaceChildren(head, lines, total, ...notes.map((n) => el('p', { class: 'ticket-note', text: n })), stamp);
}
