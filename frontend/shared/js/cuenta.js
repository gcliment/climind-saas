// Tu cuenta: datos personales, contraseña, emails de producto, descarga de datos y baja de la cuenta.
import { api, ApiError } from './api.js';
import { renderInstallGuide } from './app-extras.js';
import { initPanel } from './panel.js';
import {
  clearFieldErrors, formData, hideNotice, setupPasswordToggles, showFieldErrors, showNotice, withBusy,
} from './ui.js';

await initPanel({ current: '/app/cuenta', requireTenant: false });
setupPasswordToggles();
const $ = (id) => document.getElementById(id);
const notice = $('account-notice');
const longDate = new Intl.DateTimeFormat('es-AR', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'America/Argentina/Buenos_Aires' });

// Instalar la app: botón del navegador, pasos para iPhone según el navegador o cómo hacerlo desde el menú.
renderInstallGuide(document.getElementById('install-guide'));

// Apariencia: preferencia de este dispositivo (la aplica theme.js).
const themeChoices = document.querySelectorAll('#theme-choices input[name="theme"]');
const syncThemeChoices = () => {
  const mode = window.climindTheme?.get() || 'auto';
  themeChoices.forEach((input) => { input.checked = input.value === mode; });
};
syncThemeChoices();
themeChoices.forEach((input) => input.addEventListener('change', () => {
  window.climindTheme?.set(input.value);
  document.dispatchEvent(new CustomEvent('climind:themechange'));
}));
document.addEventListener('climind:themechange', syncThemeChoices);

let account = await api.get('/api/v1/account');

function render() {
  $('firstName').value = account.firstName;
  $('lastName').value = account.lastName;
  $('account-email').textContent = account.email;
  $('productEmails').checked = account.productEmails;
  $('owner-data').hidden = !account.owner;
  $('grace-days').textContent = `${account.deletionGraceDays} días`;
  const scheduled = account.deletionScheduledFor;
  $('owner-delete').hidden = !account.owner || Boolean(scheduled);
  $('deletion-pending').hidden = !scheduled;
  if (scheduled) {
    $('deletion-pending-text').textContent = `El ${longDate.format(new Date(scheduled))} vamos a eliminar los datos de ${account.businessName}. `
      + 'Tus cotizadores ya no están disponibles para tus clientes. Si te arrepentiste, cancelá la baja: todo vuelve a funcionar '
      + '(si tenías un plan pago, lo vas a tener que contratar de nuevo).';
  }
}
render();

function flash(id, text) {
  const el = $(id);
  el.textContent = text;
  setTimeout(() => { if (el.textContent === text) el.textContent = ''; }, 4000);
}

async function submit(form, action, onSuccess) {
  hideNotice(notice);
  clearFieldErrors(form);
  const button = form.querySelector('[type="submit"]');
  await withBusy(button, async () => {
    try {
      await onSuccess(await action());
    } catch (error) {
      if (!(error instanceof ApiError)) throw error;
      if (!showFieldErrors(form, error.fieldErrors)) showNotice(notice, error.message);
    }
  });
}

// ------------------------------------------------------------------ datos personales

$('profile-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  submit(form, () => api.put('/api/v1/account/profile', formData(form)), (updated) => {
    account = updated;
    render();
    const fullName = `${account.firstName} ${account.lastName || ''}`.trim();
    document.querySelectorAll('[data-user-name]').forEach((el) => { el.textContent = fullName; });
    flash('profile-status', 'Guardado');
  });
});

// ------------------------------------------------------------------ contraseña

$('password-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  if (data.password !== data.passwordConfirmation) {
    clearFieldErrors(form);
    showFieldErrors(form, [{ field: 'passwordConfirmation', message: 'Las contraseñas no coinciden.' }]);
    return;
  }
  submit(form, () => api.post('/api/v1/account/password', data), () => {
    form.reset();
    flash('password-status', 'Listo: cambiaste tu contraseña. Te mandamos un email de aviso.');
  });
});

// ------------------------------------------------------------------ emails de producto

$('productEmails').addEventListener('change', async (event) => {
  const input = event.currentTarget;
  input.disabled = true;
  try {
    account = await api.put('/api/v1/account/email-preferences', { productEmails: input.checked });
    flash('emails-status', input.checked ? 'Vas a recibir consejos y el resumen mensual.' : 'Listo: no vas a recibir consejos ni resúmenes.');
  } catch (error) {
    input.checked = !input.checked;
    showNotice(notice, error.message);
  } finally {
    input.disabled = false;
  }
});

// ------------------------------------------------------------------ descargar datos

$('export-button').addEventListener('click', (event) => {
  const button = event.currentTarget;
  hideNotice(notice);
  withBusy(button, async () => {
    const response = await fetch('/api/v1/account/export', { credentials: 'same-origin' }).catch(() => null);
    if (!response || !response.ok) {
      const body = response ? await response.json().catch(() => null) : null;
      showNotice(notice, body?.message || 'No pudimos preparar el archivo. Probá de nuevo en unos minutos.');
      return;
    }
    const disposition = response.headers.get('Content-Disposition') || '';
    const name = /filename="?([^";]+)"?/.exec(disposition)?.[1] || 'climind-cotiza-datos.zip';
    const url = URL.createObjectURL(await response.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    flash('export-status', 'Descarga lista.');
  });
});

// ------------------------------------------------------------------ baja de la cuenta

const dialog = $('delete-dialog');
$('delete-open').addEventListener('click', () => {
  const date = new Date(Date.now() + account.deletionGraceDays * 86400000);
  $('delete-date').textContent = longDate.format(date);
  $('delete-form').reset();
  clearFieldErrors($('delete-form'));
  hideNotice($('delete-notice'));
  dialog.showModal();
});
$('delete-close').addEventListener('click', () => dialog.close());

$('delete-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  clearFieldErrors(form);
  hideNotice($('delete-notice'));
  await withBusy(form.querySelector('[type="submit"]'), async () => {
    try {
      const status = await api.post('/api/v1/account/deletion', formData(form));
      dialog.close();
      account = await api.get('/api/v1/account');
      render();
      showNotice(notice, status.canceledPaidPlan
        ? 'Programamos la baja y cancelamos tu plan pago en Mercado Pago. Te mandamos un email con el detalle.'
        : 'Programamos la baja de tu cuenta. Te mandamos un email con el detalle.', 'ok');
      notice.focus();
    } catch (error) {
      if (!(error instanceof ApiError)) throw error;
      if (!showFieldErrors(form, error.fieldErrors)) showNotice($('delete-notice'), error.message);
    }
  });
});

$('deletion-cancel').addEventListener('click', (event) => {
  hideNotice(notice);
  withBusy(event.currentTarget, async () => {
    try {
      account = await api.del('/api/v1/account/deletion');
      render();
      showNotice(notice, 'Cancelaste la baja: tu cuenta y tus cotizadores siguen funcionando.', 'ok');
      notice.focus();
    } catch (error) {
      showNotice(notice, error.message);
    }
  });
});

// Desde «Menú → Instalar la app»: ir directo a la guía, ya con la página completa (si no, el contenido que
// carga después la corre de lugar).
if (window.location.hash === '#instalar') {
  const block = document.getElementById('instalar');
  setTimeout(() => {
    block.scrollIntoView({ block: 'start', behavior: 'instant' });
    block.focus({ preventScroll: true });
  }, 0);
}
