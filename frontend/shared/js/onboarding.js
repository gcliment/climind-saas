// Asistente de configuración inicial (5 pasos, salteable).
import { api } from './api.js';
import { moneyText, parseMoney, parseNumber } from './calculator-model.js';
import { initPanel } from './panel.js';
import { clearFieldErrors, formData, hideNotice, showFieldErrors, showNotice, withBusy } from './ui.js';

const me = await initPanel({ current: '/app/onboarding' });
const notice = document.getElementById('wizard-notice');
const SKIP_KEY = `climind.onboardingSkipped.${me.user.id}`;

const state = { step: 1, objective: 'SERVICIOS', templates: [], profile: null, calc: null, calcTemplate: null };

function goTo(step) {
  hideNotice(notice);
  state.step = step;
  for (let i = 1; i <= 5; i++) document.getElementById(`step-${i}`).hidden = i !== step;
  document.querySelectorAll('#progress li').forEach((li, i) => {
    li.className = i + 1 < step ? 'done' : i + 1 === step ? 'current' : '';
    if (i + 1 === step) li.setAttribute('aria-current', 'step'); else li.removeAttribute('aria-current');
  });
  document.querySelector(`#step-${step} h1`)?.focus?.();
  window.scrollTo({ top: 0 });
}

document.querySelectorAll('[data-back]').forEach((b) => b.addEventListener('click', () => goTo(state.step - 1)));
document.querySelectorAll('[data-skip]').forEach((a) => a.addEventListener('click', () => {
  try { localStorage.setItem(SKIP_KEY, '1'); } catch { /* preferencia local, no crítica */ }
}));

// ------------------------------------------------------------------ paso 1: negocio

const step1 = document.getElementById('step-1');
const logoPreview = document.getElementById('logo-preview');

function showLogo(url) {
  if (!url) { logoPreview.textContent = 'Sin logo'; return; }
  const img = document.createElement('img');
  img.src = url;
  img.alt = 'Logo del negocio';
  logoPreview.replaceChildren(img);
}

document.getElementById('logo-file').addEventListener('change', async (event) => {
  const file = event.target.files[0];
  if (!file) return;
  hideNotice(notice);
  if (file.size > 2 * 1024 * 1024) { showNotice(notice, 'El logo puede pesar hasta 2 MB.'); return; }
  const body = new FormData();
  body.append('file', file);
  logoPreview.textContent = 'Subiendo…';
  try {
    const data = await api.upload('/api/v1/business/logo', body);
    state.profile = { ...state.profile, logoUrl: data.logoUrl };
    showLogo(data.logoUrl);
  } catch (error) {
    showLogo(state.profile?.logoUrl);
    showNotice(notice, error.fieldErrors?.[0]?.message || error.message);
  } finally {
    event.target.value = '';
  }
});

step1.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearFieldErrors(step1);
  hideNotice(notice);
  const data = formData(step1);
  if (!data.businessName) return showFieldErrors(step1, [{ field: 'businessName', message: 'Ingresá el nombre de tu negocio.' }]);
  await withBusy(step1.querySelector('[type="submit"]'), async () => {
    try {
      state.profile = await api.put('/api/v1/business/profile', { ...data, website: state.profile?.website, description: state.profile?.description });
      document.querySelectorAll('[data-business-name]').forEach((el) => { el.textContent = state.profile.businessName; });
      goTo(2);
    } catch (error) {
      if (error.fieldErrors?.length) showFieldErrors(step1, error.fieldErrors);
      else showNotice(notice, error.message);
    }
  });
});

// ------------------------------------------------------------------ paso 2: objetivo

document.getElementById('step-2').addEventListener('submit', (event) => {
  event.preventDefault();
  state.objective = new FormData(event.target).get('objective');
  renderTemplates();
  goTo(3);
});

// ------------------------------------------------------------------ paso 3: plantilla

function renderTemplates() {
  const industry = state.profile?.industry;
  const score = (t) => (t.industry === industry ? 2 : 0) + (t.objectives.includes(state.objective) ? 1 : 0);
  const sorted = [...state.templates].sort((a, b) => score(b) - score(a));
  const cards = sorted.map((t, i) => card(t.code, t.name, t.description, i === 0, score(t) >= 2 ? 'Recomendada para tu rubro' : score(t) === 1 ? 'Sirve para lo que querés cotizar' : null));
  cards.push(card('', 'Empezar de cero', 'Un cotizador vacío: armás tus preguntas y precios en el editor.', false, null));
  document.getElementById('template-list').replaceChildren(...cards);
}

