// Piezas compartidas por la página pública del cotizador (/c/...) y la de una cotización emitida (/cotizacion/...).
// Sin sesión ni CSRF: la API pública no usa cookies de autenticación. La cotización emitida se lee con el
// header X-Quote-Token; el token vive en el fragmento (#t=...) de la URL, que nunca llega al servidor.
import { ApiError } from './api.js';
import { formatPhone } from './format.js';
import { renderTicket } from './quote-form.js';

export const TOKEN_HEADER = 'X-Quote-Token';

/** fetch a /api/v1/public/... con el formato de errores de la API. */
export async function publicApi(method, path, { body, token } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers[TOKEN_HEADER] = token;
  let response;
  try {
    response = await fetch(path, { method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, { code: 'NETWORK', message: 'No hay conexión. Revisá tu internet y probá de nuevo.' });
  }
  if (response.status === 204) return null;
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(response.status, data);
  return data;
}

/** Color del negocio en botones y opciones elegidas (CSSOM: compatible con la CSP sin estilos inline). */
export function applyAccent(color) {
  if (/^#[0-9a-fA-F]{6}$/.test(color || '')) document.body.style.setProperty('--accent', color);
}

/** Encabezado con logo y nombre del negocio. */
export function renderBusiness(business) {
  document.getElementById('biz-name').textContent = business.name;
  const logo = document.getElementById('biz-logo');
  if (business.logoUrl) {
    logo.src = business.logoUrl;
    logo.alt = `Logo de ${business.name}`;
    logo.hidden = false;
  }
}

/** Datos de contacto del negocio (solo los que cargó). */
export function renderContact(business) {
  const items = [];
  const add = (label, text, href) => {
    const li = document.createElement('li');
    const strong = document.createElement('span');
    strong.className = 'pub-contact-label';
    strong.textContent = label;
    const a = document.createElement(href ? 'a' : 'span');
    a.textContent = text;
    if (href) {
      a.href = href;
      if (href.startsWith('http')) { a.target = '_blank'; a.rel = 'noopener'; }
    }
    li.append(strong, a);
    items.push(li);
  };
  if (business.whatsapp) add('WhatsApp', formatPhone(business.whatsapp), `https://wa.me/${business.whatsapp}`);
  if (business.phone) add('Teléfono', business.phone, `tel:${business.phone.replace(/[^0-9+]/g, '')}`);
  if (business.email) add('Email', business.email, `mailto:${business.email}`);
  if (business.website) add('Web', business.website.replace(/^https?:\/\//, ''), business.website);
  if (business.description) {
    const p = document.createElement('li');
    p.className = 'pub-contact-about';
    p.textContent = business.description;
    items.unshift(p);
  }
  if (!items.length) return;
  document.getElementById('contact-list').replaceChildren(...items);
  document.getElementById('contact').hidden = false;
}

/** Ticket de una cotización emitida (snapshot del servidor). */
export function renderIssuedTicket(container, quote) {
  renderTicket(container, quote.result, {
    businessName: quote.business.name,
    title: quote.calculatorName,
    disclaimer: quote.disclaimer,
    validUntil: quote.validUntil,
    scale: quote.priceScale,
    code: quote.code,
    createdAt: quote.createdAt,
  });
}

/** Enlace permanente de la cotización (el token va en el fragmento). */
export function permalink(quote, token) {
  return `${window.location.origin}/cotizacion/${quote.code}#t=${token}`;
}

/** Descarga el PDF (con el token en un header, no en la URL). */
export async function downloadPdf(code, token, button) {
  const original = button.textContent;
  button.disabled = true;
  button.textContent = 'Preparando PDF…';
  try {
    const response = await fetch(`/api/v1/public/quotes/${encodeURIComponent(code)}/pdf`, { headers: { [TOKEN_HEADER]: token } });
    if (!response.ok) throw new Error();
    const url = URL.createObjectURL(await response.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = `cotizacion-${code}.pdf`;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    button.textContent = original;
  } catch {
    button.textContent = 'No se pudo descargar. Reintentar';
  } finally {
    button.disabled = false;
  }
}

/** Registra el clic en WhatsApp sin demorar la apertura del chat. */
export function trackWhatsapp(code, token) {
  fetch(`/api/v1/public/quotes/${encodeURIComponent(code)}/whatsapp`, {
    method: 'POST', keepalive: true, headers: { [TOKEN_HEADER]: token },
  }).catch(() => {});
}

/** Lee el formulario de contacto. */
export function contactPayload(fieldset) {
  const value = (name) => fieldset.querySelector(`[name="${name}"]`).value.trim();
  return {
    name: value('name'),
    whatsapp: value('whatsapp'),
    email: value('email') || null,
    comments: value('comments') || null,
    consent: fieldset.querySelector('[name="consent"]').checked,
    website: fieldset.querySelector('[name="website"]').value,
  };
}

/** Marca los errores del servidor en el formulario de contacto. Devuelve true si marcó alguno. */
export function showContactErrors(fieldset, error) {
  fieldset.querySelectorAll('.is-invalid').forEach((el) => { el.classList.remove('is-invalid'); el.removeAttribute('aria-invalid'); });
  fieldset.querySelectorAll('.invalid-feedback').forEach((el) => el.remove());
  let first = null;
  for (const { field, message } of error.fieldErrors || []) {
    const name = field.replace(/^contact\./, '');
    const input = fieldset.querySelector(`[name="${name}"]`);
    if (!input) continue;
    input.classList.add('is-invalid');
    input.setAttribute('aria-invalid', 'true');
    const feedback = document.createElement('div');
    feedback.className = 'invalid-feedback d-block';
    feedback.id = `${input.id}-error`;
    feedback.textContent = message;
    (input.closest('.form-check') || input).after(feedback);
    input.setAttribute('aria-describedby', feedback.id);
    first ??= input;
  }
  first?.focus();
  return first !== null;
}
