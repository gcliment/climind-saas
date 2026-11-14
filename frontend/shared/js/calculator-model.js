// Modelo del constructor: tipos en lenguaje del usuario, altas de preguntas y reglas, y utilidades para
// mantener la configuración consistente (las referencias se validan además en el servidor).

export const QUESTION_TYPES = {
  SINGLE_CHOICE: { label: 'Opción única', hint: 'El cliente elige una opción. Ej.: tipo de servicio.' },
  MULTI_CHOICE: { label: 'Varias opciones', hint: 'Puede marcar varias. Ej.: servicios adicionales.' },
  QUANTITY: { label: 'Cantidad', hint: 'Un número entero. Ej.: invitados, unidades.' },
  NUMBER: { label: 'Número o medida', hint: 'Con unidad y decimales. Ej.: metros cuadrados.' },
  YES_NO: { label: 'Sí / No', hint: 'Una pregunta de sí o no. Ej.: ¿necesitás instalación?' },
  TEXT: { label: 'Texto libre', hint: 'Comentarios del cliente. No cambia el precio.' },
  DATE: { label: 'Fecha', hint: 'Fecha que pide el cliente. No cambia el precio.' },
};

export const RULE_TYPES = {
  OPTION_PRICE: { label: 'Precio según la opción', hint: 'Cada opción de una pregunta tiene su precio.' },
  PER_UNIT: { label: 'Precio por unidad', hint: 'Cantidad o medida × precio. Con precios por volumen.' },
  OPTION_PER_UNIT: { label: 'Precio por unidad según opción', hint: 'Ej.: invitados × precio del menú elegido.' },
  FIXED: { label: 'Importe fijo', hint: 'Un monto que se suma siempre o si se cumple algo.' },
  SURCHARGE_PERCENT: { label: 'Recargo %', hint: 'Suma un porcentaje. Ej.: urgencia.' },
  DISCOUNT_PERCENT: { label: 'Descuento %', hint: 'Resta un porcentaje. Ej.: promoción.' },
};

export const OPERATORS = {
  EQUALS: 'es', NOT_EQUALS: 'no es', INCLUDES: 'incluye', IS_TRUE: 'es sí', IS_FALSE: 'es no',
  GT: 'es mayor que', GTE: 'es mayor o igual que', LT: 'es menor que', LTE: 'es menor o igual que',
};

/** Operadores válidos según el tipo de la pregunta (mismas reglas que el validador del servidor). */
export function operatorsFor(type) {
  switch (type) {
    case 'SINGLE_CHOICE': return ['EQUALS', 'NOT_EQUALS'];
    case 'MULTI_CHOICE': return ['INCLUDES'];
    case 'YES_NO': return ['IS_TRUE', 'IS_FALSE'];
    case 'QUANTITY':
    case 'NUMBER': return ['GTE', 'GT', 'LTE', 'LT', 'EQUALS', 'NOT_EQUALS'];
    default: return [];
  }
}

export const isChoice = (type) => type === 'SINGLE_CHOICE' || type === 'MULTI_CHOICE';
export const isNumeric = (type) => type === 'QUANTITY' || type === 'NUMBER';
export const affectsPrice = (type) => type !== 'TEXT' && type !== 'DATE';

