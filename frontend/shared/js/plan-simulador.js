// Simulador del checkout de Mercado Pago (solo perfiles dev y test; en producción la API /api/dev no existe).
import { api } from './api.js';
import { formatMoney } from './format.js';
import { initPanel } from './panel.js';
import { showNotice, withBusy } from './ui.js';

await initPanel({ current: '/app/plan' });

const $ = (id) => document.getElementById(id);
const notice = $('sim-notice');
const sub = new URLSearchParams(window.location.search).get('sub');

try {
  const overview = await api.get('/api/v1/billing');
  if (!overview.simulator || !sub) {
    showNotice(notice, 'El simulador de pagos no está disponible en este entorno.', 'warn');
  } else {
    const pending = overview.pendingCheckout;
    $('sim-text').textContent = pending && pending.checkoutUrl?.endsWith(sub)
      ? `Contratación del plan ${pending.planName} por ${formatMoney(pending.price, { decimals: 0 })} por mes.`
      : 'Suscripción simulada: podés simular un cobro de renovación o una cancelación.';
    $('sim-block').hidden = false;
  }
} catch (error) {
  showNotice(notice, error.message);
}

document.querySelectorAll('[data-charge], [data-status]').forEach((button) => {
  button.addEventListener('click', (e) => withBusy(e.currentTarget, async () => {
    try {
      const path = button.dataset.charge
        ? `/api/dev/payments/${encodeURIComponent(sub)}/charge?result=${button.dataset.charge}`
        : `/api/dev/payments/${encodeURIComponent(sub)}/status?value=${button.dataset.status}`;
      await api.post(path);
      window.location.assign('/app/plan?retorno=1');
    } catch (error) {
      showNotice(notice, error.message);
    }
  }));
});
