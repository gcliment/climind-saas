// Página pública de un cotizador: /c/{negocio}/{cotizador}.
// El cliente responde, el SERVIDOR calcula y guarda la cotización; después puede dejar sus datos,
// escribir por WhatsApp o descargar el PDF.
import { renderQuoteForm, renderTicket, visibleAnswers, visibleQuestions } from './quote-form.js';
import {
  applyAccent, contactPayload, downloadPdf, permalink, publicApi, renderBusiness, renderContact, renderIssuedTicket,
  showContactErrors, trackWhatsapp,
} from './public-quote.js';
import { hideNotice, showNotice, withBusy } from './ui.js';

const $ = (id) => document.getElementById(id);
const match = window.location.pathname.match(/^\/c\/([a-z0-9-]+)\/([a-z0-9-]+)\/?$/);

const state = {
  calc: null,
  answers: {},
  issues: {},
  quote: null,      // última cotización emitida
  token: null,
  answersKey: null, // respuestas con las que se emitió (para detectar cambios)
};

const contactFields = $('contact-fields');
const form = $('quote-form');
const notice = $('form-notice');

function fail(message) {
  document.body.removeAttribute('data-state');
  $('biz-name').textContent = 'Climind Cotiza';
  showNotice($('page-error'), message);
}

function answersKey() {
  return JSON.stringify(visibleAnswers(state.calc, state.answers));
}

let lastVisible = '';
let snapshot = {};

function renderForm() {
  lastVisible = visibleQuestions(state.calc, state.answers).join('|');
  snapshot = { ...state.answers };
  renderQuoteForm($('questions'), state.calc, state.answers, onAnswer, { issues: state.issues });
}

function onAnswer() {
  // Se vuelve a pintar solo si cambió qué preguntas se ven (así no se pierde el foco al escribir).
  if (visibleQuestions(state.calc, state.answers).join('|') !== lastVisible) {
    renderForm();
  } else {
    // El error de una pregunta se borra apenas se corrige la respuesta.
    for (const key of Object.keys(state.issues)) {
      if (JSON.stringify(state.answers[key]) === JSON.stringify(snapshot[key])) continue;
      delete state.issues[key];
      const field = $('questions').querySelector(`[data-key="${CSS.escape(key)}"]`);
      field?.querySelector('.qf-error')?.remove();
      field?.querySelectorAll('[aria-invalid]').forEach((el) => el.removeAttribute('aria-invalid'));
    }
    snapshot = { ...state.answers };
  }
  markStale();
}

/** Si cambian las respuestas después de cotizar, el ticket deja de valer hasta recalcular. */
function markStale() {
  if (!state.quote) return;
  const stale = answersKey() !== state.answersKey;
  $('stale').hidden = !stale;
  $('ticket').classList.toggle('is-stale', stale);
  $('actions').hidden = stale;
  $('calc-button').textContent = stale ? 'Recalcular precio' : 'Ver precio estimado';
  if (state.calc.leadCapture === 'AFTER_ESTIMATE' && !state.quote.request.sent) $('lead-section').hidden = stale;
}

function placeholderTicket() {
  renderTicket($('ticket'), null, {
    businessName: state.calc.business.name,
    title: state.calc.name,
    disclaimer: state.calc.disclaimer,
    validityDays: state.calc.validityDays,
    scale: state.calc.priceScale,
  });
}

function showQuote(quote, token) {
  state.quote = quote;
  state.token = token;
  state.answersKey = answersKey();
  renderIssuedTicket($('ticket'), quote);
  $('ticket').classList.remove('is-stale');
  $('stale').hidden = true;
  $('calc-button').textContent = 'Ver precio estimado';

  const wa = $('whatsapp-link');
  wa.hidden = !quote.whatsappUrl;
  if (quote.whatsappUrl) wa.href = quote.whatsappUrl;
  $('permalink').href = permalink(quote, token);
  $('actions').hidden = false;

  if (quote.request.sent) {
    $('lead-section').hidden = true;
    $('lead-done').hidden = false;
  } else if (state.calc.leadCapture === 'AFTER_ESTIMATE') {
    $('contact-slot-after').append(contactFields);
    contactFields.hidden = false;
    $('lead-section').hidden = false;
  }
  const result = $('result');
  result.classList.add('has-quote');
  if (window.matchMedia('(max-width: 899px)').matches) result.scrollIntoView({ behavior: 'smooth', block: 'start' });
  result.focus({ preventScroll: true });
}