function card(value, title, description, checked, tag) {
  const label = document.createElement('label');
  label.className = 'choice-card';
  const input = document.createElement('input');
  input.type = 'radio';
  input.name = 'template';
  input.value = value;
  input.checked = checked;
  const span = document.createElement('span');
  const strong = document.createElement('strong');
  strong.textContent = title;
  const small = document.createElement('small');
  small.textContent = description;
  span.append(strong, small);
  if (tag) {
    const em = document.createElement('em');
    em.textContent = tag;
    span.append(em);
  }
  label.append(input, span);
  return label;
}

document.getElementById('step-3').addEventListener('submit', async (event) => {
  event.preventDefault();
  const code = new FormData(event.target).get('template') || '';
  await withBusy(event.target.querySelector('[type="submit"]'), async () => {
    try {
      if (!code) {
        const blank = await api.post('/api/v1/calculators', { name: 'Mi cotizador' });
        window.location.assign(`/app/editor?id=${blank.id}&tab=preguntas`);
        return;
      }
      if (!state.calc || state.calcTemplate !== code) {
        const template = state.templates.find((t) => t.code === code);
        state.calc = await api.post('/api/v1/calculators', { name: template.calculatorName, templateCode: code });
        state.calcTemplate = code;
      }
      renderPrices();
      goTo(4);
    } catch (error) {
      showNotice(notice, error.message);
    }
  });
});

// ------------------------------------------------------------------ paso 4: precios

const priceInputs = [];

function questionLabel(config, key) {
  return config.questions.find((q) => q.key === key)?.label || '';
}

function moneyField(labelText, value, apply) {
  const id = `price-${priceInputs.length}`;
  const wrapper = document.createElement('div');
  wrapper.className = 'price-row';
  const label = document.createElement('label');
  label.htmlFor = id;
  label.textContent = labelText;
  const box = document.createElement('div');
  box.className = 'money-input';
  const input = document.createElement('input');
  input.className = 'form-control';
  input.id = id;
  input.inputMode = 'decimal';
  input.value = moneyText(value);
  box.append(input);
  wrapper.append(label, box);
  priceInputs.push({ input, apply, parse: parseMoney, message: 'Ingresá un importe válido, por ejemplo 15.000.' });
  return wrapper;
}

function percentField(labelText, value, apply) {
  const id = `price-${priceInputs.length}`;
  const wrapper = document.createElement('div');
  wrapper.className = 'price-row';
  const label = document.createElement('label');
  label.htmlFor = id;
  label.textContent = labelText;
  const box = document.createElement('div');
  box.className = 'percent-input';
  const input = document.createElement('input');
  input.className = 'form-control';
  input.id = id;
  input.inputMode = 'decimal';
  input.value = value ?? '';
  box.append(input);
  wrapper.append(label, box);
  priceInputs.push({ input, apply, parse: parseNumber, message: 'Ingresá un porcentaje válido.' });
  return wrapper;
}

function group(title, subtitle, ...rows) {
  const section = document.createElement('div');
  section.className = 'price-group';
  const h3 = document.createElement('h3');
  h3.textContent = title;
  section.append(h3);
  if (subtitle) {
    const p = document.createElement('p');
    p.className = 'form-text mt-n1 mb-2';
    p.textContent = subtitle;
    section.append(p);
  }
  const table = document.createElement('div');
  table.className = 'price-table';
  table.append(...rows);
  section.append(table);
  return section;
}

/** "Precio por unidad: se multiplica por la respuesta a «¿Cuántas personas asistirán?»". */
const perUnit = (question) => `Precio por unidad: se multiplica por la respuesta a «${question?.label ?? 'la cantidad'}».`;

