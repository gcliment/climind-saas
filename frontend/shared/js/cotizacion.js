// Cotización emitida: /cotizacion/{código}#t={token}. Constancia del precio estimado que recibió el cliente.
import { formatLocalDate } from './format.js';
import {
  applyAccent, contactPayload, downloadPdf, publicApi, renderBusiness, renderContact, renderIssuedTicket,
  showContactErrors, trackWhatsapp,
} from './public-quote.js';
import { hideNotice, showNotice, withBusy } from './ui.js';

const $ = (id) => document.getElementById(id);
const code = (window.location.pathname.match(/^\/cotizacion\/(COT-[A-Za-z0-9]{6})\/?$/) || [])[1];
const token = new URLSearchParams(window.location.hash.slice(1)).get('t');

function fail(message) {
  document.body.removeAttribute('data-state');
  $('biz-name').textContent = 'Climind Cotiza';
  showNotice($('page-error'), message);
}

function render(quote) {
  document.querySelector('.pub-foot').hidden = !quote.branding;
  applyAccent(quote.accentColor);
  renderBusiness(quote.business);
  renderContact(quote.business);
  document.title = `Cotización ${quote.code} — ${quote.business.name}`;
  $('quote-title').textContent = quote.calculatorName;
  renderIssuedTicket($('ticket'), quote);
  if (quote.expired) {
    $('expired').textContent = `Esta cotización venció el ${formatLocalDate(quote.validUntil)}. Los precios pueden haber cambiado: hacé una nueva para ver el importe actual.`;
    $('expired').hidden = false;
  }
  const wa = $('whatsapp-link');
  wa.hidden = !quote.whatsappUrl;
  if (quote.whatsappUrl) wa.href = quote.whatsappUrl;
  $('again-link').href = quote.calculatorUrl;
  $('consent-text').textContent = `Acepto que ${quote.business.name} use estos datos para responder mi consulta.`;
  $('lead-text').textContent = `Dejá tus datos y ${quote.business.name} te escribe para confirmar el presupuesto.`;
  $('lead-done-text').textContent = `${quote.business.name} ya tiene tus datos y se va a comunicar con vos.`;
  $('demo-banner').hidden = !quote.demo;
  $('lead-section').hidden = quote.request.sent || quote.demo;
  $('lead-done').hidden = !quote.request.sent;
  $('quote').hidden = false;
  document.body.removeAttribute('data-state');
}

$('whatsapp-link').addEventListener('click', () => trackWhatsapp(code, token));
$('pdf-button').addEventListener('click', (event) => downloadPdf(code, token, event.currentTarget));
$('lead-form').addEventListener('submit', (event) => {
  event.preventDefault();
  hideNotice($('lead-notice'));
  withBusy(event.submitter, async () => {
    try {
      await publicApi('POST', `/api/v1/public/quotes/${code}/request`, { body: contactPayload($('contact-fields')), token });
      $('lead-section').hidden = true;
      $('lead-done').hidden = false;
      $('lead-done').focus();
    } catch (error) {
      const marked = showContactErrors($('contact-fields'), error);
      showNotice($('lead-notice'), marked ? 'Revisá los datos marcados.' : error.message);
    }
  });
});

if (!code || !token) {
  fail('El enlace de la cotización está incompleto. Abrilo desde el mensaje o la página donde lo guardaste.');
} else {
  publicApi('GET', `/api/v1/public/quotes/${code}`, { token })
    .then(render)
    .catch((error) => fail(error.status === 404 ? 'No encontramos esta cotización. Revisá que el enlace esté completo.' : error.message));
}