/** Clave estable a partir de un texto: minúsculas, sin acentos, con guiones bajos. No cambia después. */
export function keyFrom(text, existing, fallback) {
  let base = String(text || '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 30);
  if (!/^[a-z]/.test(base)) base = fallback + (base ? `_${base}` : '');
  base = base.slice(0, 36);
  let key = base;
  for (let i = 2; existing.has(key); i++) key = `${base}_${i}`;
  return key;
}

export function newQuestion(config, type) {
  const keys = new Set((config.questions || []).map((q) => q.key));
  const question = { key: keyFrom('', keys, `pregunta_${keys.size + 1}`), type, label: '', required: true };
  if (isChoice(type)) {
    question.options = [{ key: 'opcion_1', label: '' }, { key: 'opcion_2', label: '' }];
  }
  if (type === 'QUANTITY') Object.assign(question, { min: 1, max: 100 });
  if (type === 'NUMBER') Object.assign(question, { min: 1, max: 1000, unit: '' });
  if (type === 'TEXT') Object.assign(question, { required: false, maxLength: 500 });
  if (type === 'DATE') question.required = false;
  return question;
}

export function newOption(question) {
  const keys = new Set((question.options || []).map((o) => o.key));
  return { key: keyFrom('', keys, `opcion_${keys.size + 1}`), label: '' };
}

export function newRule(config, type) {
  const ids = new Set((config.pricing?.rules || []).map((r) => r.id));
  const rule = { type, id: keyFrom('', ids, `regla_${ids.size + 1}`), label: '' };
  const questions = config.questions || [];
  const firstChoice = questions.find((q) => isChoice(q.type));
  const firstSingle = questions.find((q) => q.type === 'SINGLE_CHOICE');
  const firstNumeric = questions.find((q) => isNumeric(q.type));
  switch (type) {
    case 'FIXED': rule.amount = 0; break;
    case 'OPTION_PRICE': rule.question = firstChoice?.key ?? ''; rule.prices = {}; break;
    case 'PER_UNIT': rule.question = firstNumeric?.key ?? ''; rule.unitPrice = 0; rule.tiers = []; break;
    case 'OPTION_PER_UNIT':
      rule.question = firstSingle?.key ?? '';
      rule.quantityQuestion = firstNumeric?.key ?? '';
      rule.prices = {};
      break;
    case 'SURCHARGE_PERCENT':
    case 'DISCOUNT_PERCENT': rule.percent = 10; break;
    default: break;
  }
  return rule;
}

/** Reglas y condiciones que dependen de una pregunta (para avisar antes de borrarla). */
export function dependentsOf(config, key) {
  const rules = (config.pricing?.rules || []).filter((r) => r.question === key || r.quantityQuestion === key);
  const conditions = [
    ...(config.pricing?.rules || []).filter((r) => (r.when || []).some((c) => c.question === key)),
    ...(config.questions || []).filter((q) => (q.visibleIf || []).some((c) => c.question === key)),
  ];
  return { rules, conditions };
}

/** Borra una pregunta y todo lo que depende de ella: reglas que la usan y condiciones que la nombran. */
export function removeQuestion(config, key) {
  config.questions = config.questions.filter((q) => q.key !== key);
  config.pricing.rules = config.pricing.rules
    .filter((r) => r.question !== key && r.quantityQuestion !== key)
    .map((r) => ({ ...r, when: (r.when || []).filter((c) => c.question !== key) }));
  for (const q of config.questions) q.visibleIf = (q.visibleIf || []).filter((c) => c.question !== key);
}

/** Al borrar una opción se limpian sus precios y las condiciones que la usaban. */
export function removeOption(config, question, optionKey) {
  question.options = question.options.filter((o) => o.key !== optionKey);
  for (const r of config.pricing.rules) {
    if (r.prices && r.question === question.key) delete r.prices[optionKey];
    r.when = (r.when || []).filter((c) => !(c.question === question.key && c.value === optionKey));
  }
  for (const q of config.questions) {
    q.visibleIf = (q.visibleIf || []).filter((c) => !(c.question === question.key && c.value === optionKey));
  }
}

/**
 * Cambiar el tipo de una pregunta invalida las reglas y condiciones que la usaban: se quitan para no dejar
 * referencias rotas. Se conservan la clave, el texto, la ayuda y su propia condición de visibilidad.
 */
export function changeQuestionType(config, question, type) {
  const key = question.key;
  config.pricing.rules = config.pricing.rules
    .filter((r) => r.question !== key && r.quantityQuestion !== key)
    .map((r) => ({ ...r, when: (r.when || []).filter((c) => c.question !== key) }));
  for (const q of config.questions) {
    if (q !== question) q.visibleIf = (q.visibleIf || []).filter((c) => c.question !== key);
  }
  const fresh = newQuestion(config, type);
  const keep = { key, label: question.label, help: question.help, visibleIf: question.visibleIf };
  for (const prop of Object.keys(question)) delete question[prop];
  Object.assign(question, fresh, keep);
}

export function move(list, index, delta) {
  const target = index + delta;
  if (target < 0 || target >= list.length) return;
  [list[index], list[target]] = [list[target], list[index]];
}

/**
 * Importe en formato argentino → número. "50.000" = 50000; "1.234,56" = 1234.56; "1500" = 1500.
 * Devuelve null si no es un importe válido.
 */
export function parseMoney(text) {
  const value = String(text ?? '').trim().replace(/\s|\$/g, '');
  if (value === '') return null;
  if (!/^\d{1,3}(\.\d{3})*(,\d{1,2})?$|^\d+(,\d{1,2})?$|^\d+\.\d{1,2}$/.test(value)) return null;
  // "1500.5" (punto decimal con 1-2 decimales y sin miles) también se acepta.
  if (/^\d+\.\d{1,2}$/.test(value)) return Number(value);
  return Number(value.replace(/\./g, '').replace(',', '.'));
}

/** Número → texto para editar ("50.000", "1.234,5"). */
export function moneyText(value) {
  if (value === null || value === undefined || value === '') return '';
  return new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 }).format(Number(value));
}

export function parseNumber(text) {
  const value = String(text ?? '').trim().replace(',', '.');
  if (value === '' || Number.isNaN(Number(value))) return null;
  return Number(value);
}

export function questionLabel(config, key) {
  const q = (config.questions || []).find((x) => x.key === key);
  return q ? (q.label || 'Pregunta sin texto') : 'Pregunta eliminada';
}