function renderPrices() {
  priceInputs.length = 0;
  const config = state.calc.config;
  const groups = [];
  for (const rule of config.pricing.rules) {
    const q = config.questions.find((x) => x.key === rule.question);
    switch (rule.type) {
      case 'OPTION_PRICE':
      case 'OPTION_PER_UNIT': {
        const qty = config.questions.find((x) => x.key === rule.quantityQuestion);
        groups.push(group(rule.label || questionLabel(config, rule.question),
          rule.type === 'OPTION_PER_UNIT' ? perUnit(qty) : null,
          ...q.options.filter((o) => rule.prices[o.key] !== undefined)
            .map((o) => moneyField(o.label, rule.prices[o.key], (v) => { rule.prices[o.key] = v; }))));
        break;
      }
      case 'PER_UNIT':
        groups.push(group(rule.label || q?.label, perUnit(q),
          moneyField('Precio por unidad', rule.unitPrice, (v) => { rule.unitPrice = v; })));
        break;
      case 'FIXED':
        groups.push(group(rule.label, null, moneyField('Importe', rule.amount, (v) => { rule.amount = v; })));
        break;
      case 'SURCHARGE_PERCENT':
      case 'DISCOUNT_PERCENT':
        groups.push(group(rule.label, null, percentField(rule.type === 'SURCHARGE_PERCENT' ? 'Recargo' : 'Descuento', rule.percent, (v) => { rule.percent = v; })));
        break;
      default:
        break;
    }
  }
  if (config.pricing.minimumTotal !== undefined) {
    groups.push(group('Precio mínimo', 'Si el cálculo da menos, se cobra este importe.',
      moneyField('Nunca menos de', config.pricing.minimumTotal, (v) => { config.pricing.minimumTotal = v; })));
  }
  document.getElementById('price-fields').replaceChildren(...groups);
}

document.getElementById('step-4').addEventListener('submit', async (event) => {
  event.preventDefault();
  hideNotice(notice);
  let firstInvalid = null;
  for (const field of priceInputs) {
    const value = field.parse(field.input.value);
    const valid = value !== null && value >= 0;
    field.input.classList.toggle('is-invalid', !valid);
    field.input.toggleAttribute('aria-invalid', !valid);
    if (valid) field.apply(value);
    else firstInvalid ??= field;
  }
  if (firstInvalid) {
    showNotice(notice, firstInvalid.message);
    firstInvalid.input.focus();
    return;
  }
  const calc = state.calc;
  await withBusy(event.target.querySelector('[type="submit"]'), async () => {
    try {
      state.calc = await api.put(`/api/v1/calculators/${calc.id}`, {
        name: calc.name, description: calc.description, slug: calc.slug, appearance: calc.appearance,
        config: calc.config, lockVersion: calc.lockVersion,
      });
      document.getElementById('summary-name').textContent = state.calc.name;
      document.getElementById('summary-url').textContent = state.calc.publicUrl;
      goTo(5);
    } catch (error) {
      const issue = error.issues?.[0]?.message;
      showNotice(notice, issue ? `${error.message} ${issue}` : error.message);
    }
  });
});

// ------------------------------------------------------------------ paso 5: publicar

document.getElementById('publish-button').addEventListener('click', (event) => withBusy(event.currentTarget, async () => {
  try {
    const result = await api.post(`/api/v1/calculators/${state.calc.id}/publish`);
    await api.post('/api/v1/business/onboarding/complete');
    document.getElementById('publish-panel').hidden = true;
    const done = document.getElementById('done-panel');
    done.hidden = false;
    document.getElementById('done-url').textContent = result.calculator.publicUrl;
    document.getElementById('view-calculator').href = `/app/editor?id=${state.calc.id}&tab=publicacion`;
    document.getElementById('open-public').href = result.calculator.publicUrl;
    document.getElementById('share-whatsapp').href = `https://wa.me/?text=${encodeURIComponent(`Calculá tu presupuesto de ${result.calculator.name} en segundos: ${result.calculator.publicUrl}`)}`;
    document.querySelectorAll('#progress li').forEach((li) => { li.className = 'done'; });
    done.focus();
  } catch (error) {
    const issues = (error.issues || []).map((i) => i.message).join(' ');
    showNotice(notice, issues ? `${error.message} ${issues}` : error.message);
  }
}));

document.getElementById('copy-url').addEventListener('click', async (event) => {
  try {
    await navigator.clipboard.writeText(document.getElementById('done-url').textContent);
    event.target.textContent = 'Enlace copiado';
  } catch {
    event.target.textContent = 'No se pudo copiar';
  }
});

// ------------------------------------------------------------------ inicio

try {
  const [profile, industries, templates] = await Promise.all([
    api.get('/api/v1/business/profile'),
    api.get('/api/v1/catalog/industries'),
    api.get('/api/v1/templates'),
  ]);
  state.profile = profile;
  state.templates = templates;
  const select = step1.elements.namedItem('industry');
  industries.forEach(({ code, label }) => select.add(new Option(label, code)));
  for (const name of ['businessName', 'industry', 'phone', 'contactEmail']) {
    step1.elements.namedItem(name).value = profile[name] ?? '';
  }
  step1.elements.namedItem('whatsapp').value = profile.whatsapp ? `+${profile.whatsapp}` : '';
  showLogo(profile.logoUrl);
  goTo(1);
} catch (error) {
  showNotice(notice, error.message);
}
