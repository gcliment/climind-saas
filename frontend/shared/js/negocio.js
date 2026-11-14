// Perfil del negocio.
import { api, ApiError } from './api.js';
import { initPanel } from './panel.js';
import { clearFieldErrors, formData, hideNotice, showFieldErrors, showNotice, withBusy } from './ui.js';

await initPanel({ current: '/app/negocio' });

const form = document.getElementById('profile-form');
const notice = document.getElementById('profile-notice');
const status = document.getElementById('save-status');
const appUrl = window.CLIMIND_CONFIG?.appUrl || window.location.origin;

// ------------------------------------------------------------------ logo

const logoPreview = document.getElementById('logo-preview');
const logoRemove = document.getElementById('logo-remove');
const logoNotice = document.getElementById('logo-notice');

function showLogo(url) {
  logoRemove.hidden = !url;
  if (!url) { logoPreview.textContent = 'Sin logo'; return; }
  const img = document.createElement('img');
  img.src = url;
  img.alt = 'Logo actual del negocio';
  logoPreview.replaceChildren(img);
}

document.getElementById('logo-file').addEventListener('change', async (event) => {
  const file = event.target.files[0];
  if (!file) return;
  hideNotice(logoNotice);
  if (file.size > 2 * 1024 * 1024) { showNotice(logoNotice, 'El logo puede pesar hasta 2 MB.'); return; }
  const body = new FormData();
  body.append('file', file);
  logoPreview.textContent = 'Subiendo…';
  try {
    showLogo((await api.upload('/api/v1/business/logo', body)).logoUrl);
    showNotice(logoNotice, 'Logo actualizado.', 'ok');
  } catch (error) {
    showLogo(null);
    showNotice(logoNotice, error.fieldErrors?.[0]?.message || error.message);
  } finally {
    event.target.value = '';
  }
});

logoRemove.addEventListener('click', () => withBusy(logoRemove, async () => {
  try {
    await api.del('/api/v1/business/logo');
    showLogo(null);
    showNotice(logoNotice, 'Quitaste el logo.', 'ok');
  } catch (error) {
    showNotice(logoNotice, error.message);
  }
}));

function fill(profile) {
  showLogo(profile.logoUrl);
  for (const name of ['businessName', 'industry', 'whatsapp', 'phone', 'contactEmail', 'website', 'description']) {
    const value = profile[name];
    form.elements.namedItem(name).value = name === 'whatsapp' && value ? `+${value}` : (value ?? '');
  }
  document.getElementById('public-link').textContent = `${appUrl}/c/${profile.publicSlug}/…`;
  document.querySelectorAll('[data-business-name]').forEach((el) => { el.textContent = profile.businessName; });
}

try {
  const [industries, profile] = await Promise.all([
    api.get('/api/v1/catalog/industries'),
    api.get('/api/v1/business/profile'),
  ]);
  const select = form.elements.namedItem('industry');
  industries.forEach(({ code, label }) => select.add(new Option(label, code)));
  fill(profile);
} catch (error) {
  showNotice(notice, error.message);
}

form.addEventListener('input', () => { status.hidden = true; });
form.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearFieldErrors(form);
  hideNotice(notice);
  status.hidden = true;
  const data = formData(form);
  if (!data.businessName) {
    showFieldErrors(form, [{ field: 'businessName', message: 'Ingresá el nombre de tu negocio.' }]);
    return;
  }
  await withBusy(form.querySelector('[type="submit"]'), async () => {
    try {
      fill(await api.put('/api/v1/business/profile', data));
      status.hidden = false;
    } catch (error) {
      if (error instanceof ApiError && error.fieldErrors.length) showFieldErrors(form, error.fieldErrors);
      else showNotice(notice, error.message);
    }
  });
});

// ------------------------------------------------------------------ preferencias

const settingsForm = document.getElementById('settings-form');
const settingsNotice = document.getElementById('settings-notice');
const settingsStatus = document.getElementById('settings-status');
const retention = document.getElementById('leadRetentionMonths');

function showSettings(settings) {
  document.getElementById('notifyNewLeadEmail').checked = settings.notifyNewLeadEmail;
  const value = String(settings.leadRetentionMonths);
  if (![...retention.options].some((o) => o.value === value)) retention.add(new Option(`${value} meses`, value));
  retention.value = value;
}

settingsForm.addEventListener('submit', (event) => {
  event.preventDefault();
  hideNotice(settingsNotice);
  settingsStatus.hidden = true;
  withBusy(event.submitter, async () => {
    try {
      showSettings(await api.put('/api/v1/business/settings', {
        notifyNewLeadEmail: document.getElementById('notifyNewLeadEmail').checked,
        leadRetentionMonths: Number(retention.value),
      }));
      settingsStatus.hidden = false;
    } catch (error) {
      showNotice(settingsNotice, error.message);
    }
  });
});

api.get('/api/v1/business/settings').then(showSettings).catch((error) => showNotice(settingsNotice, error.message));
