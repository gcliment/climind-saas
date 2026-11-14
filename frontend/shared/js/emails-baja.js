// Baja de los emails de producto con el enlace del email. Se confirma con un botón (POST): los antivirus de
// correo que abren los enlaces no dan de baja a nadie.
import { publicApi } from './public-quote.js';
import { showNotice, withBusy } from './ui.js';

const $ = (id) => document.getElementById(id);
const token = new URLSearchParams(window.location.search).get('t');

if (!token) {
  showNotice($('unsub-notice'), 'El enlace está incompleto. Podés cambiar tus preferencias desde Tu cuenta.');
  $('unsub-button').hidden = true;
}

$('unsub-button').addEventListener('click', (event) => {
  withBusy(event.currentTarget, async () => {
    try {
      const result = await publicApi('POST', `/api/v1/public/emails/unsubscribe?t=${encodeURIComponent(token)}`);
      $('unsub-ask').hidden = true;
      $('unsub-done-text').textContent = result.message;
      $('unsub-done').hidden = false;
      $('unsub-done').focus();
    } catch (error) {
      showNotice($('unsub-notice'), error.message);
    }
  });
});