// ------------------------------------------------------------------ cotizar

form.addEventListener('submit', (event) => {
  event.preventDefault();
  hideNotice(notice);
  const contactFirst = state.calc.leadCapture === 'BEFORE_ESTIMATE';
  const body = { answers: visibleAnswers(state.calc, state.answers) };
  if (contactFirst) body.contact = contactPayload(contactFields);
  withBusy($('calc-button'), async () => {
    try {
      const quote = await publicApi('POST', `/api/v1/public/c/${match[1]}/${match[2]}/quotes`, { body });
      state.issues = {};
      renderForm();
      showQuote(quote, quote.token);
      if (contactFirst) showContactErrors(contactFields, {});
    } catch (error) {
      if (error.code === 'INVALID_ANSWERS') {
        state.issues = Object.fromEntries(error.fieldErrors.map((f) => [f.field, f.message]));
        renderForm();
        showNotice(notice, 'Revisá las respuestas marcadas.');
        $('questions').querySelector('.qf-error')?.closest('.qf-field')?.querySelector('input, textarea')?.focus();
      } else if (contactFirst && error.fieldErrors?.length && showContactErrors(contactFields, error)) {
        showNotice(notice, error.message);
      } else {
        showNotice(notice, error.message);
      }
    }
  });
});

// ------------------------------------------------------------------ dejar datos (después del precio)

$('lead-form').addEventListener('submit', (event) => {
  event.preventDefault();
  hideNotice($('lead-notice'));
  withBusy(event.submitter, async () => {
    try {
      const quote = await publicApi('POST', `/api/v1/public/quotes/${state.quote.code}/request`, {
        body: contactPayload(contactFields), token: state.token,
      });
      state.quote = quote;
      $('lead-section').hidden = true;
      const done = $('lead-done');
      done.hidden = false;
      done.focus();
    } catch (error) {
      if (!showContactErrors(contactFields, error)) showNotice($('lead-notice'), error.message);
      else showNotice($('lead-notice'), 'Revisá los datos marcados.');
    }
  });
});

$('whatsapp-link').addEventListener('click', () => trackWhatsapp(state.quote.code, state.token));
$('pdf-button').addEventListener('click', (event) => downloadPdf(state.quote.code, state.token, event.currentTarget));
$('permalink').addEventListener('click', async (event) => {
  event.preventDefault();
  const link = event.currentTarget;
  try {
    await navigator.clipboard.writeText(link.href);
    link.textContent = 'Enlace copiado: guardalo para volver a ver esta cotización';
  } catch {
    window.location.assign(link.href);
  }
});

// ------------------------------------------------------------------ inicio

async function init() {
  if (!match) return fail('Este enlace no corresponde a ningún cotizador. Revisá la dirección.');
  let calc;
  try {
    calc = await publicApi('GET', `/api/v1/public/c/${match[1]}/${match[2]}`);
  } catch (error) {
    return fail(error.status === 404 ? 'No encontramos este cotizador. Puede que el negocio lo haya dado de baja.' : error.message);
  }
  state.calc = calc;
  document.querySelector('.pub-foot').hidden = !calc.branding;
  applyAccent(calc.accentColor);
  renderBusiness(calc.business);
  renderContact(calc.business);
  document.body.removeAttribute('data-state');

  if (!calc.available) {
    $('unavailable').hidden = false;
    return;
  }
  $('calc-name').textContent = calc.name;
  if (calc.description) {
    $('calc-description').textContent = calc.description;
    $('calc-description').hidden = false;
  }
  $('intro').hidden = false;
  $('consent-text').textContent = `Acepto que ${calc.business.name} use estos datos para responder mi consulta.`;
  $('lead-text').textContent = `Dejá tus datos y ${calc.business.name} te escribe para confirmar el presupuesto.`;
  $('lead-done-text').textContent = `${calc.business.name} recibió tus datos y se va a comunicar con vos.`;
  if (calc.leadCapture === 'BEFORE_ESTIMATE') {
    $('contact-slot-before').append(contactFields);
    contactFields.hidden = false;
    $('calc-hint').textContent = 'Completá tus datos para ver el precio. El negocio los usa solo para responderte.';
  }
  renderForm();
  placeholderTicket();
  $('quote-layout').hidden = false;
}

init();
