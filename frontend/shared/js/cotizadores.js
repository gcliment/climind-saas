// Listado de cotizadores y alta (desde plantilla o de cero).
import { api } from './api.js';
import { formatDateTime } from './format.js';
import { initPanel } from './panel.js';
import { clearFieldErrors, hideNotice, showFieldErrors, showNotice, withBusy } from './ui.js';

await initPanel({ current: '/app/cotizadores' });

const list = document.getElementById('calc-list');
const notice = document.getElementById('list-notice');
const toggleArchived = document.getElementById('toggle-archived');
let showArchived = false;

const STATUS = { BORRADOR: 'Borrador', PUBLICADO: 'Publicado', PAUSADO: 'Pausado', ARCHIVADO: 'Archivado' };

function row(calc) {
  const article = document.createElement('article');
  article.className = 'calc-row';
  const info = document.createElement('div');
  const title = document.createElement('h2');
  const link = document.createElement('a');
  link.href = `/app/editor?id=${calc.id}`;
  link.textContent = calc.name;
  title.append(link);
  const meta = document.createElement('p');
  meta.className = 'calc-meta m-0';
  meta.textContent = calc.publishedVersionNo
    ? `Versión ${calc.publishedVersionNo} publicada${calc.hasUnpublishedChanges ? ', con cambios sin publicar' : ''}. Editado el ${formatDateTime(calc.updatedAt)}.`
    : `Sin publicar. Editado el ${formatDateTime(calc.updatedAt)}.`;
  info.append(title, meta);
  const badge = document.createElement('span');
  badge.className = `status-badge status-${calc.status}`;
  badge.textContent = STATUS[calc.status];
  const edit = document.createElement('a');
  edit.className = 'btn btn-quiet';
  edit.href = link.href;
  edit.textContent = 'Editar';
  edit.setAttribute('aria-label', `Editar ${calc.name}`);
  const actions = document.createElement('div');
  actions.className = 'calc-actions';
  if (calc.status === 'PUBLICADO') {
    const open = document.createElement('a');
    open.className = 'btn btn-quiet';
    open.href = calc.publicUrl;
    open.target = '_blank';
    open.rel = 'noopener';
    open.textContent = 'Ver página';
    open.setAttribute('aria-label', `Ver la página pública de ${calc.name}`);
    actions.append(open);
  }
  actions.append(edit);
  article.append(info, badge, actions);
  return article;
}

async function load() {
  try {
    const calcs = await api.get(`/api/v1/calculators${showArchived ? '?archived=true' : ''}`);
    if (calcs.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.innerHTML = '<h2>Todavía no tenés cotizadores</h2><p>Empezá con una plantilla de tu rubro: ya trae preguntas y precios de ejemplo para que los ajustes.</p>';
      const button = document.createElement('button');
      button.className = 'btn btn-primary';
      button.type = 'button';
      button.textContent = 'Crear mi primer cotizador';
      button.addEventListener('click', openDialog);
      empty.append(button);
      list.replaceChildren(empty);
    } else {
      list.replaceChildren(...calcs.map(row));
    }
  } catch (error) {
    showNotice(notice, error.message);
  }
}

toggleArchived.addEventListener('click', () => {
  showArchived = !showArchived;
  toggleArchived.setAttribute('aria-pressed', String(showArchived));
  toggleArchived.textContent = showArchived ? 'Ocultar archivados' : 'Ver archivados';
  load();
});

// ------------------------------------------------------------------ alta

const dialog = document.getElementById('new-dialog');
const form = document.getElementById('new-form');
const choices = document.getElementById('template-choices');
const nameInput = document.getElementById('new-name');
let templates = null;

function choice(value, title, description, checked) {
  const label = document.createElement('label');
  label.className = 'choice-card';
  const input = document.createElement('input');
  input.type = 'radio';
  input.name = 'templateCode';
  input.value = value;
  input.checked = checked;
  const span = document.createElement('span');
  const strong = document.createElement('strong');
  strong.textContent = title;
  const small = document.createElement('small');
  small.textContent = description;
  span.append(strong, small);
  label.append(input, span);
  input.addEventListener('change', () => {
    const template = templates.find((t) => t.code === value);
    if (template && (!nameInput.value || nameInput.dataset.auto === 'true')) {
      nameInput.value = template.calculatorName;
      nameInput.dataset.auto = 'true';
    }
  });
  return label;
}

async function openDialog() {
  hideNotice(document.getElementById('new-notice'));
  clearFieldErrors(form);
  if (!templates) {
    try {
      templates = await api.get('/api/v1/templates');
    } catch (error) {
      showNotice(notice, error.message);
      return;
    }
  }
  choices.replaceChildren(
    ...templates.map((t, i) => choice(t.code, t.name, t.description, i === 0)),
    choice('', 'Empezar de cero', 'Un cotizador vacío para armar tus propias preguntas y precios.', false),
  );
  nameInput.value = templates[0]?.calculatorName ?? '';
  nameInput.dataset.auto = 'true';
  dialog.showModal();
}

nameInput.addEventListener('input', () => { nameInput.dataset.auto = 'false'; });
document.getElementById('new-button').addEventListener('click', openDialog);
document.getElementById('new-cancel').addEventListener('click', () => dialog.close());

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearFieldErrors(form);
  const name = nameInput.value.trim();
  if (!name) {
    showFieldErrors(form, [{ field: 'name', message: 'Poné un nombre al cotizador.' }]);
    return;
  }
  const templateCode = form.querySelector('input[name="templateCode"]:checked')?.value || null;
  await withBusy(form.querySelector('[type="submit"]'), async () => {
    try {
      const created = await api.post('/api/v1/calculators', { name, templateCode });
      window.location.assign(`/app/editor?id=${created.id}${templateCode ? '&tab=precios' : '&tab=preguntas'}`);
    } catch (error) {
      showNotice(document.getElementById('new-notice'), error.message);
    }
  });
});

if (new URLSearchParams(window.location.search).get('nuevo') === '1') openDialog();
load();
