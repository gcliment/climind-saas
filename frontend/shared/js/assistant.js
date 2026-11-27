// Asistente de ayuda: botón flotante «¿Dudas?» y una conversación que responde sobre la app. Se monta en el panel
// y en el centro de ayuda (con sesión). La conversación se guarda en esta pestaña (sessionStorage) para seguirla
// al cambiar de sección; el servidor no la guarda.
import { api } from './api.js';

const SUGGESTIONS = [
  '¿Cómo cargo los precios de mis servicios?',
  '¿Cómo veo cómo queda mi cotizador?',
  'Mi enlace dice que no está disponible',
  '¿Cómo instalo la app en el celular?',
];
const MAX_CHARS = 2000;
const MAX_STORED = 20;

let mounted = null;

function el(tag, attrs = {}, ...children) {
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

/** Enlaces que el asistente puede ofrecer: páginas propias y los canales de soporte. */
function safeHref(raw) {
  const url = raw.replace(/[.,;:)]+$/, '');
  if (/^\/(ayuda|app|precios|legal)(\/[\w\-/]*)?$/.test(url)) return url;
  if (/^https:\/\/wa\.me\/\d+$/.test(url)) return url;
  return null;
}

/** Texto del asistente → nodos seguros: párrafos, listas, **negrita** y enlaces permitidos. Nunca innerHTML. */
function renderRich(text) {
  const frag = document.createDocumentFragment();
  let list = null;
  const inline = (line, parent) => {
    const parts = line.split(/(\*\*[^*]+\*\*|https:\/\/wa\.me\/\d+|\/(?:ayuda|app|precios|legal)[\w\-/]*)/g);
    for (const part of parts) {
      if (!part) continue;
      if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
        parent.append(el('strong', { text: part.slice(2, -2) }));
      } else if (safeHref(part)) {
        const href = safeHref(part);
        const external = href.startsWith('https://');
        parent.append(el('a', { href, text: external ? 'WhatsApp' : part, target: external ? '_blank' : null, rel: external ? 'noopener' : null }));
        parent.append(document.createTextNode(part.slice(href.length)));
      } else {
        parent.append(document.createTextNode(part));
      }
    }
  };
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    const item = line.match(/^(?:[-•*]|\d+[.)])\s+(.*)$/);
    if (item) {
      const ordered = /^\d/.test(line);
      if (!list || (ordered ? list.tagName !== 'OL' : list.tagName !== 'UL')) {
        list = el(ordered ? 'ol' : 'ul');
        frag.append(list);
      }
      const li = el('li');
      inline(item[1], li);
      list.append(li);
      continue;
    }
    list = null;
    if (!line) continue;
    const p = el('p');
    inline(line, p);
    frag.append(p);
  }
  return frag;
}

function storageKey(userId) {
  return `climind.assistant.${userId}`;
}

function load(userId) {
  try {
    const data = JSON.parse(sessionStorage.getItem(storageKey(userId)) || '[]');
    return Array.isArray(data) ? data.filter((m) => m && (m.role === 'user' || m.role === 'assistant') && typeof m.content === 'string') : [];
  } catch {
    return [];
  }
}

function save(userId, messages) {
  try { sessionStorage.setItem(storageKey(userId), JSON.stringify(messages.slice(-MAX_STORED))); } catch { /* sin almacenamiento */ }
}

/**
 * Monta el asistente (una sola vez por página).
 * @param {{user: {id: string, firstName: string}}} me respuesta de /api/v1/auth/me
 */
export function mountAssistant(me) {
  if (mounted) return mounted;
  if (!document.querySelector('link[href="/shared/css/assistant.css"]')) {
    document.head.append(el('link', { rel: 'stylesheet', href: '/shared/css/assistant.css' }));
  }
  const userId = me.user.id;
  let messages = load(userId);
  let busy = false;

  const launcher = el('button', { type: 'button', class: 'assistant-launcher', 'aria-haspopup': 'dialog', 'aria-expanded': 'false' },
    el('span', { class: 'assistant-launcher-dot', 'aria-hidden': 'true' }), '¿Dudas?');
  const subtitle = el('p', { class: 'assistant-sub', text: 'Te ayudo a usar Climind Cotiza' });
  const log = el('div', { class: 'assistant-log', role: 'log', 'aria-live': 'polite', 'aria-label': 'Conversación con el asistente' });
  const input = el('textarea', { class: 'form-control assistant-input', rows: '1', maxlength: String(MAX_CHARS), placeholder: 'Escribí tu pregunta…', 'aria-label': 'Tu pregunta' });
  const send = el('button', { type: 'submit', class: 'btn btn-primary assistant-send', 'aria-label': 'Enviar' }, '➤');
  const form = el('form', { class: 'assistant-form', novalidate: true }, input, send);
  const resetButton = el('button', { type: 'button', class: 'assistant-icon-btn', text: 'Nueva', title: 'Empezar una conversación nueva' });
  const close = el('button', { type: 'button', class: 'assistant-icon-btn', 'aria-label': 'Cerrar el asistente', text: '✕' });
  const panel = el('section', { class: 'assistant-panel', role: 'dialog', 'aria-modal': 'false', 'aria-labelledby': 'assistant-title', hidden: true },
    el('header', { class: 'assistant-head' },
      el('div', { class: 'assistant-avatar', 'aria-hidden': 'true' }, el('img', { src: '/shared/img/cotiza-mark.svg', alt: '', width: '26', height: '26' })),
      el('div', { class: 'assistant-titles' }, el('h2', { id: 'assistant-title', text: 'Asistente de Cotiza' }), subtitle),
      resetButton, close),
    log, form,
    el('p', { class: 'assistant-foot', text: 'Puede equivocarse: ante la duda, revisá la ayuda o escribinos.' }));
  document.body.append(launcher, panel);
  document.body.classList.add('has-assistant');

  function bubble(role, content, extras = {}) {
    const body = el('div', { class: 'assistant-bubble-body' });
    if (role === 'assistant') body.append(renderRich(content)); else body.textContent = content;
    const node = el('div', { class: `assistant-bubble from-${role}${extras.error ? ' is-error' : ''}` }, body);
    if (extras.links?.length) {
      node.append(el('div', { class: 'assistant-links' }, extras.links.map((l) => el('a', { href: safeHref(l.url) || '/ayuda', class: 'assistant-chip', text: l.title }))));
    }
    if (extras.retry) {
      node.append(el('button', { type: 'button', class: 'assistant-chip', text: 'Reintentar', on: { click: () => { node.remove(); ask(null); } } }));
    }
    return node;
  }

  function welcome() {
    const first = (me.user.firstName || '').split(' ')[0];
    return el('div', { class: 'assistant-welcome' },
      bubble('assistant', `¡Hola${first ? `, ${first}` : ''}! Soy el asistente de Climind Cotiza. Preguntame cómo hacer algo en la app o cómo armar tu cotizador con tus precios.`),
      el('div', { class: 'assistant-suggestions' }, SUGGESTIONS.map((s) => el('button', { type: 'button', class: 'assistant-chip', text: s, on: { click: () => ask(s) } }))));
  }

  function render() {
    log.replaceChildren(welcome(), ...messages.map((m) => bubble(m.role, m.content, { links: m.links })));
    log.scrollTop = log.scrollHeight;
  }

  async function ask(text) {
    if (busy) return;
    const question = text === null ? null : (text ?? '').trim().slice(0, MAX_CHARS);
    if (question === '') return;
    if (question !== null) {
      messages.push({ role: 'user', content: question });
      save(userId, messages);
      log.append(bubble('user', question));
    }
    busy = true;
    send.disabled = true;
    const typing = el('div', { class: 'assistant-bubble from-assistant is-typing', 'aria-label': 'El asistente está escribiendo' }, el('span'), el('span'), el('span'));
    log.append(typing);
    log.scrollTop = log.scrollHeight;
    try {
      const payload = messages.map(({ role, content }) => ({ role, content }));
      const reply = await api.post('/api/v1/assistant/chat', { messages: payload });
      typing.remove();
      const message = { role: 'assistant', content: reply.reply, links: reply.links };
      messages.push(message);
      save(userId, messages);
      log.append(bubble('assistant', message.content, { links: message.links }));
    } catch (error) {
      typing.remove();
      const limit = error.status === 429;
      log.append(bubble('assistant', limit ? error.message : 'No pude responder en este momento. Revisá tu conexión y probá de nuevo.', { error: true, retry: !limit }));
    } finally {
      busy = false;
      send.disabled = false;
      log.scrollTop = log.scrollHeight;
    }
  }

  function open() {
    panel.hidden = false;
    launcher.setAttribute('aria-expanded', 'true');
    document.body.classList.add('assistant-open');
    render();
    setTimeout(() => input.focus(), 50);
  }
  function hide() {
    panel.hidden = true;
    launcher.setAttribute('aria-expanded', 'false');
    document.body.classList.remove('assistant-open');
    launcher.focus();
  }

  launcher.addEventListener('click', () => (panel.hidden ? open() : hide()));
  close.addEventListener('click', hide);
  resetButton.addEventListener('click', () => {
    messages = [];
    save(userId, messages);
    render();
    input.focus();
  });
  panel.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') hide();
  });
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const text = input.value;
    input.value = '';
    input.style.height = '';
    ask(text);
  });
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      form.requestSubmit();
    }
  });
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = `${Math.min(input.scrollHeight, 140)}px`;
  });
  document.querySelectorAll('[data-open-assistant]').forEach((b) => b.addEventListener('click', open));

  // Si responde con IA o con las preguntas frecuentes: se consulta una vez por pestaña.
  const showStatus = (ai) => {
    subtitle.textContent = ai ? 'Te ayudo a usar Climind Cotiza' : 'Respuestas de las preguntas frecuentes';
  };
  let cachedStatus = null;
  try { cachedStatus = sessionStorage.getItem('climind.assistant.ai'); } catch { /* sin almacenamiento */ }
  if (cachedStatus !== null) {
    showStatus(cachedStatus === '1');
  } else {
    api.get('/api/v1/assistant/status').then((status) => {
      showStatus(Boolean(status.ai));
      try { sessionStorage.setItem('climind.assistant.ai', status.ai ? '1' : '0'); } catch { /* sin almacenamiento */ }
    }).catch(() => { /* el chat funciona igual */ });
  }

  mounted = { open, close: hide };
  return mounted;
}
